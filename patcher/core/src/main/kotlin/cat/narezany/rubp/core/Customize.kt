package cat.narezany.rubp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Making a built apk one's own: another package name, another name and icon
 * on the home screen, and -- when asked -- LSPatch embedded with its modules,
 * so the result installs beside the official app and carries LSPosed inside.
 *
 * The apk this runs on is the release the patch made: byte-exact until now,
 * so anything changed here is re-signed with the patcher's own key.
 */
object Customize {

    class Refused(message: String) : IOException(message)

    class Options(
        /** New package name, or null to keep the app's own. */
        val packageName: String? = null,
        /** New launcher label, or null to keep the app's own. */
        val label: String? = null,
        /** A PNG to replace the launcher icon with, or null to keep it. */
        val iconPng: ByteArray? = null,
        /** The lspatch jar to embed from, or null for no LSPatch at all. */
        val lspatchJar: File? = null,
        /** LSPatch's signature bypass: 0 off, 1 package manager, 2 also file reads. */
        val sigBypassLevel: Int = 2,
        val debuggable: Boolean = false,
        /** Module apks to bake in; only with [lspatchJar]. */
        val modules: List<File> = emptyList(),
        /** ABIs to carry liblspatch.so for; null carries every one the jar has. */
        val arches: Set<String>? = null,
    ) {
        val wanted: Boolean
            get() = packageName != null || label != null || iconPng != null ||
                lspatchJar != null || modules.isNotEmpty()
    }

    /**
     * Write [source] to [out] with [options] applied, signed with [key].
     * [progress] is told what is happening, in English; screens translate the stage.
     */
    fun apply(source: File, out: File, options: Options, key: Sign.Key) {
        if (!options.wanted) throw Refused("nothing to do")
        if (options.modules.isNotEmpty() && options.lspatchJar == null) {
            throw Refused("modules need LSPatch embedded")
        }
        val zip = ApkZip(source)
        try {
            val manifest = Axml.parse(zip.read("AndroidManifest.xml"))
            val root = manifest.elements("manifest").firstOrNull()
                ?: throw Refused("the apk has no manifest")
            val application = manifest.elements("application").firstOrNull()
                ?: throw Refused("the apk has no application element")
            val oldPackage = manifest.attrString(root, "package")
                ?: throw Refused("the manifest has no package name")

            // the resource table the result carries: renamed with the package,
            // and -- for a custom picture -- grown by one mipmap entry of its
            // own, so the baked aliases point at a resource that exists. With
            // LSPatch the nested original keeps the rename-only table while
            // the outer one carries the extra package.
            var customIconId: Int? = null
            var renamedArsc: ByteArray? = null
            var outerArsc: ByteArray? = null
            if (options.packageName != null || options.iconPng != null) {
                val sourceArsc = try {
                    zip.read("resources.arsc")
                } catch (missing: Exception) {
                    throw Refused("the apk has no resource table")
                }
                renamedArsc = if (options.packageName != null) {
                    Arsc.renamed(sourceArsc, oldPackage, options.packageName)
                } else null
                if (options.iconPng != null) {
                    val added = try {
                        ResAdd.addIcon(renamedArsc ?: sourceArsc)
                    } catch (broken: ResAdd.TableError) {
                        throw Refused("could not add the icon: ${broken.message}")
                    }
                    customIconId = added.resId
                    outerArsc = added.data
                }
            }
            // without LSPatch there is only the one table
            val finalArsc = outerArsc ?: renamedArsc

            editManifest(manifest, root, application, oldPackage, options, customIconId)

            if (options.lspatchJar != null) {
                writeLspatched(zip, source, out, options, key, manifest, application,
                    renamedArsc, outerArsc)
            } else {
                zip.replace("AndroidManifest.xml", manifest.build())
                if (finalArsc != null) zip.replace("resources.arsc", finalArsc)
                if (options.iconPng != null) {
                    // the file the new table entry points at, and the copy
                    // the mod's icon list shows
                    zip.add(ResAdd.PATH, options.iconPng)
                    zip.add("assets/rubtt/icon_custom.png", options.iconPng)
                }
                if (options.label != null) {
                    // and the name switch reads it from here
                    zip.add(CUSTOM_LABEL_ASSET, options.label.toByteArray(Charsets.UTF_8))
                }
                Sign.sign(zip, key) { entry -> zip.read(entry.name) }
                zip.write(out)
                Sign.v2(out, key)
            }
        } finally {
            zip.close()
        }
    }

