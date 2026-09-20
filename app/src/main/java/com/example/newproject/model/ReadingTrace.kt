package com.example.newproject.model

// ---------------------------------------------------------------------------
// ReadingTrace（読書痕跡）のデータモデル。
//
// ユーザーは「痕跡を残す」操作を一切しない。普通にノートを読むだけで訪問が
// 溜まり、Rediscover で再会した時に「前回の自分はこう読んでいた」を見せる。
// AIの役割は問いの創作ではなく、溜まった訪問の俯瞰要約のみ。
//
// checksum はこのモデルには持たせない（保存形式の関心事なので `ReadingTraceJson` が
// encode 時に付与し decode 時に検証する）。
// ---------------------------------------------------------------------------

/** Vault内のサイドカー置き場。アンダースコア始まりの可視フォルダ。 */
internal const val READING_TRACE_FOLDER_NAME = "_ReadingTraces"

/**
 * 現行の保存形式。**書き込むのは常にこの版**で、旧版は `ReadingTraceJson` の decode が読むときに移行する。
 *
 * **版を上げたら、decode の移行と checksum の正規形の両方へ版の分岐を足す。**
 * 正規形は書かれた版で計算するので、片方だけだと既存のファイルが破損扱いになる。
 * 各版で足した欄とその理由は features/reflect_reading_trace.md が持つ。
 */
internal const val READING_TRACE_SCHEMA_VERSION = 7

/** 読み込みだけは受け付ける版。decode が現行版へ移行させるので、書き戻しは常に現行版になる。 */
internal val READING_TRACE_READABLE_SCHEMA_VERSIONS =
    setOf(1, 2, 3, 4, 5, 6, READING_TRACE_SCHEMA_VERSION)

internal object ReadingTraceLimits {
    /** 訪問の保持上限。超えたら古いものから捨てる（世代アーカイブは持たない）。 */
    const val MAX_VISITS = 30

    /** AI俯瞰要約を出すのに必要な訪問数。1回では「俯瞰」にならない。 */
    const val MIN_VISITS_FOR_AI_SUMMARY = 2

    // 上限はいずれもUTF-8バイト基準（日本語は1文字≒3バイトなので文字数とは別に予算を持つ）。
    const val MAX_RELATIVE_PATH_BYTES = 1024
    const val MAX_NOTE_TITLE_BYTES = 512
    const val MAX_SECTION_TITLE_BYTES = 512
    const val MAX_DOCUMENT_ID_BYTES = 2048
    const val MAX_AI_SUMMARY_BYTES = 2048

    /**
     * 余白メモ1件の上限。日本語で約340字。
     *
     * **壁だが、超えた入力を無かったことにはしない** — 受け取ってから切り、切ったら示す
     * （→ features/reflect_margin_memo.md §5）。静かに知らせる目安は入力側が持つ。
     */
    const val MAX_MEMO_BYTES = 1_024

    /**
     * 1ノートに置ける余白メモの件数。
     *
     * **訪問と違い、超えても古いものから捨てない。** 訪問は読み直せば付き直るが、
     * メモは作り直せない（→ features/reflect_margin_memo.md 判断4）。
     *
     * **値は容量から逆算した暫定値である。** JSONは `"` と `\` を2バイトへ広げるので、
     * 全欄を上限まで詰めた実物が [MAX_FILE_BYTES] に収まる範囲でしか増やせない。
     * `ReadingTraceLimitsTest` が**実シリアライザで測って**固定する。
     */
    const val MAX_MEMOS = 20

    /**
     * サイドカー1ファイルの読み込み上限。
     *
     * **全欄を上限まで詰めた実物が収まること**が条件で、`ReadingTraceLimitsTest` が
     * **本物の `ReadingTraceJson.encode` に通して測り**固定する。
     *
     * **生の文字列長を足す形では保証にならない。** JSONは `"` と `\` を2バイトへ、
     * 制御文字を `\u00XX` の6バイトへ広げるので、各欄の上限を満たしたまま
     * ここを超えられる（→ features/reflect_margin_memo.md 判断11）。
     * 超えると**正しく保存したファイルを次回読めなくなる**（保存側と
     * 読み込み側で上限が食い違う、最も気づきにくい壊れ方）。
     */
    const val MAX_FILE_BYTES = 128 * 1024
}

/**
 * 1回の閲覧。
 *
 * [deepestSectionTitle] は「どこまで読んだか」の最深到達セクション名。見出しの無い
 * ノートや最初の見出しより前で離れた場合は null（その時は [progressPercent] だけを見せる）。
 * 記録時点の見出し名をそのまま保持する歴史的記録なので、後で本文が編集されて
 * その見出しが消えても再解決はしない。
 */
