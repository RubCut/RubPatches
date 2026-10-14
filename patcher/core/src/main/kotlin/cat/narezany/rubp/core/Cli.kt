package cat.narezany.rubp.core

import java.io.File
import kotlin.system.exitProcess

/**
 * The engine, run on a computer: what the app will do on a phone, done here
 * first, so that a patch is known to give the release before it is handed out.
 *
 *   BASE.apk PATCH.mpatch OUT.apk
 *   customize BASE.apk OUT.apk [--package=x.y.z] [--label=Name] [--icon=icon.png]
 *             [--lspatch=lspatch.jar] [--module=mod.apk]... [--sigbypass=0|1|2]
 *             [--debuggable]
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "customize") {
        customize(args.drop(1))
        return
    }
    if (args.size != 3) {
        System.err.println("usage: BASE.apk PATCH.mpatch OUT.apk")
        exitProcess(2)
    }
    val (base, patch, out) = args.map(::File)
    val started = System.currentTimeMillis()
    val about = Patch.about(patch)
    if (Patch.sha256(base) != about.baseSha256) {
        System.err.println("this is not the apk the patch is for")
        exitProcess(1)
    }
    val checked = System.currentTimeMillis()
    Patch.apply(base, patch, out)
    val made = System.currentTimeMillis()
    println("the release apk, byte for byte: %d MB, checked in %.1f s, made in %.1f s".format(
        out.length() / 1_000_000, (checked - started) / 1000.0, (made - checked) / 1000.0))
}

private fun customize(args: List<String>) {
    if (args.size < 2) {
        System.err.println("usage: customize BASE.apk OUT.apk [--package=x.y.z] [--label=Name] " +
            "[--icon=icon.png] [--lspatch=lspatch.jar] [--module=mod.apk]... [--sigbypass=N] [--debuggable]")
        exitProcess(2)
    }
    val base = File(args[0])
    val out = File(args[1])
    var packageName: String? = null
    var label: String? = null
    var icon: ByteArray? = null
    var lspatch: File? = null
    var sigBypass = 2
    var debuggable = false
    val modules = ArrayList<File>()
    for (arg in args.drop(2)) {
        when {
            arg.startsWith("--package=") -> packageName = arg.removePrefix("--package=")
            arg.startsWith("--label=") -> label = arg.removePrefix("--label=")
            arg.startsWith("--icon=") -> icon = File(arg.removePrefix("--icon=")).readBytes()
            arg.startsWith("--lspatch=") -> lspatch = File(arg.removePrefix("--lspatch="))
            arg.startsWith("--module=") -> modules.add(File(arg.removePrefix("--module=")))
            arg.startsWith("--sigbypass=") -> sigBypass = arg.removePrefix("--sigbypass=").toInt()
            arg == "--debuggable" -> debuggable = true
            else -> {
                System.err.println("unknown option: $arg")
                exitProcess(2)
            }
        }
    }
    val options = Customize.Options(
        packageName = packageName, label = label, iconPng = icon,
        lspatchJar = lspatch, sigBypassLevel = sigBypass,
        debuggable = debuggable, modules = modules,
    )
    val started = System.currentTimeMillis()
    Customize.apply(base, out, options, Sign.key())
    println("customized: %d MB in %.1f s".format(
        out.length() / 1_000_000, (System.currentTimeMillis() - started) / 1000.0))
}
