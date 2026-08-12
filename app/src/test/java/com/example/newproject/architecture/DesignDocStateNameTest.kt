package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正本の文書が、消した型・値の名前を**現役のもののように**書いていないことを固定する。
 *
 * **改名のたびに正本が置き去りになったのを3度指摘された。** コードの改名はコンパイラが
 * 全箇所を挙げてくれるが、**文書には何も起きない**。文書を信じて書いた次の実装が
 * 古い契約を再生産するので、ここが唯一の歯止めになる。
 *
 * 検査は2本立て。
 *
 * 1. **修飾された参照** — `AiAvailability.<名前>` は実在する値だけ。
 *    実装から値名を読むので、名前を足しても検査側の更新は要らない。
 * 2. **裸の名前** — 文書は `` `Available` `` のように型名を付けずに書くことが多く、
 *    1だけでは素通りする（実際にそれで5箇所を見逃した）。
 *    消した名前は [RETIRED_NAMES] へ足し、**現役のように書いたら落とす。**
 *
 * **歴史的な言及は「旧」を前に置く**（例: 旧 `Available` を畳んでいた）。
 * これが唯一の逃げ道で、**ファイル単位の許容リストは持たない** — 例外が増えるほど
 * 検査の力は落ちる（→ [lessons L29](../../../../../../../docs/dev/lessons.md)）。
 *
 * **型や値を消したら [RETIRED_NAMES] へ足す。** 足し忘れは検査できないが、
 * 足しさえすれば以後は自動で守られる。
 */
class DesignDocStateNameTest {

    @Test
    fun `設計文書は実在する AiAvailability の値だけを書く`() {
        val declared = declaredVariants()
        assertTrue("AiAvailability の値を読み取れません", declared.size >= 2)

        val violations = scanDocs { line ->
            QUALIFIED_PATTERN.findAll(line)
                .map { it.groupValues[1] }
                .filterNot { it in declared }
                .toList()
        }

        assertTrue(
            "実在しない AiAvailability の値を書いています" +
                "（歴史的な言及は「旧」を前に置き、型名を付けずに書くこと）:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `設計文書は消した名前を現役のように書かない`() {
        val violations = scanDocs { line ->
            RETIRED_NAMES.filter { name ->
                Regex("""(?<!旧 )(?<!旧)`${Regex.escape(name)}`""").containsMatchIn(line)
            }
        }

        assertTrue(
            "消した名前が現役として残っています" +
                "（歴史的な言及なら「旧 `名前`」と書くこと）:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** 各行を [findViolations] にかけ、見つかった名前を「場所: 名前 — 行」へ整える。 */
    private fun scanDocs(findViolations: (String) -> List<String>): List<String> =
        designDocs()
            .flatMap { file ->
                file.readText().lineSequence().mapIndexedNotNull { index, line ->
                    val found = findViolations(line)
                    if (found.isEmpty()) {
                        null
                    } else {
                        "${file.name}:${index + 1}: ${found.joinToString()} — ${line.trim()}"
                    }
                }
            }
            .sorted()
            .toList()

    private fun declaredVariants(): Set<String> {
        val source = repositoryRoot()
            .resolve("app/src/main/java/com/example/newproject/ai/AICoreClient.kt")
        assertTrue("AICoreClient.kt が見つかりません: $source", source.isFile)
        return DECLARATION_PATTERN.findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * 対象は**正本だけ**。
     *
     * `lessons/` は経緯の置き場、`_wip/` は使い捨てなので入れない
     * （どちらも「昔こう書いた」を残すことに意味がある）。
     * **`owner/` を入れるのは、技術俯瞰も実装の正本として読まれるため。**
     */
    private fun designDocs(): Sequence<File> =
        sequenceOf("docs/dev/features", "docs/dev/system", "docs/owner")
            .map(repositoryRoot()::resolve)
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "md" }
            // 開発日誌はその日の記録なので、当時の名前のままでよい。
            .filterNot { it.invariantSeparatorsPath.contains("/owner/journal/") }

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
        val QUALIFIED_PATTERN = Regex("""AiAvailability\.(\w+)""")

        /**
         * 消した型・値・フィールドの名前。**現役のように書いたら落とす。**
         *
         * `Unavailable` を挙げても現役の `AiUnavailable` は誤検出しない
         * （バッククォートで囲まれた**完全一致**だけを見るため）。
         */
        val RETIRED_NAMES = listOf(
            "Available",
            "Unavailable",
            "CheckFailed",
            "AiRecommendationStatus",
            "aiStatus",
            "aiErrorMessage"
        )
    }
}
