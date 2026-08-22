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
 * 現行の保存形式。**書き込むのは常にこの版**。
 *
 * v2 で [ReadingTrace.totalVisitCount] を足した。v1 は保持件数（最大30）を累計回数として
 * 使っており、30回を超えると表示が「30回」で止まるだけでなく、AI俯瞰要約の再生成判定
 * （[needsAiSummary]）も止まって古い要約が「最新」として出続けていた。
 *
 * v3 で「ノートへのひとこと」を足した。旧「AI補記メモ」が Vaultへ `.md` を
 * 作っていたのを、1文になったのに合わせてサイドカーへ移したもの。
 *
 * v4 で [ReadingTrace.reflection] へ畳み、**ユーザーの返事**を組にした。
 * v3 の平坦な `remark` は「AIのひとことだけ」を保存していたが、
 * **AIが問いを投げて会話が終わる**ため読後感が宙に浮いていた。
 * v3 の値は `Reflection(remark = 旧remark, reply = null)` として読み込める。
 *
 * v5 で [Reflection.mirrored]（返事を受けてAIが返す1文）を足した。
 * 返事を書いて終わりだと**受け取ってもらえた感触が無い**ため、1往復だけ閉じる。
 *
 * v6 で再会カードの枠を4種別で取り合う形にし、[ReadingTrace.aiSummaryKind] と
 * 「まだ考えたい」の印（[ReadingTrace.markedAtEpochMillis] ほか）を足した。
 * v5 までの `aiSummary` はすべて俯瞰要約なので、[ReunionKind.Overview] として読み込む。
 */
internal const val READING_TRACE_SCHEMA_VERSION = 6

/** 読み込みだけは受け付ける版。decode が現行版へ移行させるので、書き戻しは常に現行版になる。 */
internal val READING_TRACE_READABLE_SCHEMA_VERSIONS =
    setOf(1, 2, 3, 4, 5, READING_TRACE_SCHEMA_VERSION)

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
     * ひとことの上限。仕様は「原則1文・最大2文・80〜120文字程度」なので、
     * 日本語1文字≒3バイトで360バイト。生成の揺れを吸収して512とする。
     * **要約（2048）より意図的に小さい** — 枠を広げると長文が通り、
     * 「1文だけ出す」という設計が保存側から緩む。
     */
    const val MAX_REMARK_BYTES = 512

    /**
     * 返事の上限。**8,000文字＝日本語で最大24,000バイト**に余裕を持たせた値。
     *
     * **以前は 1536（400文字）だった。これはAIへ渡せる長さから逆算した数字で、
     * ユーザーの文章をローカルLLMの都合で縛っていた。**
     * 本文は「保存は原文・AIへは抜粋」でやっているのに、返事だけ両方を
     * 同じ数字で縛っていたのが誤り（→ features/reflect_remark.md §11）。
     *
     * 「汎用エディタにしない」という意図（→ feature_ideas N-6）は
     * **壁ではなく合図**で守る — 2,000文字を超えたら静かに知らせるだけで、
     * 切り詰めも拒否もしない。
     */
    const val MAX_REPLY_BYTES = 25_600

    /** 映し返しの上限。ひとことと同じ「1文」なので同じ枠でよい。 */
    const val MAX_MIRRORED_BYTES = 512

    /**
     * サイドカー1ファイルの読み込み上限。
     *
     * **上の各上限の最悪ケースを足しても収まること**が条件で、
     * `ReadingTraceLimitsTest` が計算して固定している。返事の上限を上げたときに
     * ここを忘れると、**正しく保存したファイルを次回読めなくなる**（保存側と
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
 * AIのひとことと、それへのユーザーの返事の組。
 *
 * **別々の文字列ではなく1組として持つ。** 片方だけが残る状態
 * （返事だけあって元の問いが分からない／問いを作り直したのに古い返事が残る）を
 * 型で作れなくするため。読み返すときも必ず対で出す。
 *
 * **[reply] を書いてもAIへ再送しない。** 返事を書いた時点で対話は完了する。
 * 往復させると「AIと会話するアプリ」になり、
 * 「AIは相手役／本質はノートを読む」という北極星から外れる。
 * ここに残るのは**ユーザー自身の言葉**であって、AIへの入力ではない。
 */
