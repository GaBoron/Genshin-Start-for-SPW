import java.util.zip.ZipFile

plugins { java }
group = "io.github.gaboron.spwgenshin"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    // SPW supplies JNA; bundling another copy can lock native libraries on unload.
    compileOnly("net.java.dev.jna:jna-platform:5.18.1")
    testImplementation(files(sourceSets.main.get().compileClasspath))
}

tasks.jar {
    manifest.attributes(mapOf(
        "Plugin-Id" to "spw-genshin-start",
        "Plugin-Class" to "io.github.gaboron.spwgenshin.GenshinPlugin",
        "Plugin-Version" to project.version.toString(),
        "Plugin-Provider" to "GaBoron",
        "Plugin-Name" to "原神，启动！",
        "Plugin-Description" to "播放 HOYO-MiX 时自动启动国服原神，未安装则打开启动器或云原神",
        "Plugin-Has-Config" to "true"
    ))
}

val behaviorCheck = tasks.register<JavaExec>("behaviorCheck") {
    group = "verification"
    description = "Verify playback gating, mainland routing and read-only local discovery."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.gaboron.spwgenshin.BehaviorChecks")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8")
}
// The checks use an executable harness rather than a test framework.
tasks.test { enabled = false }
tasks.check { dependsOn(behaviorCheck) }

tasks.register<Zip>("plugin") {
    dependsOn(tasks.jar, tasks.check)
    archiveFileName.set("spw-genshin-start-${project.version}.spmod")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    into("classes") { from(tasks.jar.map { zipTree(it.archiveFile) }) }
    from("README.md", "LICENSE", "THIRD_PARTY_NOTICES.md")
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            check("classes/META-INF/MANIFEST.MF" in names)
            check("classes/META-INF/extensions.idx" in names)
            check("classes/preference_config.json" in names)
            check("classes/audio/genshin-start.wav" in names)
            check("classes/io/github/gaboron/spwgenshin/GenshinPlugin.class" in names)
            check(names.none { it.startsWith("lib/") || it.endsWith(".dll") || it.endsWith(".exe") })
        }
    }
}