internal data class ReadingVisit(
    val atEpochMillis: Long,
    val deepestSectionTitle: String?,
    val progressPercent: Int
)

/**
 * 読んでいる最中に置いた短い断片。
 *
 * **何にも紐づかない。** AIの生成物にも、ユーザーが選んだ本文の範囲にも結び付けない。
 * 紐づけると「どこに付けるか」という判断が増え、本文が編集されれば紐づけ先も壊れる。
 *
 * [sectionTitle] は置いたときに見ていた見出し。**紐づけではなく歴史的記録**で、
 * 後から本文が編集されて見出しが消えても再解決しない（訪問の最深セクション名と同じ扱い）。
 * ユーザーの操作は1つも増えないのに、数か月後に読み返したとき断片が読める文になる。
 */
// UI状態として画面まで運ばれるため public（`RelatedNote` と同じ理由）。
data class MarginMemo(
    val text: String,
    val writtenAtEpochMillis: Long,
    val sectionTitle: String? = null
)

/**
 * 2つの並びを合流させる。**どちらも捨てない。**
 *
 * 配列なので「両方を残す」ができる。1ノート1組だった旧ひとことは構造上マージできず、
 * 読み戻しのたびにどちらかの言葉が必ず消えていた（→ features/reflect_margin_memo.md 判断5）。
 *
 * 同じ断片は `(日時, 本文)` で畳む。**見出しは畳む鍵に入れない** —
 * 同じ瞬間に同じ文を2度置くことはできないので、見出しだけが違うのは同一の断片である。
 *
 * **件数の上限はここで当てない。** 上限を超えたときに切るのか保留するのかは
 * 呼び出し側の契約で、ここで黙って切ると「捨てない」が破れる。
 */
internal fun mergeMarginMemos(
    first: List<MarginMemo>,
    second: List<MarginMemo>
): List<MarginMemo> = (first + second)
    .distinctBy { it.writtenAtEpochMillis to it.text }
    .sortedBy { it.writtenAtEpochMillis }

/**
 * 1ノート分の痕跡。
 *
 * [vaultRelativePath] が主キー。SAF の documentId は端末／権限グラントごとに変わるため
 * 同期した別端末では別IDになり可搬キーにならない。[documentId] は端末内の高速引き当て
 * キャッシュに留める（checksum の対象外なので、再バインドで書き換えても
 * ユーザー内容の整合性には影響しない）。
 */
internal data class ReadingTrace(
    val vaultRelativePath: String,
    val noteTitle: String,
    val documentId: String?,
    val visits: List<ReadingVisit>,
    /**
     * 再会カードの枠へ出す1件。**種別は [aiSummaryKind] が持つ。**
     *
     * **null は「まだ試していない」とは限らない。** 候補があってもAIが「どれも該当しない」と
     * 返す回（空振り）があり、そのときは [aiSummaryVisitCount] と [aiSummaryKind] だけが
     * 記録されてここは null のままになる（→ features/reunion_card.md「空振りの扱い」）。
     */
    val aiSummary: String? = null,
    /**
     * **最後に生成を試みた時点の [totalVisitCount]。** null は未試行。
     *
     * 「要約が説明している訪問数」ではなく**試行の記録**である点が要点で、
     * これにより空振りも記録できる。空振りを記録しないと [needsAiSummary] が真のまま残り、
     * **同じノートを開くたびに同じ候補で生成し直す**（Mutex 直列なので待ち時間だけが増える）。
     */
    val aiSummaryVisitCount: Int? = null,
    /** [aiSummary] がどの種別か。空振りの回も種別だけは残る。 */
    val aiSummaryKind: ReunionKind? = null,
    /**
     * これまで開いた**延べ回数**。[visits] は直近30件しか残さないので、
     * 保持件数とは別に数える。表示・AI要約の鮮度判定はすべてこちらを見る。
     *
     * 既定値が `visits.size` なのは、v1 から移行した痕跡と新しく作る痕跡の
     * 初期値がどちらもそれになるため。**`copy()` は既定値を再評価しない**ので、
     * 訪問を外すときは [withoutLastVisit] を使うこと（手で `copy(visits = ...)` すると
     * 累計だけ取り残される）。
     */
    val totalVisitCount: Int = visits.size,
    /**
     * 読んでいる最中に置いた余白メモ。**追記であって上書きではない。**
     *
     * **[aiSummary] と違い、作り直せない。** 俯瞰要約は訪問履歴からいつでも生成し直せるが、
     * メモはユーザーが書いた言葉なので、失えば戻らない。
     * 突き合わせ・退避・容量の扱いがすべてこの一点から決まる
     * （→ features/reflect_margin_memo.md）。
     *
     * **並びは追記順（古い順）。** 表示側が新しい順へ並べ替える。
     * 別端末との合流で前後しうるので、**検証は順序を要求しない。**
     */
    val memos: List<MarginMemo> = emptyList(),
    /**
     * 「まだ考えたい」の印。**3つで1組**（片方だけ残らないよう検証で固定する）。
     *
     * **内容ごと保存するのが要点。** 印は*その内容*への意図なので、次の再会で生成し直すと
     * 別の文が出て意図とずれる（→ features/reunion_card.md §6）。
     * 保存済みを再掲すれば生成もゼロで済む。
     */
    val markedAtEpochMillis: Long? = null,
    val markedSummary: String? = null,
    val markedKind: ReunionKind? = null,
    val schemaVersion: Int = READING_TRACE_SCHEMA_VERSION
) {
    /** 印があるか。**あるときは生成そのものを行わず、保存済みを再掲する。** */
    val hasMark: Boolean get() = markedSummary != null
}

