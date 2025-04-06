import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.tasks.RunIdeTask
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
  alias(libs.plugins.changelog)
  alias(libs.plugins.protobuf)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get() + "-" + properties("pluginSinceBuild").get()

checkstyle {
  toolVersion = libs.versions.checkstyle.get()
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

intellij {
  pluginName.set(properties("pluginName"))
  version.set(properties("platformVersion"))
  type.set(properties("platformType"))
  plugins.set(listOf("java", "PythonCore:223.8617.56", "Git4Idea", "org.jetbrains.kotlin"))
}

changelog {
  groups.empty()
  repositoryUrl.set(properties("pluginRepositoryUrl"))
}

dependencies {
  implementation(project(":codegpt-telemetry"))
  implementation(project(":codegpt-treesitter"))

  implementation("com.google.protobuf:protobuf-java:3.21.7")
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
  testImplementation(kotlin("test"))
}

tasks.register<Exec>("updateSubmodules") {
  workingDir(rootDir)
  commandLine("git", "submodule", "update", "--init", "--recursive")
}

/**
 * Task to run a custom IntelliJ IDEA sandbox.
 *
 * This task launches a custom IntelliJ IDEA installation using the path specified in the
 * 'customIdePath' property from local.properties.
 *
 * IMPORTANT:
 * - On macOS, the path must include the 'Contents' directory (e.g., /Applications/IntelliJ IDEA.app/Contents).
 * - For Windows or Linux, specify the appropriate path to the IntelliJ IDEA installation.
 *
 * Usage:
 *   ./gradlew runCustomIde
 */
if (customIdePath != null) {
    tasks.register<RunIdeTask>("runCustomIde") {
        group = "intellij"
        description = "Start custom idea sandbox"
        ideDir.set(file(customIdePath))
        environment("ENVIRONMENT", "LOCAL")
        autoReloadPlugins.set(false)
    }
}

tasks {
  wrapper {
    gradleVersion = properties("gradleVersion").get()
  }

  verifyPlugin {
    enabled = true
  }

  runPluginVerifier {
    enabled = true
  }

  patchPluginXml {
    enabled = true
    version.set(properties("pluginVersion").get() + "-" + properties("pluginSinceBuild").get())
    sinceBuild.set(properties("pluginSinceBuild"))
    untilBuild.set(properties("pluginUntilBuild"))

    pluginDescription.set(providers.fileContents(layout.projectDirectory.file("DESCRIPTION.md")).asText.map {
      val start = "<!-- Plugin description -->"
      val end = "<!-- Plugin description end -->"

      with(it.lines()) {
        if (!containsAll(listOf(start, end))) {
          throw GradleException("Plugin description section not found in DESCRIPTION.md:\n$start ... $end")
        }
        subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
      }
    })

    val changelog = project.changelog // local variable for configuration cache compatibility
    // Get the latest available change notes from the changelog file
    changeNotes.set(properties("pluginVersion").map { pluginVersion ->
      with(changelog) {
        renderItem(
          (getOrNull(pluginVersion) ?: getUnreleased())
            .withHeader(false)
            .withEmptySections(false),
          Changelog.OutputType.HTML,
        )
      }
    })
  }

  prepareSandbox {
    enabled = true
    dependsOn("updateSubmodules")
    from("src/main/cpp/llama.cpp") {
      into("ProxyAI/llama.cpp")
    }
  }

  signPlugin {
    enabled = true
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  buildPlugin {
    enabled = true
  }

  publishPlugin {
    enabled = true
    dependsOn("patchChangelog")
    token.set(System.getenv("PUBLISH_TOKEN"))
    channels.set(listOf("stable"))
  }

  runIde {
    enabled = true
    environment("ENVIRONMENT", "LOCAL")
    autoReloadPlugins.set(false) // is triggered when building llama server
  }

  test {
    exclude("**/testsupport/*")
    useJUnitPlatform()
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