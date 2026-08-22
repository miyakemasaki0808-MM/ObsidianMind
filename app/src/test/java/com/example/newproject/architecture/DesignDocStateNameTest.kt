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
 * 2. **裸の名前** — 文書は `` `Available` `` とも「Availableは即生成」とも書くので、
 *    1だけでは素通りする（実際にそれで7箇所を見逃した）。
 *    消した名前は [RETIRED_NAMES] へ足し、**バッククォートの有無に関わらず落とす。**
 *
 * **歴史的な言及は直前に「旧 」を置く**（例: 旧 `Available` を畳んでいた）。
 * これが唯一の逃げ道で、**ファイル単位の許容リストは持たない** — 例外が増えるほど
 * 検査の力は落ちる（→ [lessons L29](../../../../../../../../docs/dev/lessons.md)）。
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
        val declared = declaredStateNames()
        // **どの型にも無い名前だけを禁じる。** 例えば `Unavailable` は `AiAvailability` からは
        // 消えたが `DistillState` には現存するので、名前だけでは古さを決められない。
        val retired = RETIRED_NAMES.filterNot { it in declared }
        assertTrue("RETIRED_NAMES が全て現存しています。一覧が古い可能性があります", retired.isNotEmpty())

        val violations = scanDocs { line ->
            // **バッククォートの有無で見逃さない。** 文書は `Available` とも
            // 「Availableは即生成」とも書く。実際に後者を素通りさせた。
            val plain = line.replace("`", "")
            retired.filter { name ->
                Regex("""(?<!旧 )(?<![A-Za-z])${Regex.escape(name)}(?![A-Za-z])""")
                    .containsMatchIn(plain)
            }
        }

        assertTrue(
            "消した名前が現役として残っています" +
                "（歴史的な言及なら「旧 名前」と書くこと）:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * **意味上の旧契約を、限定的な文言リストで止める。**
     *
     * 名前ベースの検査では拾えない — 使われている識別子はどれも現存するので、
     * 「`Unusable` を非対応の意味で書く」「`DL中は合流する` と書く」は素通りする。
     * **汎用化はできない**ので、**一度誤って直した主張だけ**を並べる。
     *
     * 対象はKDoc・テスト名も含む（正本だけでなく**コード内の説明**も次の実装者が読む）。
     */
    @Test
    fun `一度直した旧い主張を書き戻さない`() {
        val violations = scanAll { line ->
            RETIRED_CLAIMS.filter { claim ->
                line.contains(claim) && !line.contains("旧")
            }
        }

        assertTrue(
            "一度誤りとして直した主張が戻っています" +
                "（歴史として書くなら同じ行に「旧」を含めること）:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** 正本の文書に加えて、KDoc・テスト名も見る。 */
    private fun scanAll(findViolations: (String) -> List<String>): List<String> =
        (designDocs() + kotlinSources())
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

    private fun kotlinSources(): Sequence<File> =
        sequenceOf("app/src/main/java", "app/src/test/java", "app/src/androidTest/java")
            .map(repositoryRoot()::resolve)
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            // この検査自身が禁止語を持つので、対象から外す。
            .filterNot { it.name == "DesignDocStateNameTest.kt" }

    /**
     * `model/state/` と `ai/` が今も宣言している型・値の名前。
     *
     * **これで [RETIRED_NAMES] から誤検出を自動的に外す。** 一覧へ足した名前が
     * 別の型に現存していれば、その名前は禁じない。
     */
    private fun declaredStateNames(): Set<String> {
        val roots = listOf(
            "app/src/main/java/com/example/newproject/model/state",
            "app/src/main/java/com/example/newproject/ai"
        ).map(repositoryRoot()::resolve).filter(File::isDirectory)
        assertTrue("状態の定義が見つかりません", roots.isNotEmpty())
        return roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { STATE_NAME_PATTERN.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()
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
     * 対象は**判断の正本（`dev/`）だけ**。
     *
     * `lessons/` は経緯の置き場、`_wip/` は使い捨てなので入れない
     * （どちらも「昔こう書いた」を残すことに意味がある）。
     *
     * **`owner/` も入れない。** あちらはオーナーが読むための俯瞰で、
     * **更新はオーナーが指示したときだけ**と決まっている（→ `docs/owner/README.md`）。
     * 検査に載せると、古びてよいと決めた文書が無関係な変更を止めることになる。
     * 改名が俯瞰へ届いていないことは、次の通し見直しで直る。
     */
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

        /** 状態の型・値・プロパティの名前。誤検出を外すためだけに使うので広めに拾う。 */
        val STATE_NAME_PATTERN =
            Regex("""(?:object|class|enum class|data class|val|var)\s+(\w+)""")
        val QUALIFIED_PATTERN = Regex("""AiAvailability\.(\w+)""")

        /**
         * 消した型・値・フィールドの名前。**現役のように書いたら落とす。**
         *
         * **現存する名前を挙げても構わない** — [declaredStateNames] が自動で外す。
         * `Unavailable` がその例で、`AiAvailability` からは消えたが `DistillState` には残る。
         *
         * **限界:** 名前が別の型に現存すると禁じられないので、
         * 「`DistillState` の一覧に消えた `NeedsDownload` が残っている」ような
         * **型ごとの古さは機械では見つけられない**。そこは読んで直すしかない。
         */
        /**
         * **一度誤りとして直した主張。** 書き戻したら落とす。
         *
         * どれも「識別子は現存するのに意味が逆」で、名前ベースの検査を通過した実例。
         * 歴史として触れるなら同じ行に「旧」を含める。
         */
        val RETIRED_CLAIMS = listOf(
            // ひとことの結果は専用画面にある。読書画面へ1文は出ない。
            "読書画面へ直接1文",
            // 走行中のDLへは合流できない（AARに状態の門番が無い）。
            "DL中は合流するだけ",
            // PickerResult.Error は生成例外から実際に構築される。
            "到達不能な variant が2件"
        )

        val RETIRED_NAMES = listOf(
            "Available",
            "Unavailable",
            "CheckFailed",
            "AiRecommendationStatus",
            "aiStatus",
            "aiErrorMessage",
            "aiNotice",
            "answerError"
        )
    }
}
