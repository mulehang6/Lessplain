import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "Lessplain"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    // 配置所有项目使用的依赖仓库。
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle 插件的仓库扩展：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