/**
 * 直前の生成が**空振りだった**か（AIが「どれも該当しない」と答えた回）。
 *
 * 種別だけが残って内容が無い状態がそれで、**次の生成契機では俯瞰要約へ倒す**合図になる。
 * 呼べなかった回・失敗した回は何も記録しないので、ここには現れない。
 */
internal val ReadingTrace.wasEmptyReunionAttempt: Boolean
    get() = aiSummaryKind != null && aiSummary == null

/** 「まだ考えたい」を押す。押した時点で枠に出ていた内容ごと控える。 */
internal fun ReadingTrace.withMark(
    summary: String,
    kind: ReunionKind,
    atEpochMillis: Long
): ReadingTrace = copy(
    markedAtEpochMillis = atEpochMillis,
    markedSummary = summary,
    markedKind = kind
)

/** 印を外す。**「読んだ」では外れない** — 閉じる操作と取り消しは別（→ features/reunion_card.md §4）。 */
internal fun ReadingTrace.withoutMark(): ReadingTrace = copy(
    markedAtEpochMillis = null,
    markedSummary = null,
    markedKind = null
)

/** 訪問を1件足す。保持は直近[ReadingTraceLimits.MAX_VISITS]件までだが、累計は積み上げる。 */
internal fun ReadingTrace.withVisit(visit: ReadingVisit): ReadingTrace = copy(
    visits = (visits + visit).takeLast(ReadingTraceLimits.MAX_VISITS),
    totalVisitCount = totalVisitCount + 1
)

/**
 * 末尾の訪問を外す。**この閲覧で自分が書いた訪問を差し替えるためだけに使う。**
 *
 * 累計も一緒に戻すのが要点。戻さずに [withVisit] で書き直すと、
 * 背面化のたびに累計が増える（保持件数は30で頭打ちになるので、件数だけ見ていると気づけない）。
 */
internal fun ReadingTrace.withoutLastVisit(): ReadingTrace = copy(
    visits = visits.dropLast(1),
    totalVisitCount = (totalVisitCount - 1).coerceAtLeast(0)
)

/**
 * AI俯瞰要約を作り直す必要があるか。
 * 訪問が増えていなければキャッシュ済みの要約をそのまま使えるので、
 * 2回目以降の再会は生成を待たずに即表示できる。
 *
 * 判定に累計を使う。保持件数で見ると30件で頭打ちになり、31回目以降は
 * どれだけ読んでも「増えていない」と判定されて要約が二度と更新されない。
 */
internal val ReadingTrace.needsAiSummary: Boolean
    get() = visits.size >= ReadingTraceLimits.MIN_VISITS_FOR_AI_SUMMARY &&
        aiSummaryVisitCount != totalVisitCount

/**
 * UTF-8バイト上限で切る。マルチバイト文字の途中では切らない。
 * AI要約が上限を超えたときに検証で弾かれて保存できなくなるのを避けるため、
 * 保存前にここで丸める。
 */
internal fun truncateToUtf8Bytes(value: String, maximumBytes: Int): String {
    if (value.toByteArray(Charsets.UTF_8).size <= maximumBytes) return value
    // 1文字あたり最大4バイトなので、ここから縮めれば数回で収まる。
    var end = minOf(value.length, maximumBytes)
    while (end > 0) {
        val candidate = value.substring(0, end)
        if (candidate.toByteArray(Charsets.UTF_8).size <= maximumBytes) return candidate
        end--
    }
    return ""
}

private fun requireWithinBytes(value: String, maximumBytes: Int, label: String) {
    require(value.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "${label}が長すぎます。" }
}

