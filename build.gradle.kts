import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// 更多说明：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.junit)
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.12.0")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.12.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.12.0")

    // IntelliJ Platform Gradle 插件的依赖扩展：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.5")
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("com.intellij.mcpServer")

        // 在此添加编译所需的插件依赖，例如：
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

// 开发沙箱可使用较新的 IDE，同时保留插件最低平台版本 261。
intellijPlatformTesting.runIde.register("runIdeLatest") {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.2.3"
    plugins { bundledPlugin("com.intellij.mcpServer") }
}

// Gradle 准备下一次运行时，保留通过沙箱 IDE 界面安装的插件。
// 开发中的插件始终由本次构建替换。
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
    useJUnitPlatform {
        if (!providers.gradleProperty("graphBenchmark").isPresent) excludeTags("benchmark")
    }
}
