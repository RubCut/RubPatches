package cat.narezany.patcher.work

import android.content.Context
import android.net.Uri
import cat.narezany.patcher.core.Customize
import cat.narezany.patcher.core.Lspatch
import cat.narezany.patcher.core.Patch
import cat.narezany.patcher.core.Sign
import cat.narezany.patcher.data.Mod
import cat.narezany.patcher.data.Net
import cat.narezany.patcher.data.Prefs
import cat.narezany.patcher.data.Release
import cat.narezany.patcher.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Making a mod's apk, one at a time, from the moment the person asks to the
 * apk lying ready to install.
 *
 * It lives outside any screen: the work takes minutes and a screen does not
 * last that long. Screens watch [state]; a service keeps the app alive and
 * shows the same progress in a notification while there is any.
 */
object Engine {

    enum class Stage { ORIGINAL, CHECKING, PATCH, MAKING, APK, CUSTOMIZE }

    /** The steps a release goes through, in order: a patched one has four, one handed out whole has one. */
    fun stages(release: Release): List<Stage> =
        if (release.whole) listOf(Stage.APK) else listOf(Stage.ORIGINAL, Stage.CHECKING, Stage.PATCH, Stage.MAKING)

    /** Why it stopped, for the screen to put into the person's language. */
    enum class Trouble { NETWORK, WRONG_APK, BAD_PATCH, NO_ROOM, OTHER }

    sealed class State {
        data object Idle : State()

        /** TikTok's own apk is not here yet, and the person is asked where from. */
        data class Asking(val mod: Mod, val release: Release) : State()

        data class Working(val mod: Mod, val release: Release, val stage: Stage,
                           val done: Long, val of: Long) : State()

        data class Ready(val mod: Mod, val release: Release, val apk: File) : State()

        data class Failed(val mod: Mod, val release: Release, val trouble: Trouble,
                          val detail: String) : State()
    }

    private val flow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = flow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Each run carries its own flag: a shared one meant a new run switched a
     * still-stopping old one back to life, and its half-finished writes mixed
     * into the new run's files.
     */
    /** One run's stop flag; a new run gets a new one, so it cannot resurrect the old. */
    private class Halt { @Volatile var asked = false }
    private var halt = Halt()