/**
 * 読み書き両方で使う厳格検証。壊れたものを見せないための最後の砦。
 *
 * 読める版は [READING_TRACE_READABLE_SCHEMA_VERSIONS]（v1 の既存痕跡を破損扱いに
 * しないため）。書き込み側は現行版だけを許す（`ReadingTraceJson.encode` が別途確認する）。
 */
internal fun validateReadingTrace(trace: ReadingTrace) {
    require(trace.schemaVersion in READING_TRACE_READABLE_SCHEMA_VERSIONS) {
        "未対応の痕跡フォーマットです（version=${trace.schemaVersion}）。"
    }
    require(trace.vaultRelativePath.isNotBlank()) { "ノートの相対パスが空です。" }
    requireWithinBytes(trace.vaultRelativePath, ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES, "相対パス")
    requireWithinBytes(trace.noteTitle, ReadingTraceLimits.MAX_NOTE_TITLE_BYTES, "ノートタイトル")
    trace.documentId?.let {
        requireWithinBytes(it, ReadingTraceLimits.MAX_DOCUMENT_ID_BYTES, "documentId")
    }

    require(trace.visits.isNotEmpty()) { "訪問が1件もありません。" }
    require(trace.visits.size <= ReadingTraceLimits.MAX_VISITS) {
        "訪問が上限（${ReadingTraceLimits.MAX_VISITS}件）を超えています。"
    }
    // 累計が保持件数を下回るのは、片方だけ更新した実装ミスか改変。
    require(trace.totalVisitCount >= trace.visits.size) {
        "累計の閲覧回数が保持している訪問件数を下回っています。"
    }
    trace.visits.forEach { visit ->
        require(visit.atEpochMillis >= 0) { "訪問日時が不正です。" }
        require(visit.progressPercent in 0..100) { "到達率が0〜100の範囲外です。" }
        visit.deepestSectionTitle?.let {
            requireWithinBytes(it, ReadingTraceLimits.MAX_SECTION_TITLE_BYTES, "セクション名")
        }
    }

    trace.aiSummary?.let {
        requireWithinBytes(it, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES, "AI要約")
    }
    // **件数は超えたら弾く。古いものから捨てない**（→ features/reflect_margin_memo.md 判断4）。
    // 訪問と逆なのは、訪問は読み直せば付き直るがメモは作り直せないため。
    require(trace.memos.size <= ReadingTraceLimits.MAX_MEMOS) {
        "余白メモが上限（${ReadingTraceLimits.MAX_MEMOS}件）を超えています。"
    }
    trace.memos.forEach { memo ->
        // 空白だけのメモは「無い」と区別できないので受け付けない。
        require(memo.text.isNotBlank()) { "余白メモが空です。" }
        requireWithinBytes(memo.text, ReadingTraceLimits.MAX_MEMO_BYTES, "余白メモ")
        require(memo.writtenAtEpochMillis >= 0) { "余白メモの日時が不正です。" }
        memo.sectionTitle?.let {
            requireWithinBytes(it, ReadingTraceLimits.MAX_SECTION_TITLE_BYTES, "余白メモのセクション名")
        }
    }
    // **並び順は要求しない。** 別端末との合流で前後しうる。
    trace.aiSummaryVisitCount?.let {
        require(it in 0..trace.totalVisitCount) { "AI要約の訪問数が閲覧回数と矛盾しています。" }
    }
    // 要約だけがあって基準の訪問数が無い状態は、次回の再会で無効化できず古い要約を
    // 出し続けてしまうため受け付けない。
    // **逆向き（訪問数だけがある）は許す** — それが空振りの記録そのものだから。
    require(trace.aiSummary == null || trace.aiSummaryVisitCount != null) {
        "AI要約に対応する訪問数が記録されていません。"
    }
    // 種別は「最後に試みた生成」に付くので、試行の記録（訪問数）と対で存在する。
    require((trace.aiSummaryVisitCount == null) == (trace.aiSummaryKind == null)) {
        "AI要約の訪問数と種別の一方だけが記録されています。"
    }

    // 印は3つで1組。片方だけ残ると「内容の無い印」「いつ付けたか不明な印」になる。
    val markedFields = listOf(
        trace.markedAtEpochMillis != null,
        trace.markedSummary != null,
        trace.markedKind != null
    )
    require(markedFields.all { it } || markedFields.none { it }) {
        "「まだ考えたい」の印が中途半端に記録されています。"
    }
    trace.markedSummary?.let {
        require(it.isNotBlank()) { "印の内容が空です。" }
        requireWithinBytes(it, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES, "印の内容")
    }
    trace.markedAtEpochMillis?.let { require(it >= 0) { "印の日時が不正です。" } }
}
