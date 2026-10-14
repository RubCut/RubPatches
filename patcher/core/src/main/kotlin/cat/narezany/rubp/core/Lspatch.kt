package cat.narezany.rubp.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Embedding LSPosed's LSPatch (the JingMatrix fork) into an apk, the way the
 * official patcher does it: the original apk goes in whole as a nested stored
 * entry, the metaloader dex becomes the outer classes.dex, the loader and its
 * native library live under assets/lspatch/, and the manifest's
 * appComponentFactory is pointed at LSPatch's stub with the patch config in a
 * meta-data tag. Modules are baked in as assets/lspatch/modules/<pkg>.apk.
 *
 * The linked-entry trick keeps the result the size of the original plus the
 * loader, rather than twice the original.
 */
object Lspatch {

    const val CONFIG_ASSET = "assets/lspatch/config.json"
    const val LOADER_DEX_ASSET = "assets/lspatch/loader.dex"
    const val META_LOADER_DEX_ASSET = "assets/lspatch/metaloader.dex"
    const val ORIGIN_APK_ASSET = "assets/lspatch/origin.apk"
    const val MODULES_ASSET = "assets/lspatch/modules/"
    const val STUB_FACTORY = "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub"

    /** The JingMatrix release the loader assets and the config values come from. */
    const val JAR_URL =
        "https://github.com/JingMatrix/LSPatch/releases/download/v1.2/lspatch-v1.2-487-release.jar"
    const val JAR_SHA256 = "d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165"

    class Refused(message: String) : IOException(message)

    /** The loader payload, read out of the lspatch jar. */
    class Assets(
        val metaloader: ByteArray,
        val loader: ByteArray,
        val so: Map<String, ByteArray>,
        val lspConfig: JSONObject,
    )

    fun loadAssets(jar: File): Assets {
        ZipFile(jar).use { zip ->
            fun bytes(name: String): ByteArray {
                val entry = zip.getEntry(name) ?: throw Refused("the lspatch jar has no $name")
                return zip.getInputStream(entry).use { it.readBytes() }
            }
            val so = LinkedHashMap<String, ByteArray>()
            for (entry in zip.entries()) {
                if (entry.name.startsWith("assets/lspatch/so/") && entry.name.endsWith(".so")) {
                    so[entry.name.removePrefix("assets/lspatch/so/")] = bytes(entry.name)
                }
            }
            if (so.isEmpty()) throw Refused("the lspatch jar has no native libraries")
            return Assets(
                bytes(META_LOADER_DEX_ASSET),
                bytes(LOADER_DEX_ASSET),
                so,
                JSONObject()
                    .put("API_CODE", 102)
                    .put("VERSION_CODE", 487)
                    .put("VERSION_NAME", "1.2")
                    .put("CORE_VERSION_CODE", 7306)
                    .put("CORE_VERSION_NAME", "canary-3106")
                    .put("CORE_VERSION_HASH", "e00c5c5038bbcc14e0b382ab893301d6993e6989"),
            )
        }
    }

    /** The package name of a module apk, from its manifest. */
    fun modulePackage(apk: File): String {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw Refused("${apk.name} is not an apk")
            val manifest = Axml.parse(zip.getInputStream(entry).use { it.readBytes() })
            val root = manifest.elements("manifest").firstOrNull()
                ?: throw Refused("${apk.name} has no manifest element")
            return manifest.attrString(root, "package")
                ?: throw Refused("${apk.name} has no package name")
        }
    }

    class Options(
        val debuggable: Boolean = false,
        val sigBypassLevel: Int = 0,
        val modules: List<File> = emptyList(),
        /** arches to carry; null carries every one the jar has */
        val arches: Set<String>? = null,
    )
}