    // ------------------------------------------------------------ manifest

    private fun editManifest(
        manifest: Axml, root: Axml.Node, application: Axml.Node,
        oldPackage: String, options: Options, customIconId: Int?,
    ) {
        if (options.packageName != null && options.packageName != oldPackage) {
            val newPackage = options.packageName
            if (!newPackage.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
                throw Refused("not a package name: $newPackage")
            }
            // the manifest's own package attribute has no namespace
            val attribute = root.attributes.firstOrNull {
                manifest.string(it.name) == "package"
            } ?: throw Refused("the manifest has no package attribute")
            val index = manifest.pool.index(newPackage)
            attribute.raw = index
            attribute.kind = Axml.TYPE_STRING
            attribute.data = index
            // authorities and other global names that begin with the old name
            // move with it, so the clone does not collide with the original
            // or with any other mod of the same app
            for (node in manifest.nodes) {
                if (node.kind != Axml.RES_XML_START_ELEMENT) continue
                val element = manifest.string(node.name)
                for (a in node.attributes) {
                    if (a.kind != Axml.TYPE_STRING) continue
                    val value = manifest.string(a.data) ?: continue
                    val attribute = manifest.string(a.name)
                    val moved = when {
                        // a provider's authority is global to the phone, whatever it starts with;
                        // several can be listed, split by semicolons
                        attribute == "authorities" && element == "provider" ->
                            value.split(';').joinToString(";") { one ->
                                if (one.startsWith("$oldPackage.")) newPackage + one.substring(oldPackage.length)
                                else "$newPackage.$one"
                            }
                        // a permission the app declares, asks for, or guards a part with
                        attribute == "name" && (element == "permission" || element == "permission-group" ||
                            element == "permission-tree" || element == "uses-permission") &&
                            value.startsWith("$oldPackage.") ->
                            newPackage + value.substring(oldPackage.length)
                        (attribute == "permission" || attribute == "readPermission" ||
                            attribute == "writePermission") && value.startsWith("$oldPackage.") ->
                            newPackage + value.substring(oldPackage.length)
                        // anything else that is the old name exactly, but never a class name
                        value == oldPackage && attribute != "name" -> newPackage
                        else -> continue
                    }
                    val at = manifest.pool.index(moved)
                    a.data = at
                    a.raw = at
                }
            }
        }
        if (options.label != null || options.iconPng != null) {
            bakeAliases(manifest, application, options, customIconId)
        }
    }

    /**
     * The patcher's own launcher entries, so what a person named and drew is
     * a switch inside the mod rather than a rebuild: one alias carries the
     * picture, another the picture and the name, both off until chosen. The
     * mod finds the name itself in `assets/rubtt/custom_label.txt`.
     *
     * The picture is a resource of its own ([ResAdd]), never TikTok's icon
     * overwritten: on Android 8 and up the launcher reads the adaptive icon,
     * so painted-over legacy PNGs never reached the home screen at all.
     */
    private fun bakeAliases(
        manifest: Axml, application: Axml.Node, options: Options, customIconId: Int?,
    ) {
        val target = launcherTarget(manifest)
            ?: throw Refused("the apk has no launcher entry to stand in for")
        val icon = customIconId
            ?: manifest.attr(application, "icon")
                ?.takeIf { it.kind == Axml.TYPE_REFERENCE }?.data
            ?: throw Refused("the apk has no icon to stand in for")
        val appLabel = manifest.attr(application, "label")
        val labelRef = appLabel?.takeIf { it.kind == Axml.TYPE_REFERENCE }?.data
        val labelText = appLabel?.takeIf { it.kind == Axml.TYPE_STRING }
            ?.let { manifest.string(it.data) }
        addAlias(manifest, application, ALIAS_CUSTOM, target, icon, labelText, labelRef)
        if (options.label != null) {
            addAlias(manifest, application, ALIAS_CUSTOM_NAMED, target, icon,
                options.label, Axml.NO_ENTRY)
        }
    }

