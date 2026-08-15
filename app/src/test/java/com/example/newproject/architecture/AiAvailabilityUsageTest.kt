package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AiAvailability` を等値比較で扱うことを禁じる。
 *
 * **これは規則ではなく検査である。** `!= AiAvailability.Available` と書かれていた3箇所は、
 * 変種を足しても改名しても**コンパイラに何も言われずに素通り**していた。そのうち1箇所は
 * モデル未取得を「この端末では利用できません」と同じ文言へ畳んでおり、実際に誤っていた。
 *
 * 網羅 `when` に統一してあれば、値が増えたときに全箇所がコンパイルエラーになり、
 * 「新しい状態を見落としたまま動く」ことがなくなる。
 *
 * **許容リストを持たない。** 読書痕跡（意図的に全状態を同じ枝へ落とす場所）も
 * 網羅 `when` で書いてあるので、例外は1つも要らない。
 */
class AiAvailabilityUsageTest {

    @Test
    fun `AiAvailability を等値比較しない`() {
        val violations = scan { line ->
            EQUALITY_PATTERNS.any { it.containsMatchIn(line) }
        }

        assertTrue(
            "AiAvailability は網羅 when で分岐すること（値を足したとき素通りするため）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * **メンバーimportを禁じる。**
     *
     * `import ...AiAvailability.Ready` を書かれると、以降は `x == Ready` と書けてしまい
     * 上の検査を素通りする。**型名を省けなくしておけば、比較は必ず `AiAvailability.` を伴う。**
     * 網羅 `when` の分岐は `AiAvailability.Ready ->` と書けるので、これで困る場面は無い。
     */
    @Test
    fun `AiAvailability のメンバーを import しない`() {
        val violations = scan { line -> MEMBER_IMPORT_PATTERN.containsMatchIn(line) }

        assertTrue(
            "AiAvailability のメンバーimportは等値比較の検査を素通りさせるので禁じる:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun scan(isViolation: (String) -> Boolean): List<String> =
        mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readText().withoutComments().lineSequence()
                    .mapIndexedNotNull { index, line ->
                        if (isViolation(line)) "${file.relativePath()}:${index + 1}: ${line.trim()}" else null
                    }
            }
            .sorted()
            .toList()

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

    /** この検査自身の説明をKDocに書いている型があるので、本文だけを見る。 */
    private fun String.withoutComments(): String =
        BLOCK_COMMENT.replace(this, "").let { LINE_COMMENT.replace(it, "") }

    private companion object {
        /**
         * **左右どちらの側も見る。** 片側だけだと `AiAvailability.Ready == x` で素通りする。
         * 変種名は `Ready` のような短い語なので、型名の側を手がかりにする。
         */
        val EQUALITY_PATTERNS = listOf(
            Regex("""[!=]=\s*AiAvailability\."""),
            Regex("""AiAvailability\.\w+\s*[!=]=""")
        )
        val MEMBER_IMPORT_PATTERN =
            Regex("""^\s*import\s+[\w.]*\bAiAvailability\.\w+""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//.*""")
    }
}
