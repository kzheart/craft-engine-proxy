import net.momirealms.RelocationExtension

plugins {
    id("java")
}

subprojects {

    apply {
        plugin("java")
        plugin("java-library")
        plugin("com.gradleup.shadow")
        plugin("maven-publish")
    }

    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://jitpack.io/")
        maven("https://libraries.minecraft.net/")
        maven("https://repo.momirealms.net/releases/")
        maven("https://repo.gtemc.net/releases/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.create<RelocationExtension>("relocation")

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        dependsOn(tasks.clean)
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(26)
        }
        withSourcesJar()
        disableAutoTargetJvm()
    }
}
