import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
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

  implementation(lib("kotlinx.serialization.json"))

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
    java {
      toolchain {
        languageVersion.set(JavaLanguageVersion.of(it.toInt()))
      }
    }
    extensions.configure<KotlinJvmProjectExtension>("kotlin") {
      jvmToolchain(it.toInt())
    }
    withType<JavaCompile> {
      sourceCompatibility = it
      targetCompatibility = it
    }
    withType<KotlinCompile> {
      compilerOptions.jvmTarget.set(JvmTarget.fromTarget(it))
      compilerOptions.freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
  }
}