    private fun addAlias(
        manifest: Axml, application: Axml.Node, name: String, target: String,
        icon: Int, label: String?, labelRef: Int?,
    ) {
        val alias = manifest.makeElement("activity-alias")
        manifest.setAttrRef(alias, "icon", icon)
        when {
            label != null -> manifest.setAttrString(alias, "label", label)
            labelRef != null -> manifest.setAttrRef(alias, "label", labelRef)
        }
        manifest.setAttrString(alias, "name", name)
        manifest.setAttrBool(alias, "enabled", false)
        manifest.setAttrBool(alias, "exported", true)
        manifest.setAttrString(alias, "targetActivity", target)

        val filter = manifest.makeElement("intent-filter")
        val action = manifest.makeElement("action")
        manifest.setAttrString(action, "name", "android.intent.action.MAIN")
        val category = manifest.makeElement("category")
        manifest.setAttrString(category, "name", "android.intent.category.LAUNCHER")
        manifest.insertInto(application, listOf(
            alias, filter, action, manifest.closeElement(action),
            category, manifest.closeElement(category),
            manifest.closeElement(filter), manifest.closeElement(alias)))
    }

    /** The activity the home screen's entry opens, whoever fronts for it. */
    private fun launcherTarget(manifest: Axml): String? {
        var inside: String? = null
        var target: String? = null
        var sawMain = false
        var sawLauncher = false
        for (node in manifest.nodes) {
            if (node.kind == Axml.RES_XML_START_ELEMENT) {
                when (manifest.string(node.name)) {
                    "activity", "activity-alias" -> {
                        inside = manifest.string(node.name)
                        target = manifest.attrString(node, "targetActivity")
                            ?: manifest.attrString(node, "name")
                        sawMain = false
                        sawLauncher = false
                    }
                    "action", "category" -> if (inside != null) {
                        when (manifest.attrString(node, "name")) {
                            "android.intent.action.MAIN" -> sawMain = true
                            "android.intent.category.LAUNCHER" -> sawLauncher = true
                        }
                    }
                }
            } else if (node.kind == Axml.RES_XML_END_ELEMENT &&
                manifest.string(node.name) == inside) {
                if (sawMain && sawLauncher && target != null) return target
                inside = null
            }
        }
        return null
    }

    /** Where the mod's icon list and name switch read what was baked. */
    private const val CUSTOM_LABEL_ASSET = "assets/rubtt/custom_label.txt"
    private const val ALIAS_CUSTOM = "cat.narezany.rubtt.IconCustom"
    private const val ALIAS_CUSTOM_NAMED = "cat.narezany.rubtt.IconCustomNamed"

    // ------------------------------------------------------------- lspatch

    private fun writeLspatched(
        zip: ApkZip, source: File, out: File, options: Options, key: Sign.Key,
        manifest: Axml, application: Axml.Node,
        renamedArsc: ByteArray?, outerArsc: ByteArray?,
    ) {
        val assets = Lspatch.loadAssets(options.lspatchJar!!)

        // the loader is built against 28; an app declaring less is refused the APIs it uses
        val usesSdk = manifest.elements("uses-sdk").firstOrNull()
        if (usesSdk != null) {
            val min = manifest.attrString(usesSdk, "minSdkVersion")?.toIntOrNull() ?: 0
            if (min < 28) manifest.setAttrInt(usesSdk, "minSdkVersion", 28)
        }
        val originalFactory = manifest.attrString(application, "appComponentFactory")
        manifest.setAttrString(application, "appComponentFactory", Lspatch.STUB_FACTORY)

        val config = JSONObject()
            .put("useManager", false)
            .put("debuggable", options.debuggable)
            .put("sigBypassLevel", options.sigBypassLevel)
            .put("injectDex", false)
            .put("addedPermissions", JSONArray())
            .put("injectDocumentsProvider", false)
        if (options.sigBypassLevel > 0) {
            val signature = SigInfo.of(source)
                ?: throw Refused("could not read the original apk's signature")
            config.put("originalSignature", signature)
        }
        if (originalFactory != null) config.put("appComponentFactory", originalFactory)
        config.put("lspConfig", assets.lspConfig)
        val configBytes = config.toString().toByteArray(Charsets.UTF_8)

        // the config is written twice: a meta-data the manager can read from
        // an installed app, and an asset the loader reads without one
        val meta = manifest.makeElement("meta-data")
        manifest.setAttrString(meta, "name", "lspatch")
        manifest.setAttrString(meta, "value", Base64.getEncoder().encodeToString(configBytes))
        manifest.insertInto(application, listOf(meta, manifest.closeElement(meta)))

        // the nested original first: stored, page-aligned so the entries the
        // links point at keep the alignment aapt gave them. A renamed app
        // also needs its resource table renamed: it is what getIdentifier
        // looks package names up in, and it is read out of this copy. The
        // entry list is taken from whatever file gets embedded, so the
        // headers' checksums match the bytes they point at.
        var inner = source
        var tempOrigin: File? = null
        if (options.packageName != null || options.iconPng != null
            || options.label != null) {
            val t = File.createTempFile("origin", ".apk", out.parentFile)
            tempOrigin = t
            val oz = ApkZip(source)
            try {
                if (renamedArsc != null) oz.replace("resources.arsc", renamedArsc)
                if (options.iconPng != null) {
                    // the mod's icon list shows it from here
                    oz.add("assets/rubtt/icon_custom.png", options.iconPng)
                }
                if (options.label != null) {
                    // and the name switch reads it from here
                    oz.add(CUSTOM_LABEL_ASSET, options.label.toByteArray(Charsets.UTF_8))
                }
                oz.write(t)
            } finally {
                oz.close()
            }
            inner = t
        }
        val originZip = if (inner == source) null else ApkZip(inner)
        val originals = (originZip?.entries ?: zip.entries).toList()
        zip.entries.clear()
        zip.index.clear()
        try {
            writeLspatchedInner(zip, inner, out, options, key, manifest,
                application, outerArsc, assets, configBytes, originals)
        } finally {
            originZip?.close()
            tempOrigin?.delete()
        }
    }