// `RemarkState`（public な sealed class）が保持するため public。
// 痕跡の他の型は internal だが、これだけはUI状態として画面まで運ばれる
// （`RelatedNote` が public なのと同じ理由）。
data class Reflection(
    val remark: String,
    val remarkedAtEpochMillis: Long,
    val reply: String? = null,
    val repliedAtEpochMillis: Long? = null,
    /**
     * 返事を受けてAIが返す1文。**問いではない。**
     *
     * 返事を書いて終わりだと「受け取ってもらえた」感触が無く、対話が閉じない。
     * ただし**1往復だけ**で、ここに問いを書かせると無限会話の入口になる
     * （→ features/reflect_remark.md §10）。
     *
     * 生成に失敗しても null のまま。**返事は先に保存済み**なので、
     * ここが空でもユーザーの言葉は失われない。
     */
    val mirrored: String? = null
) {
    /**
     * 返事を残す。**同じ問いに対する返事は上書きする**（1組しか持たないため）。
     * 返事を書き直したら映し返しも捨てる — 古い返事に対する応答が残ると噛み合わない。
     */
    fun withReply(reply: String, atEpochMillis: Long): Reflection =
        copy(reply = reply, repliedAtEpochMillis = atEpochMillis, mirrored = null)

    fun withMirrored(mirrored: String): Reflection = copy(mirrored = mirrored)

    val hasReply: Boolean get() = reply != null
}

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
     * 既定値が `visits.size` なのは、v1 から移行した痕跡と、既存の構築箇所の
     * 素直な初期値がどちらもそれになるため。**`copy()` は既定値を再評価しない**ので、
     * 訪問を外すときは [withoutLastVisit] を使うこと（手で `copy(visits = ...)` すると
     * 累計だけ取り残される）。
     */
    val totalVisitCount: Int = visits.size,
    /**
     * ノートへのひとこと と、それへのユーザーの返事の組。
     *
     * **[aiSummary] と違い、訪問数では無効化しない。** 俯瞰要約の入力は訪問履歴なので
     * 訪問が増えれば作り直す必要があるが、ひとことの入力は本文であり、
     * ユーザーが明示ボタンを押したときにだけ作られて上書きされる。
     *
     * **1ノート1組。** 生成のたびに上書きする（旧補記はファイルが増え続けていた）。
     */
    val reflection: Reflection? = null,
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
 * 累計も一緒に戻すのが要点。戻さずに [withVisit] で書き直すと、背面化のたびに
 * 累計が増えて「ホームボタンを押すたび回数が膨らむ」に逆戻りする
 * （保持件数は30で頭打ちになるので、v1ではこの誤りが見えなかった）。
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
    trace.reflection?.let { reflection ->
        // 空白だけのひとことは「無い」と区別できないので受け付けない。
        // 保存側で null へ倒すのが正で、ここは最後の砦。
        require(reflection.remark.isNotBlank()) { "ひとことが空です。" }
        requireWithinBytes(reflection.remark, ReadingTraceLimits.MAX_REMARK_BYTES, "ひとこと")
        require(reflection.remarkedAtEpochMillis >= 0) { "ひとことの日時が不正です。" }
        reflection.reply?.let { reply ->
            require(reply.isNotBlank()) { "返事が空です。" }
            requireWithinBytes(reply, ReadingTraceLimits.MAX_REPLY_BYTES, "返事")
        }
        // 返事と日時の一方だけが残っていると、次に開いたとき
        // 「返事はあるがいつ書いたか分からない」状態になる。
        require((reflection.reply == null) == (reflection.repliedAtEpochMillis == null)) {
            "返事と日時の一方だけが記録されています。"
        }
        reflection.repliedAtEpochMillis?.let {
            require(it >= 0) { "返事の日時が不正です。" }
        }
        reflection.mirrored?.let { mirrored ->
            require(mirrored.isNotBlank()) { "映し返しが空です。" }
            requireWithinBytes(mirrored, ReadingTraceLimits.MAX_MIRRORED_BYTES, "映し返し")
            // 返事が無いのに映し返しだけあるのは、片方を消し忘れた実装ミスか改変。
            require(reflection.reply != null) { "返事が無いのに映し返しだけが記録されています。" }
        }
    }
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
