import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ 2025.1 ships Kotlin stdlib 2.1: don't call newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    // https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

val fixtureProject = layout.projectDirectory.dir("fixtures/spring-ai-sample")

tasks {
    test {
        // Run the Kotlin plugin in K2 mode inside tests (default mode of the IDE since 2025.1).
        systemProperty("idea.kotlin.plugin.use.k2", "true")
    }

    runIde {
        // Open the sample project directly, with the files that show each inspection (and correct code).
        val projectDir = fixtureProject.asFile.absolutePath
        val sources = "$projectDir/src/main/java/dev/llmlint/sample"
        argumentProviders += CommandLineArgumentProvider {
            listOf(
                projectDir,
                "$sources/SummaryService.java",
                "$projectDir/src/main/kotlin/dev/llmlint/sample/TicketAssistant.kt",
                "$sources/ChatConfig.java",
                "$sources/SessionMemoryFactory.java",
                "$sources/SupportService.java",
            )
        }
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Didea.trust.all.projects=true",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.kotlin.plugin.use.k2=true",
            )
        }
    }
}
