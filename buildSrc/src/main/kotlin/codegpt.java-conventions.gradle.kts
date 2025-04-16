import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val libs = versionCatalogs.named("libs")
fun lib(reference: String) = libs.findLibrary(reference).get()
fun properties(key: String) = project.findProperty(key).toString()

plugins {
  checkstyle
  id("java")
  id("idea")
  id("org.jetbrains.intellij")
  id("org.jetbrains.kotlin.jvm")
}

version = properties("pluginVersion")

repositories {
  mavenLocal()
  mavenCentral()
}

intellij {
  type.set(properties("platformType"))
  version.set(properties("platformVersion"))
}

checkstyle {
  toolVersion = "10.15.0"
}

dependencies {
  implementation(lib("llm.client"))
  constraints {
    implementation(lib("okio")) {
      because("llm-client 0.7.0 uses okio 3.2.0: https://avd.aquasec.com/nvd/cve-2023-3635")
    }
  }

  testImplementation(platform(lib("junit.bom")))
  testImplementation(lib("assertj.core"))
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks {
  properties("javaVersion").let {
    withType<JavaCompile> {
      sourceCompatibility = it
      targetCompatibility = it
    }
    withType<KotlinCompile> {
      compilerOptions.jvmTarget.set(JvmTarget.fromTarget(it))
    }
  }

  runIde {
    enabled = false
  }

  verifyPlugin {
    enabled = false
  }

  runPluginVerifier {
    enabled = false
  }

  patchPluginXml {
    enabled = false
  }

  publishPlugin {
    enabled = false
  }

  signPlugin {
    enabled = false
  }
}
