package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **コードと正本のあいだの「機械で確かめられる部分」を固定する。**
 *
 * 影響面監査は「状態欄を足したら正本も直す」を求めるが、**守ったかどうかの証拠が無かった。**
 * 実際に `isSuggestionsLoading` を足した回で、正本の状態一覧が旧7欄のまま残った。
 * さらに**その回に直したKDocのリンク自身が壊れていた**（`..` が2階層足りず、
 * 存在しない `app/src/docs/` を指していた）ため、判断の正本へ辿れなかった。
 *
 * ここが見るのは2つだけ。
 *
 * 1. **状態型のフィールドが正本の「一覧」に載っている** — 意味の正しさは見ない（見られない）が、
 *    **欄が抜けていること**は確実に分かる。
 *    **文書全体の単語検索では足りない** — 一覧から欄を落としても、直後の設計説明に
 *    同じ名前が1つ出てくるだけで満たされてしまい、実際にその変異を緑で通した。
 *    範囲は `<!-- state-fields: 型名 -->` … `<!-- /state-fields -->` で区切る
 * 2. **KDocの相対リンクが実在するファイルを指す** — リンク切れは読み手が根拠へ辿れない
 */
class SourceDocSyncTest {

    /**
     * 「型 → 正本」の対応。**明示的に持つ**（自動で探すと、文書が無い型を黙って素通りさせる）。
     *
     * **対象は平らな `data class` の状態型だけ。** `QuizState` / `RemarkState` のような
     * sealed 型は欄が variant ごとに散るので、「フィールド一覧」という形が当てはまらない。
     * そちらの同期は `DesignDocStateNameTest`（消した名前）と読み手が受け持つ。
     */
    private val stateDocs = mapOf(
        StateType("model/state/SectionChatState.kt", "SectionChatState") to
            "features/section_ai_chat.md",
        // **欄を足したら、その欄を読む正本が全部追いつく必要がある。**
        // `ReadingTrace` は v6 で4欄増えたが、退避・復元の突き合わせ表（reading_trace_backup §5）は
        // 別文書・別タイミングで直すため、欄の登録漏れが最も起きやすい型だった。
        StateType("model/ReadingTrace.kt", "ReadingTrace") to
            "features/reflect_reading_trace.md"
    )

    private data class StateType(val path: String, val className: String)

