import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension

group = "com.trading"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://packages.confluent.io/maven/")
        }
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.wrapper {
    gradleVersion = "9.2.1"
    distributionType = Wrapper.DistributionType.BIN
}

// ---------------------------------------------------------------------------
// dumpSources: concatenate all hand-written source (main + test), build
// scripts and config into a single text document for review / LLM context.
// Build outputs (build/, bin/, out/), the Gradle/IDE/VCS dot-dirs and binaries
// are excluded. Output: build/source-dump/<project>-sources.txt (git-ignored).
// ---------------------------------------------------------------------------
tasks.register("dumpSources") {
    group = "documentation"
    description = "Aggregate all source, tests and build files into one text document (no build outputs/binaries)."

    val rootDirFile = project.rootDir
    val projectName = project.rootProject.name
    val outputFile = layout.buildDirectory.file("source-dump/$projectName-sources.txt")

    // Source files are not declared as inputs, so always regenerate a fresh dump.
    outputs.file(outputFile)
    outputs.upToDateWhen { false }

    val excludedDirs = setOf("build", "bin", "out", "node_modules")
    val includedExtensions = setOf(
        "java", "kt", "kts", "gradle", "properties",
        "yml", "yaml", "xml", "avsc", "sql", "json"
    )

    doLast {
        val out = outputFile.get().asFile
        out.parentFile?.mkdirs()

        val files = rootDirFile.walkTopDown()
            .onEnter { dir -> !dir.name.startsWith(".") && dir.name !in excludedDirs }
            .filter { it.isFile && it.extension.lowercase() in includedExtensions }
            .sortedBy { it.relativeTo(rootDirFile).invariantSeparatorsPath }
            .toList()

        out.bufferedWriter().use { writer ->
            writer.appendLine("================================================================")
            writer.appendLine("  Source dump: $projectName")
            writer.appendLine("  Files:       ${files.size}")
            writer.appendLine("================================================================")
            files.forEach { file ->
                val rel = file.relativeTo(rootDirFile).invariantSeparatorsPath
                val text = file.readText()
                writer.appendLine()
                writer.appendLine("// ==================== $rel ====================")
                writer.appendLine()
                writer.append(text)
                if (!text.endsWith("\n")) writer.appendLine()
            }
        }

        logger.lifecycle(
            "dumpSources: wrote ${files.size} files -> ${out.absolutePath} (${out.length() / 1024} KB)"
        )
    }
}
