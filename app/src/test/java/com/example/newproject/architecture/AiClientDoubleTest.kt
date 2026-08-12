package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AiClient` のテストダブルを `fakes/` の外に作らせない。
 *
 * **散らばりは能力の欠落として現れた。** 統一前は9ファイルに10個の double があり、
 * その全部が `checkAvailability()` から例外を投げられず、`downloadModel()` が
 * 本物のチャンネルを返すものは1つだけだった。**書けないテストがあることに誰も気づけない**
 * のが散らばりの害なので、置き場所を検査で固定する。
 */
class AiClientDoubleTest {

    @Test
    fun `AiClient のテストダブルは fakes パッケージだけに置く`() {
        val violations = testSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/newproject/fakes/") }
            .filter { OVERRIDE_PATTERN.containsMatchIn(it.readText()) }
            .map { it.relativePath() }
            .sorted()
            .toList()

        assertTrue(
            "AiClient のテストダブルは fakes/FakeAiClient.kt へ寄せること:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun testSourceRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        val candidates = listOf(
            workingDirectory.resolve("src/test/java/com/example/newproject"),
            workingDirectory.resolve("app/src/test/java/com/example/newproject")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("test source root が見つかりません: $workingDirectory")
    }

    private fun File.relativePath(): String =
        invariantSeparatorsPath.substringAfter("/src/test/java/")

    private companion object {
        val OVERRIDE_PATTERN = Regex("""override\s+suspend\s+fun\s+checkAvailability""")
    }
}