    private fun writeLspatchedInner(
        zip: ApkZip, originFile: File, out: File, options: Options, key: Sign.Key,
        manifest: Axml, application: Axml.Node, outerArsc: ByteArray?,
        assets: Lspatch.Assets, configBytes: ByteArray, originals: List<ApkZip.Entry>,
    ) {
        val origin = zip.addFile(Lspatch.ORIGIN_APK_ASSET, originFile, align = 4096)
        zip.add("AndroidManifest.xml", manifest.build())
        zip.add("classes.dex", assets.metaloader)
        zip.add(Lspatch.LOADER_DEX_ASSET, assets.loader)
        zip.add(Lspatch.CONFIG_ASSET, configBytes)
        for ((arch, so) in assets.so) {
            if (options.arches != null && arch.substringBefore('/') !in options.arches) continue
            zip.add("assets/lspatch/so/$arch", so, ApkZip.STORED, align = 4096)
        }
        for (module in options.modules) {
            val pkg = Lspatch.modulePackage(module)
            zip.addFile(Lspatch.MODULES_ASSET + pkg + ".apk", module)
        }
        if (options.modules.isNotEmpty()) {
            // the mod's Plugins screen reads this roll: with LSPatch on, the
            // app's asset view is the nested origin and the modules are not
            // in it, so what was embedded is written down here by name
            val roll = options.modules.joinToString("\n",
                transform = { Lspatch.modulePackage(it) + ".apk" })
            zip.add("assets/rubtt/lspatch_modules.txt", roll.toByteArray(Charsets.UTF_8))
        }
        val replaced = mutableSetOf("AndroidManifest.xml")
        if (outerArsc != null) {
            // the outer table carries the custom icon's entry; the launcher
            // reads the alias icon out of this apk, not the nested original
            zip.add("resources.arsc", outerArsc)
            replaced.add("resources.arsc")
        }
        if (options.iconPng != null) {
            zip.add(ResAdd.PATH, options.iconPng)
            replaced.add(ResAdd.PATH)
        }

        fun skipped(name: String): Boolean {
            if (name in replaced) return true
            if (name.startsWith("classes") && name.endsWith(".dex")) return true
            if (ApkZip.isSignatureEntry(name)) return true
            return false
        }
        for (entry in originals) {
            if (skipped(entry.name)) continue
            zip.addLink(entry.name, origin, entry)
        }

        // digests for the v1 signature: linked entries are read back out of
        // the original, which is where their bytes live
        ZipFile(originFile).use { plain ->
            Sign.sign(zip, key) { entry ->
                if (entry.linkBase != null) {
                    val inner = plain.getEntry(entry.name)
                        ?: throw Refused("the original apk has no ${entry.name}")
                    plain.getInputStream(inner).use { it.readBytes() }
                } else {
                    zip.read(entry.name)
                }
            }
        }
        zip.write(out)
        Sign.v2(out, key)
    }
}
