package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 設計文書が書く `AiAvailability.<名前>` が、実在する値だけであることを固定する。
 *
 * **改名のたびに正本が置き去りになったのを2度指摘された。** `features/` と `system/` は
 * 「いま何がどうなっているか」の正本なので、消した値の名前が残っていると、
 * **文書を信じて書いた次の実装が古い契約を再生産する。**
 *
 * **許容リストを持たない。** 歴史的な言及は型名を付けずに書く規約にする
 * （例: 旧 `Available` ／ `Unavailable` を畳んでいた）。`lessons/` と `_wip/` は
 * 経緯と使い捨ての置き場なので対象外。
 */
class DesignDocStateNameTest {

    @Test
    fun `設計文書は実在する AiAvailability の値だけを書く`() {
        val declared = declaredVariants()
        assertTrue("AiAvailability の値を読み取れません", declared.size >= 2)

        val violations = designDocs()
            .flatMap { file ->
                file.readText().lineSequence().mapIndexedNotNull { index, line ->
                    val unknown = REFERENCE_PATTERN.findAll(line)
                        .map { it.groupValues[1] }
                        .filterNot { it in declared }
                        .toList()
                    if (unknown.isEmpty()) {
                        null
                    } else {
                        "${file.name}:${index + 1}: ${unknown.joinToString()} — ${line.trim()}"
                    }
                }
            }
            .sorted()
            .toList()

        assertTrue(
            "実在しない AiAvailability の値を書いています" +
                "（歴史的な言及は型名を付けずに書くこと）:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    private fun declaredVariants(): Set<String> {
        val source = repositoryRoot()
            .resolve("app/src/main/java/com/example/newproject/ai/AICoreClient.kt")
        assertTrue("AICoreClient.kt が見つかりません: $source", source.isFile)
        return DECLARATION_PATTERN.findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun designDocs(): Sequence<File> =
        sequenceOf("docs/dev/features", "docs/dev/system")
            .map(repositoryRoot()::resolve)
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "md" }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve("docs/dev").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }

    private companion object {
        /** `object Ready : AiAvailability()` / `data class TemporarilyUnavailable(...)` を拾う。 */
        val DECLARATION_PATTERN =
            Regex("""(?:object|data class)\s+(\w+)\s*(?:\(|:)\s*[^\n]*AiAvailability""")
        val REFERENCE_PATTERN = Regex("""AiAvailability\.(\w+)""")
    }
}
