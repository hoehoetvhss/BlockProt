buildscript {
    repositories {
        maven("https://plugins.gradle.org/m2/")
    }
}

plugins {
    id("maven-publish")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val nbtApiVersion = project.property("nbtApiVersion").toString()
val anvilGuiVersion = project.property("anvilGuiVersion").toString()
val townyVersion = project.property("townyVersion").toString()
val papiVersion = project.property("papiVersion").toString()
val worldGuardVersion = project.property("worldGuardVersion").toString()

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "Spigot"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.org/repository/maven-public/") {
        name = "CodeMC"
        content {
            includeGroup("de.tr7zw")
        }
    }
    maven("https://mvn.wesjd.net/") {
        name = "WesJD"
        content {
            includeGroup("net.wesjd")
        }
    }
}

dependencies {
    implementation(project(":common"))

    // Spigot
    compileOnly("org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT")
    compileOnly("org.apache.commons:commons-lang3:3.22.0")

    // bStats
    api("org.bstats:bstats-bukkit:3.2.1")

    // Dependencies
    implementation("de.tr7zw:item-nbt-api:$nbtApiVersion")
    implementation("net.wesjd:anvilgui:$anvilGuiVersion") // Allows us to use anvils as inventories without using NMS.
    implementation("org.enginehub:squirrelid:0.3.2")

    // Integrations
    compileOnly("net.kyori:adventure-api:4.17.0")
    implementation("com.github.LlmDl:Towny:$townyVersion")
    implementation("me.clip:placeholderapi:$papiVersion")
    implementation("com.sk89q.worldguard:worldguard-bukkit:$worldGuardVersion")
    implementation("com.github.IncrediblePlugins:LandsAPI:7.25.4")
}

val generatedTranslationsDir = layout.buildDirectory.dir("generated/sources/translations/java")

val generateTranslationFiles = tasks.register("generateTranslationFiles") {
    val langDir = layout.projectDirectory.dir("src/main/resources/lang")
    inputs.dir(langDir)
    outputs.dir(generatedTranslationsDir)

    doLast {
        val translationFiles = langDir.asFileTree.files
            .filter { it.name.matches("translations_[a-z].+\\.yml".toRegex()) }
            .map { it.name }
            .sorted()
            .toString()

        val outputFile = generatedTranslationsDir.get()
            .file("de/sean/blockprot/bukkit/GeneratedTranslationFiles.java")
            .asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package de.sean.blockprot.bukkit;

            final class GeneratedTranslationFiles {
                static final String TRANSLATION_FILES = "${translationFiles.replace("\"", "\\\"")}";
                private GeneratedTranslationFiles() {}
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets {
    main {
        java {
            srcDir(generateTranslationFiles)
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.javadoc {
    options {
        source = "17"
        encoding = "UTF-8"
        memberLevel = JavadocMemberLevel.PACKAGE
        (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    this.isFailOnError = false
}

tasks.shadowJar {
    relocate("de.tr7zw.changeme.nbtapi", "de.sean.blockprot.bukkit.shaded.nbtapi")
    relocate("net.wesjd.anvilgui", "de.sean.blockprot.bukkit.shaded.anvilgui")
    relocate("org.bstats", "de.sean.blockprot.bukkit.metrics")
    relocate("org.enginehub.squirrelid", "de.sean.blockprot.bukkit.squirrelid")
    // minimize()

    dependencies {
        this.include(project(":common"))
        this.include(dependency("org.jetbrains:annotations"))
        this.include(dependency("de.tr7zw:item-nbt-api"))
        this.include(dependency("net.wesjd:anvilgui"))
        this.include(dependency("org.bstats:bstats-base"))
        this.include(dependency("org.bstats:bstats-bukkit"))
        this.include(dependency("org.enginehub:squirrelid"))
    }

    archiveClassifier.set(
        if (ext["gitBranchName"] == "master" || ext["gitBranchName"] == "HEAD") "all"
        else "${ext["gitBranchName"]}-all")
    // archiveFileName.set("${base.archivesName.get()}-${archiveClassifier.get()}.jar")
}

tasks.build {
    dependsOn(tasks["javadocJar"])
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    downloadPlugins {
        url("https://download.luckperms.net/1561/bukkit/loader/LuckPerms-Bukkit-5.4.146.jar")
    }
    minecraftVersion("26.3")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group as String
            artifactId = project.name
            version = project.version as String

            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