    @Test
    fun `状態型のフィールドは正本の一覧に載っている`() {
        val violations = stateDocs.flatMap { (type, docPath) ->
            val source = sourceRoot().resolve(type.path)
            val doc = docsRoot().resolve("dev/$docPath")
            assertTrue("状態の定義が見つかりません: $source", source.isFile)
            assertTrue("正本が見つかりません: $doc", doc.isFile)

            val listed = stateFieldRegion(doc.readText(), type.className, docPath)
            primaryConstructorFields(source.readText(), type.className)
                .filterNot { listed.contains("`$it`") }
                .map { "$docPath: `$it` が状態一覧に無い（${source.name} は持っている）" }
        }.sorted()

        assertTrue(
            "状態欄を足したら正本の一覧も直すこと:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * KDocに書いた相対リンクが実在すること。
     *
     * **深さを数え間違えても何も起きない**ので、壊れたまま残る。実際に残した。
     *
     * **2026-08-22、対象を `main` だけにしていたことが表に出た。** instrumentation の KDoc へ
     * 正本リンクを足したとき `..` を1階層数え違えたが、**検査の走査範囲の外だったので何も落ちなかった。**
     * リンクが壊れる条件（深さを数える）はソースセットで変わらないのに、
     * **数える対象だけが `main` に限られていた** — [lessons L14](../../../../../../../../docs/dev/lessons.md) の型。
     * `test` と `androidTest` も同じ規則で数える。
     */
    @Test
    fun `KDocの相対リンクは実在するファイルを指す`() {
        val violations = kotlinSources()
            .flatMap { file ->
                // **バッククォート内は例示であって、リンクではない。**
                // 書式そのものを説明したKDoc（この検査自身が持っている）を壊れたリンクと数えない。
                RELATIVE_LINK.findAll(file.readText().withoutInlineCode())
                    .map { it.groupValues[1] }
                    .filterNot { File(file.parentFile, it).canonicalFile.exists() }
                    .map { "${file.name}: $it が解決できない" }
            }
            .sorted()
            .toList()

        assertTrue(
            "KDocの相対リンクが壊れています:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * **文書からソースを指すときは、行番号ではなく名前で指す。**
     *
     * 2026-08-30 に棚卸ししたところ、文書中の行番号アンカーは31本あり、
     * **確かめた5本のうち4本が別のコードを指していた**（`setNoteState` は55行ずれ、
     * `SummaryPanel` は367行ずれ）。参照先ファイルは月に十数回変わるので、
     * 行番号は書いた直後から外れていく。
     *
     * **害はずれることではなく、ずれても気づけないことにある。** リンク切れと違い、
     * 行番号のずれは**もっともらしい別のコード**を指し、読み手はそれを根拠として読む。
     *
     * 行番号を落とすと、代わりに**機械で確かめられる形**になる —
     * ラベルのバッククォート内に書いた識別子が、リンク先のファイルに実在すること。
     * 改名・削除はここで落ちる。**行番号では原理的にこの検査を書けなかった。**
     *
     * ファイル名そのもの（`AppColors.kt` のような表記）は数えない。
     * リンクのパスが同じことを既に言っており、**中身に名前を持たないファイル**
     * （トップレベル関数だけの `RelatedContextScoring.kt` など）を偽陽性にするだけのため。
     */
    @Test
    fun `文書からソースへのリンクは行番号を持たず、名前で指す`() {
        val violations = markdownDocs()
            .flatMap { doc ->
                SOURCE_LINK.findAll(doc.readText()).mapNotNull { match ->
                    val (label, path, anchor) = match.destructured
                    val target = File(doc.parentFile, path).canonicalFile
                    val name = doc.toRelativeString(docsRoot())
                    when {
                        anchor.isNotEmpty() ->
                            "$name: 行番号で指している（$path$anchor）。名前で指すこと"
                        !target.isFile -> "$name: リンク先が無い（$path）"
                        else -> missingSymbol(label, target)?.let { "$name: `$it` が ${target.name} に無い" }
                    }
                }
            }
            .sorted()
            .toList()

        assertTrue(
            "文書のソース参照が古くなっています:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * ラベルが名指しした識別子のうち、リンク先に無い最初の1つ。
     *
     * **バッククォート内だけを見る。** 地の文（「〜を除外済み」など）は名前ではないので数えない。
     */
    private fun missingSymbol(label: String, target: File): String? {
        val source = target.readText()
        val fileName = target.nameWithoutExtension
        return INLINE_CODE_BODY.findAll(label)
            .flatMap { IDENTIFIER.findAll(it.groupValues[1]) }
            .map { it.value }
            .filterNot { it == fileName || it == "kt" || it.length < MIN_SYMBOL_LENGTH }
            .firstOrNull { !Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(source) }
    }

    private fun markdownDocs(): Sequence<File> =
        docsRoot().walkTopDown().filter { it.isFile && it.extension == "md" }

    /**
     * 正本のうち「状態一覧」として数える範囲。
     *
     * **範囲外の言及を一覧登録として数えない。** 区切りが無ければ落とす —
     * 印を消すだけで検査を無効化できてはいけない。
     */
    private fun stateFieldRegion(doc: String, className: String, docPath: String): String {
        val open = "<!-- state-fields: $className -->"
        val close = "<!-- /state-fields -->"
        val start = doc.indexOf(open)
        val end = doc.indexOf(close, start + 1)
        check(start >= 0 && end > start) {
            "$docPath に $className の状態一覧の区切りがありません（$open … $close）"
        }
        return doc.substring(start + open.length, end)
    }

    /**
     * 主コンストラクタのプロパティ名。
     *
     * **先にコメントを落としてから拾う。** 最初の実装は「`,` の直後の `val`」を探しており、
     * **行コメントやKDocが挟まると次の欄を見失って**、7欄中3欄しか見ていなかった。
     * 検査が緑なのは守れているからではなく**何も見ていなかったから**、という形だった。
     *
     * 範囲を主コンストラクタに限るのは、本文中の `val` まで拾うと
     * 文書化する義務のない局所変数まで要求してしまうため。
     */
    private fun primaryConstructorFields(source: String, className: String): List<String> {
        val start = source.indexOf("data class $className")
        check(start >= 0) { "$className の宣言が見つかりません" }
        val open = source.indexOf('(', start)
        check(open >= 0) { "$className の主コンストラクタが見つかりません" }
        var depth = 0
        var end = open
        while (end < source.length) {
            when (source[end]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            end++
        }
        val body = source.substring(open, end).withoutComments()
        val fields = FIELD.findAll(body).map { it.groupValues[1] }.toList()
        check(fields.isNotEmpty()) { "$className のフィールドを1つも読めていません" }
        return fields
    }

    private fun String.withoutInlineCode(): String = INLINE_CODE.replace(this, " ")

    private fun String.withoutComments(): String =
        BLOCK_COMMENT.replace(this, " ").let { LINE_COMMENT.replace(it, " ") }

    /** リンクの深さはソースセットで変わらないので、3つとも同じ規則で数える。 */
    private fun kotlinSources(): Sequence<File> =
        SOURCE_SETS.asSequence()
            .map { repositoryRoot().resolve("app/src/$it/java/com/example/newproject") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }

    /** 状態型の正本突合は本番コードだけが対象なので、こちらは `main` に固定する。 */
    private fun sourceRoot(): File =
        repositoryRoot().resolve("app/src/main/java/com/example/newproject")

    private fun docsRoot(): File = repositoryRoot().resolve("docs")

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
        /** 走査するソースセット。**リンクの深さを数える条件はどこでも同じ。** */
        val SOURCE_SETS = listOf("main", "test", "androidTest")

        val FIELD = Regex("""\b(?:val|var)\s+(\w+)""")
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val INLINE_CODE = Regex("""`[^`\n]*`""")
        /** `[表示](../path/to.md)` の相対リンクだけを見る（URLと絶対パスは対象外）。 */
        val RELATIVE_LINK = Regex("""]\((\.\.?/[^)]+\.md)\)""")

        /** 文書 → `.kt` のリンク。行番号アンカーは**捕まえてから落とす**（見逃すと検査が空振りする）。 */
        val SOURCE_LINK = Regex("""\[([^\]]+)]\(([^)\s]+\.kt)(#L\d+)?\)""")
        val INLINE_CODE_BODY = Regex("""`([^`]+)`""")
        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        /** `id` のような短い語は地の文にも現れるので、名前として数えない。 */
        const val MIN_SYMBOL_LENGTH = 3
    }
}
