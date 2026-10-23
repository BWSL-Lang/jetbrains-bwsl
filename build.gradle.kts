import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import java.util.Properties

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.19.0"
}

// The IDE runs on Java 25, so the plugin is compiled, tested and run on it.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

group = "com.bwsl.jetbrains_plugin"
version = providers.gradleProperty("pluginVersion").get()

// Local, gitignored overrides for gradle.properties (e.g. bwslc=C:/path/to/bwslc.exe)
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    val localProperties = Properties()
    localPropertiesFile.inputStream().use { localProperties.load(it) }
    localProperties.forEach { key, value -> extra.set(key as String, value) }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2")
        bundledPlugin("intellij.libraries.misc.plugin")
        bundledModule("intellij.spellchecker")
        // The typo inspection lives in the Natural Languages plugin (bundled in the IDEs); only the tests need it.
        testBundledPlugin("tanvd.grazi")
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testCompileOnly("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
        changeNotes = "Initial version"
    }
    // The Plugin Verifier checks the plugin against the IDEs its build range covers. It takes minutes, so `check` does not
    // run it: only CI does (`check verifyPlugin`). Its default failure levels (compatibility problems, internal and
    // override-only API) plus the deprecated API, which it does not fail on by default, and a plugin that is invalid, has a
    // missing dependency or a descriptor warning.
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            // The rest, which do not fail it:
            // VerifyPluginTask.FailureLevel.COMPATIBILITY_WARNINGS,
            // VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES,
            // VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            // VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
        )
    }
    publishing {
        channels = listOf(providers.gradleProperty("pluginChannels").getOrElse("alpha"))
        // token can be given in two ways:
        // export ORG_GRADLE_PROJECT_intellijPlatformPublishingToken='YOUR_TOKEN'
        // gradle flag -PintellijPlatformPublishingToken=YOUR_TOKEN
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

tasks.generateLexer {
    sourceFile = file("src/main/resources/BwslLexer.flex")
    // The lexer is written under its package, generated/com/bwsl/plugin/_BwslLexer.java.
    targetRootOutputDir = layout.projectDirectory.dir("generated")
    purgeOldFiles = true
}

sourceSets {
    main {
        java {
            srcDir("generated")
        }
    }
}

// A Kotlin warning (a deprecated API above all) fails the build: nothing reads the compiler's warnings in a green run.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        // Kotlin otherwise copies the default methods of the platform's interfaces into every class that implements one, and
        // the Plugin Verifier then reports the deprecated ones among them as overridden.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(tasks.generateLexer)
}

tasks.compileKotlin {
    dependsOn(tasks.generateLexer)
}

val bwslcFileName = "bwslc" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

// The compiler the tests run against: the newest dev build of the compiler's master branch, which
// the compiler repository publishes at stable URLs. -PbwslcChannel=release takes the latest release
// instead; -Pbwslc=<path> uses a local compiler and skips the download.
val fetchBwslCompiler = tasks.register<JavaExec>("fetchBwslCompiler") {
    val outputFile = layout.buildDirectory.dir("bwslc").get().asFile.resolve(bwslcFileName)

    dependsOn("classes")
    outputs.file(outputFile)
    onlyIf { !outputFile.exists() }

    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass = "com.bwsl.plugin.BwslCompilerDownloaderKt"
    args = listOf(outputFile.absolutePath, (findProperty("bwslcChannel") ?: "dev").toString())
}

tasks.test {
    val overridePath = findProperty("bwslc")
    if (overridePath != null) {
        systemProperty("bwslc.path", overridePath)
    } else {
        dependsOn(fetchBwslCompiler)
        systemProperty("bwslc.path", layout.buildDirectory.dir("bwslc").get().asFile.resolve(bwslcFileName).absolutePath)
    }
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
    // The bundled IDE platform looks for a test-log.properties file inside its own extracted
    // transforms dir and prints a startup warning when it's missing. That dir lives in Gradle's
    // immutable transform cache, so it must not be written to directly (newer Gradle versions
    // detect the tampering and refuse to reuse the cache entry, breaking subsequent builds).
    // idea.log.config.file lets us point the platform at our copy instead.
    systemProperty("idea.log.config.file", layout.projectDirectory.file("src/test/resources/test-log.properties").asFile.absolutePath)
}

tasks.buildSearchableOptions {
    enabled = false
}
