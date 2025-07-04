import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val libs = versionCatalogs.named("libs")
fun lib(reference: String) = libs.findLibrary(reference).get()
fun properties(key: String) = project.findProperty(key).toString()

plugins {
  id("java")
  id("idea")
  id("org.jetbrains.intellij.platform.module")
  id("org.jetbrains.kotlin.jvm")
}

version = properties("pluginVersion")

repositories {
  mavenCentral()

  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  if (project.name != rootProject.name) {
    intellijPlatform {
      val type = providers.gradleProperty("platformType")
      val version = providers.gradleProperty("platformVersion")
      create(type, version)

      testFramework(TestFrameworkType.Platform)
    }
  }

  implementation(lib("llm.client"))
  constraints {
    implementation(lib("okio")) {
      because("llm-client 0.7.0 uses okio 3.2.0: https://avd.aquasec.com/nvd/cve-2023-3635")
    }
  }

  testImplementation("junit:junit:4.13.2")
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
}
