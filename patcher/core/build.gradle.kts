plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

// What runs the engine on a computer, to check a patch before it is handed out.
val cli: Configuration by configurations.creating

dependencies {
    // the two libraries a patch is read with; both plain Java, so the same
    // code runs on a phone and on the computer that checks the patch
    api("org.tukaani:xz:1.10")
    api("com.jcraft:jzlib:1.1.3")
    // Android has this one built in
    compileOnly("org.json:json:20240303")
    cli("org.json:json:20240303")
}

tasks.register<JavaExec>("patch") {
    description = "Apply a patch on this computer: -Pbase=… -Ppatch=… -Pout=…"
    mainClass.set("cat.narezany.rubp.core.CliKt")
    classpath = sourceSets["main"].runtimeClasspath + cli
    jvmArgs("-Xmx1g")
    args(listOfNotNull(
        project.findProperty("base") as String?,
        project.findProperty("patch") as String?,
        project.findProperty("out") as String?,
    ))
}

tasks.register<JavaExec>("customize") {
    description = "Rename, relabel, re-icon and lspatch an apk on this computer: " +
        "-Pbase=… -Pout=… [-Ppackage=…] [-Plabel=…] [-Picon=…] [-Plspatch=…] [-Pmodule=a.apk,b.apk] [-Psigbypass=2]"
    mainClass.set("cat.narezany.rubp.core.CliKt")
    classpath = sourceSets["main"].runtimeClasspath + cli
    jvmArgs("-Xmx2g")
    args(buildList {
        add("customize")
        add(project.findProperty("base") as String? ?: "")
        add(project.findProperty("out") as String? ?: "")
        (project.findProperty("package") as String?)?.let { add("--package=$it") }
        (project.findProperty("label") as String?)?.let { add("--label=$it") }
        (project.findProperty("icon") as String?)?.let { add("--icon=$it") }
        (project.findProperty("lspatch") as String?)?.let { add("--lspatch=$it") }
        (project.findProperty("module") as String?)?.split(',')?.forEach { add("--module=$it") }
        (project.findProperty("sigbypass") as String?)?.let { add("--sigbypass=$it") }
    })
}
