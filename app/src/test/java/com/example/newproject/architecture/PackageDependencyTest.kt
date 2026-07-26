package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDependencyTest {

    /**
     * 値は「その行のパッケージからimportしてよいプロジェクト内パッケージ」。
     * 同一パッケージ内のimportは常に許可し、ここには書かない。
     */
    private val allowedDependencies = mapOf(
        "model" to emptySet(),
        "ai" to setOf("model"),
        "domain" to setOf("model", "ai"),
        "data" to setOf("model", "domain"),
        "controller" to setOf("model", "data", "domain", "ai"),
        "ui" to setOf("model", "domain")
    )

    @Test
    fun `パッケージ依存は許可した方向だけを向く`() {
        val sourceRoot = mainSourceRoot()
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap(::dependencyViolations)
            .sorted()
            .toList()

        assertTrue(
            "許可されていないパッケージ依存があります:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun dependencyViolations(file: File): Sequence<String> {
        val source = file.readText()
        val sourceLayer = PACKAGE_PATTERN.find(source)?.groupValues?.get(1)
            ?: return emptySequence()
        val allowed = allowedDependencies[sourceLayer]
            ?: return sequenceOf("${file.relativePath()}: 未定義のパッケージ $sourceLayer")

        return IMPORT_PATTERN.findAll(source)
            .map { it.groupValues[1] }
            .filter { target -> target != sourceLayer && target !in allowed }
            .map { target -> "${file.relativePath()}: $sourceLayer -> $target" }
            .distinct()
    }

    private fun mainSourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("src/main/java/com/example/newproject"),
            workingDirectory.resolve("app/src/main/java/com/example/newproject")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("main source root が見つかりません: $workingDirectory")
    }

    private fun File.relativePath(): String =
        invariantSeparatorsPath.substringAfter("/src/main/java/")

    companion object {
        private val PACKAGE_PATTERN =
            Regex("""(?m)^package\s+com\.example\.newproject\.([a-z][A-Za-z0-9_]*)\b""")
        private val IMPORT_PATTERN =
            Regex("""(?m)^import\s+com\.example\.newproject\.([a-z][A-Za-z0-9_]*)\b""")
    }
}
