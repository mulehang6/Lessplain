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
    implementation("com.networknt:json-schema-validator:1.5.9") {
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }

    // IntelliJ Platform Gradle 插件的依赖扩展：https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.5")
        testFramework(TestFrameworkType.Platform)
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

// 插件构建包含本地静态资源，不依赖开发服务器或用户安装 Node。
val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir("webview")
    commandLine("cmd", "/c", "pnpm", "install", "--frozen-lockfile")
    inputs.files("webview/package.json", "webview/pnpm-lock.yaml", "webview/pnpm-workspace.yaml")
    outputs.dir("webview/node_modules")
}
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir("webview")
    commandLine("cmd", "/c", "pnpm", "build")
    inputs.dir("webview/src")
    inputs.dir("webview/scripts")
    inputs.files("webview/package.json", "webview/pnpm-lock.yaml", "webview/vite.config.ts", "webview/tsconfig.json", "webview/index.html")
    outputs.dir("webview/dist")
}
val frontendResources = tasks.register<Sync>("frontendResources") {
    dependsOn(frontendBuild)
    from("webview/dist")
    into(layout.buildDirectory.dir("generated-resources/webview"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated-resources")) }
tasks.processResources { dependsOn(frontendResources) }

// 只影响开发运行实例；原生 JCEF 日志必须在初始化前启用，随沙盒日志保存。
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
    systemProperty("ide.browser.jcef.log.level", "info")
}

// 默认构造函数或序列化 DTO 的 ABI 改变时，不能恢复旧主类对应的测试缓存。
val compiledMain = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory }
tasks.named("compileTestKotlin") {
    inputs.dir(compiledMain).withPropertyName("lessplainMainBytecode").withPathSensitivity(PathSensitivity.RELATIVE)
}
