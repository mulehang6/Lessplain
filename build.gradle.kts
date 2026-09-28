import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.junit)
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.5")
        testFramework(TestFrameworkType.Platform)
        composeUI()
        bundledPlugin("com.intellij.mcpServer")

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
    pluginVerification {
        ides {
            create("IU", "2026.1.5")
            create("IU", "2026.2.3")
        }
    }
}

// The development sandbox can use a newer IDE without raising the plugin's 261 minimum.
intellijPlatformTesting.runIde.register("runIdeLatest") {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.2.3"
    plugins { bundledPlugin("com.intellij.mcpServer") }
}

// Keep plugins installed from the sandbox IDE UI when Gradle prepares the next run.
// The plugin under development is always replaced with the current build.
tasks.withType<org.gradle.api.tasks.Sync>().configureEach {
    if (name == "prepareSandbox_runIde" || name == "prepareSandbox_runIdeLatest") {
        preserve {
            include("**/*")
            exclude("${project.name}/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
}