    private fun room(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun original(context: Context, release: Release) =
        File(room(context), "original/${release.baseSha256}.apk")

    private fun patch(context: Context, release: Release) =
        File(room(context), "patch/${release.patchSha256}.mpatch")

    fun made(context: Context, mod: Mod, release: Release) =
        File(room(context), "made/${mod.id}-${release.version}.apk")

    /** How much the app is keeping on the phone, in bytes. */
    fun kept(context: Context): Long =
        room(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear(context: Context) {
        if (flow.value is State.Working) return
        room(context).listFiles()?.forEach { it.deleteRecursively() }
        flow.value = State.Idle
    }

    val busy: Boolean get() = flow.value is State.Working

    /** What the customization choices are right now, or empty for "as released". */
    private fun customKey(context: Context): String {
        val prefs = Prefs.of(context).now.value
        if (prefs.customPackage.isBlank() && prefs.customLabel.isBlank() &&
            prefs.customIcon.isBlank() && !prefs.lspatch) return ""
        // the recipe version rides along: a fix in how customization is
        // applied must rebuild what an older patcher already made
        return listOf("r5", prefs.customPackage, prefs.customLabel, prefs.customIcon,
            prefs.lspatch, prefs.sigbypass, prefs.localPatch,
            prefs.lspatchModules.sorted().joinToString(",")).joinToString("|")
    }

    /** The mark a customized build carries, so changed choices never serve a stale apk. */
    private fun stamp(context: Context, mod: Mod, release: Release) =
        File(room(context), "made/${mod.id}-${release.version}.stamp")

    /** Begin: with the original already here it goes straight on, otherwise it asks. */
    fun start(context: Context, mod: Mod, release: Release) {
        if (busy) return
        val app = context.applicationContext
        val ready = made(app, mod, release)
        val key = customKey(app)
        val sound = if (key.isEmpty()) {
            ready.exists() && ready.length() == release.resultSize &&
                !stamp(app, mod, release).exists()
        } else {
            ready.exists() && stamp(app, mod, release).readTextOrNull() == key
        }
        if (sound) {
            flow.value = State.Ready(mod, release, ready)
            return
        }
        if (release.whole) whole(app, mod, release)
        else if (original(app, release).exists()) run(app, mod, release) { }
        else flow.value = State.Asking(mod, release)
    }

    private fun File.readTextOrNull(): String? =
        try { if (isFile) readText() else null } catch (gone: java.io.IOException) { null }

    /** The person chose a place the apk can be fetched from as it is. */
    fun fetch(context: Context, mod: Mod, release: Release, source: Source) {
        if (busy) return
        val app = context.applicationContext
        run(app, mod, release) { halt ->
            flow.value = State.Working(mod, release, Stage.ORIGINAL, 0, release.baseSize)
            val unchecked = File(original(app, release).path + ".new")
            unchecked.delete()
            Net.file(source.url, unchecked, 0L, { halt.asked }) { done, _ ->
                flow.value = State.Working(mod, release, Stage.ORIGINAL, done, release.baseSize)
            }
            take(app, mod, release, unchecked, halt)
        }
    }

    /** The person pointed at an apk they already have. */
    fun pick(context: Context, mod: Mod, release: Release, chosen: Uri) {
        if (busy) return
        val app = context.applicationContext
        run(app, mod, release) { halt ->
            flow.value = State.Working(mod, release, Stage.ORIGINAL, 0, release.baseSize)
            val unchecked = File(original(app, release).path + ".new")
            unchecked.parentFile?.mkdirs()
            val input = app.contentResolver.openInputStream(chosen)
                ?: throw Patch.Refused("the file could not be opened")
            input.use { from ->
                unchecked.outputStream().use { to ->
                    val block = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        if (halt.asked) throw Net.Cancelled()
                        val got = from.read(block)
                        if (got < 0) break
                        to.write(block, 0, got)
                        done += got
                        if (done and 0xFFFFF < got) {
                            flow.value = State.Working(mod, release, Stage.ORIGINAL, done, release.baseSize)
                        }
                    }
                }
            }
            take(app, mod, release, unchecked, halt)
        }
    }

    /** An apk has arrived: it is kept only if it is the one the patch is for. */
    private fun take(context: Context, mod: Mod, release: Release, unchecked: File, halt: Halt) {
        flow.value = State.Working(mod, release, Stage.CHECKING, 0, unchecked.length())
        val right = unchecked.length() == release.baseSize && Patch.sha256(unchecked, { halt.asked }) { seen ->
            flow.value = State.Working(mod, release, Stage.CHECKING, seen, unchecked.length())
        } == release.baseSha256
        if (!right) {
            unchecked.delete()
            throw WrongApk()
        }
        val kept = original(context, release)
        kept.delete()
        if (!unchecked.renameTo(kept)) {
            // renameTo fails across mounts: fall back to a copy
            unchecked.inputStream().use { from -> kept.outputStream().use { from.copyTo(it) } }
            unchecked.delete()
        }
    }

    private class WrongApk : java.io.IOException("not the apk the patch is for")

    /** A mod handed out as a finished apk: fetched, checked against its hash, and that is all. */
    private fun whole(context: Context, mod: Mod, release: Release) {
        val halt = Halt().also { halt = it }
        flow.value = State.Working(mod, release, Stage.APK, 0, release.resultSize)
        WorkService.start(context)
        job = scope.launch {
            val out = made(context, mod, release)
            try {
                out.parentFile?.mkdirs()
                out.parentFile?.listFiles()?.forEach { if (it.name.startsWith(mod.id + "-")) it.delete() }
                Net.file(release.apkUrl, out, release.resultSize, { halt.asked }) { done, of ->
                    flow.value = State.Working(mod, release, Stage.APK, done, of)
                }
                if (Patch.sha256(out, { halt.asked }) != release.apkSha256) {
                    out.delete()
                    throw Patch.Refused("the apk did not arrive whole")
                }
                customize(context, mod, release, out, halt)
                // remember the recipe this apk answers to, so the next start
                // serves it instead of building again
                val madeKey = customKey(context)
                if (madeKey.isNotEmpty()) {
                    val mark = stamp(context, mod, release)
                    mark.parentFile?.mkdirs()
                    mark.writeText(madeKey)
                }
                flow.value = State.Ready(mod, release, out)
            } catch (cancelled: Net.Cancelled) {
                flow.value = State.Idle
            } catch (cancelled: Patch.Cancelled) {
                flow.value = State.Idle
            } catch (refused: Patch.Refused) {
                flow.value = State.Failed(mod, release, Trouble.BAD_PATCH, refused.message ?: "")
            } catch (failed: java.io.IOException) {
                val full = room(context).usableSpace < 64L shl 20
                flow.value = State.Failed(mod, release, if (full) Trouble.NO_ROOM else Trouble.NETWORK,
                    failed.message ?: failed.toString())
            } catch (failed: Throwable) {
                flow.value = State.Failed(mod, release, Trouble.OTHER, failed.toString())
            }
        }
    }

    private fun run(context: Context, mod: Mod, release: Release, first: (Halt) -> Unit) {
        val halt = Halt().also { halt = it }
        flow.value = State.Working(mod, release, Stage.ORIGINAL, 0, release.baseSize)
        WorkService.start(context)
        job = scope.launch {
            try {
                first(halt)
                val base = original(context, release)

                val prefsNow = Prefs.of(context).now.value
                val patch: File
                if (prefsNow.localPatch.isNotBlank()) {
                    // the person's own patch file, chosen in the customization card:
                    // no server round-trip, no hash to answer to
                    flow.value = State.Working(mod, release, Stage.PATCH, 0, 0)
                    patch = File(room(context), "patch/local.mpatch")
                    patch.parentFile?.mkdirs()
                    patch.delete()
                    context.contentResolver.openInputStream(Uri.parse(prefsNow.localPatch))?.use { from ->
                        patch.outputStream().use { to ->
                            val block = ByteArray(1 shl 16)
                            while (true) {
                                if (halt.asked) throw Net.Cancelled()
                                val got = from.read(block)
                                if (got < 0) break
                                to.write(block, 0, got)
                            }
                        }
                    } ?: throw Patch.Refused("the patch file could not be opened")
                } else {
                    patch = patch(context, release)
                    if (!patch.exists()) {
                        flow.value = State.Working(mod, release, Stage.PATCH, 0, release.patchSize)
                        Net.file(release.patchUrl, patch, release.patchSize, { halt.asked }) { done, _ ->
                            flow.value = State.Working(mod, release, Stage.PATCH, done, release.patchSize)
                        }
                        if (Patch.sha256(patch, { halt.asked }) != release.patchSha256) {
                            patch.delete()
                            throw Patch.Refused("the patch did not arrive whole")
                        }
                    }
                }

                flow.value = State.Working(mod, release, Stage.MAKING, 0, release.resultSize)
                val out = made(context, mod, release)
                out.parentFile?.mkdirs()
                // anything made for this mod's earlier releases is in the way now
                out.parentFile?.listFiles()?.forEach { if (it.name.startsWith(mod.id + "-")) it.delete() }
                Patch.apply(base, patch, out, { halt.asked }) { done, of ->
                    flow.value = State.Working(mod, release, Stage.MAKING, done, of)
                }
                patch.delete()
                customize(context, mod, release, out, halt)
                // remember the recipe this apk answers to, so the next start
                // serves it instead of building again
                val madeKey = customKey(context)
                if (madeKey.isNotEmpty()) {
                    val mark = stamp(context, mod, release)
                    mark.parentFile?.mkdirs()
                    mark.writeText(madeKey)
                }
                if (!Prefs.of(context).now.value.keepOriginal) base.delete()
                flow.value = State.Ready(mod, release, out)
            } catch (cancelled: Net.Cancelled) {
                flow.value = State.Idle
            } catch (cancelled: Patch.Cancelled) {
                flow.value = State.Idle
            } catch (wrong: WrongApk) {
                flow.value = State.Failed(mod, release, Trouble.WRONG_APK, "")
            } catch (refused: Patch.Refused) {
                patch(context, release).delete()
                flow.value = State.Failed(mod, release, Trouble.BAD_PATCH, refused.message ?: "")
            } catch (failed: java.io.IOException) {
                val full = room(context).usableSpace < 64L shl 20
                flow.value = State.Failed(mod, release, if (full) Trouble.NO_ROOM else Trouble.NETWORK,
                    failed.message ?: failed.toString())
            } catch (failed: Throwable) {
                flow.value = State.Failed(mod, release, Trouble.OTHER, failed.toString())
            }
        }
    }

    /**
     * Bend the built apk to the person's choices: package name, label, icon,
     * and LSPatch with its modules embedded. Nothing happens when every
     * choice is empty.
     */
    private fun customize(context: Context, mod: Mod, release: Release, out: File, halt: Halt) {
        val prefs = Prefs.of(context).now.value
        val wanted = prefs.customPackage.isNotBlank() || prefs.customLabel.isNotBlank() ||
            prefs.customIcon.isNotBlank() || prefs.lspatch
        if (!wanted) return
        flow.value = State.Working(mod, release, Stage.CUSTOMIZE, 0, 0)

        val icon = if (prefs.customIcon.isNotBlank()) {
            val bytes = context.contentResolver.openInputStream(Uri.parse(prefs.customIcon))
                ?.use { it.readBytes() }
                ?: throw Patch.Refused("the icon could not be opened")
            // PNG magic: a PNG has to start with these eight bytes
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(png)) {
                throw Patch.Refused("the icon must be a PNG picture")
            }
            bytes
        } else null

        var jar: File? = null
        val moduleFiles = ArrayList<File>()
        if (prefs.lspatch) {
            jar = File(room(context), "lspatch.jar")
            val sound = jar.exists() &&
                Patch.sha256(jar, { halt.asked }) { } == Lspatch.JAR_SHA256
            if (!sound) {
                jar.delete()
                Net.file(Lspatch.JAR_URL, jar, 0L, { halt.asked }) { _, _ -> }
                if (Patch.sha256(jar, { halt.asked }) { } != Lspatch.JAR_SHA256) {
                    jar.delete()
                    throw Patch.Refused("the lspatch jar did not arrive whole")
                }
            }
            val dir = File(room(context), "modules")
            dir.deleteRecursively()
            dir.mkdirs()
            for ((number, uriText) in prefs.lspatchModules.withIndex()) {
                val file = File(dir, "module$number.apk")
                context.contentResolver.openInputStream(Uri.parse(uriText))?.use { from ->
                    file.outputStream().use { from.copyTo(it) }
                } ?: throw Patch.Refused("a module could not be opened")
                moduleFiles.add(file)
            }
        }

        val options = Customize.Options(
            packageName = prefs.customPackage.ifBlank { null },
            label = prefs.customLabel.ifBlank { null },
            iconPng = icon,
            lspatchJar = jar,
            sigBypassLevel = prefs.sigbypass,
            modules = moduleFiles,
        )
        val bent = File(out.parentFile, out.name + ".custom")
        try {
            Customize.apply(out, bent, options, Sign.key())
        } catch (refused: Customize.Refused) {
            throw Patch.Refused(refused.message ?: "could not customize")
        }
        out.delete()
        if (!bent.renameTo(out)) {
            bent.inputStream().use { from -> out.outputStream().use { from.copyTo(it) } }
            bent.delete()
        }
    }

    fun cancel() {
        halt.asked = true
        if (flow.value is State.Asking || flow.value is State.Failed || flow.value is State.Ready) {
            flow.value = State.Idle
        }
    }

    /** The made apk is installed, or the person has done with it. */
    fun done(context: Context) {
        val now = flow.value
        if (now is State.Ready) now.apk.delete()
        if (now !is State.Working) flow.value = State.Idle
    }
}
