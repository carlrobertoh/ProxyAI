import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.io.FileInputStream
import java.util.*

val localPropertiesFile = file("local.properties")
val env = environment("env").getOrNull()

fun loadProperties(filename: String): Properties = Properties().apply {
    load(FileInputStream(filename))
}

val localProperties: Properties? = if (localPropertiesFile.exists()) {
    loadProperties("local.properties")
} else {
    null
}

val customIdePath: String? = localProperties?.getProperty("customIdePath")

fun properties(key: String): Provider<String> {
    if ("win-arm64" == env) {
        val property = loadProperties("gradle-win-arm64.properties").getProperty(key)
            ?: return providers.gradleProperty(key)
        return providers.provider { property }
    }
    return providers.gradleProperty(key)
}

fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("codegpt.java-conventions")
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.changelog)
    alias(libs.plugins.protobuf)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get() + "-" + properties("pluginSinceBuild").get()

repositories {
    mavenCentral()
    gradlePluginPortal()

    intellijPlatform {
        defaultRepositories()
    }
}

changelog {
    groups.empty()
    repositoryUrl.set(properties("pluginRepositoryUrl"))
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(properties("platformVersion"))

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")
        plugin("PythonCore:241.14494.240")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation(project(":codegpt-telemetry"))
    implementation(project(":codegpt-treesitter"))

    implementation(platform(libs.jackson.bom))
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(libs.flexmark.all) {
        // vulnerable transitive dependency
        exclude(group = "org.jsoup", module = "jsoup")
    }
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(libs.jsoup)
    implementation(libs.commons.text)
    implementation(libs.jtokkit)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.register<Exec>("updateSubmodules") {
    workingDir(rootDir)
    commandLine("git", "submodule", "update", "--init", "--recursive")
}

intellijPlatform {
    pluginConfiguration {
        name = properties("pluginName")
        version = properties("pluginVersion").get() + "-" + properties("pluginSinceBuild").get()

        description =
            providers.fileContents(layout.projectDirectory.file("DESCRIPTION.md")).asText.map {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                with(it.lines()) {
                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("Plugin description section not found in DESCRIPTION.md:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
                        .let(::markdownToHTML)
                }
            }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = properties("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = System.getenv("PUBLISH_TOKEN")
        channels = listOf("stable")
    }
}

/**
- * Task to run a custom IntelliJ IDEA sandbox.
- *
- * This task launches a custom IntelliJ IDEA installation using the path specified in the
- * 'customIdePath' property from local.properties.
- *
- * IMPORTANT:
- * - On macOS, the path must include the 'Contents' directory (e.g., /Applications/IntelliJ IDEA.app/Contents).
- * - For Windows or Linux, specify the appropriate path to the IntelliJ IDEA installation.
- *
- * Usage:
- *   ./gradlew runCustomIde
- */
if (customIdePath != null) {
    tasks.register<RunIdeTask>("runCustomIde") {
        group = "intellij"
        description = "Start custom idea sandbox"
        sandboxDirectory = file(customIdePath)
        environment("ENVIRONMENT", "LOCAL")
    }
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    prepareSandbox {
        dependsOn("updateSubmodules")
        from(layout.projectDirectory.dir("src/main/cpp/llama.cpp")) {
            into("CodeGPT/llama.cpp")
        }
    }

    runIde {
        environment("ENVIRONMENT", "LOCAL")
    }

    buildPlugin {
        dependsOn("prepareSandbox")
    }

    publishPlugin {
        dependsOn("patchChangelog")
    }

    test {
        exclude("**/testsupport/*")
        testLogging {
            events("started", "passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.protobuf.java.get().toString()
        }
    }
    generateProtoTasks {
        all()
            .forEach {
                it.plugins {
                    create("grpc")
                }
            }
    }
}