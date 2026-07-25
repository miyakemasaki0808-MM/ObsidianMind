package com.example.newproject.model

import com.example.newproject.data.ReadingTraceJson
// ---------------------------------------------------------------------------
// ReadingTrace（読書痕跡）のデータモデル。
//
// ユーザーは「痕跡を残す」操作を一切しない。普通にノートを読むだけで訪問が
// 溜まり、Rediscover で再会した時に「前回の自分はこう読んでいた」を見せる。
// AIの役割は問いの創作ではなく、溜まった訪問の俯瞰要約のみ。
//
// checksum はこのモデルには持たせない（保存形式の関心事なので ReadingTraceJson が
// encode 時に付与し decode 時に検証する）。
// ---------------------------------------------------------------------------

/** Vault内のサイドカー置き場。アンダースコア始まりの可視フォルダ。 */
internal const val READING_TRACE_FOLDER_NAME = "_ReadingTraces"

internal const val READING_TRACE_SCHEMA_VERSION = 1

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

    /** サイドカー1ファイルの読み込み上限。上記×MAX_VISITS から十分な余裕を取った値。 */
    const val MAX_FILE_BYTES = 64 * 1024
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
    val aiSummary: String? = null,
    val aiSummaryVisitCount: Int? = null,
    val schemaVersion: Int = READING_TRACE_SCHEMA_VERSION
)

/** 訪問を1件足す。上限を超えた分は古い方から捨てる。 */
internal fun ReadingTrace.withVisit(visit: ReadingVisit): ReadingTrace =
    copy(visits = (visits + visit).takeLast(ReadingTraceLimits.MAX_VISITS))

/**
 * AI俯瞰要約を作り直す必要があるか。
 * 訪問が増えていなければキャッシュ済みの要約をそのまま使えるので、
 * 2回目以降の再会は生成を待たずに即表示できる。
 */
internal val ReadingTrace.needsAiSummary: Boolean
    get() = visits.size >= ReadingTraceLimits.MIN_VISITS_FOR_AI_SUMMARY &&
        (aiSummary == null || aiSummaryVisitCount != visits.size)

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

/** 読み書き両方で使う厳格検証。壊れたものを見せないための最後の砦。 */
internal fun validateReadingTrace(trace: ReadingTrace) {
    require(trace.schemaVersion == READING_TRACE_SCHEMA_VERSION) {
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
    trace.aiSummaryVisitCount?.let {
        require(it in 0..trace.visits.size) { "AI要約の訪問数が訪問件数と矛盾しています。" }
    }
    // 要約だけがあって基準の訪問数が無い状態は、次回の再会で無効化できず古い要約を
    // 出し続けてしまうため受け付けない。
    require((trace.aiSummary == null) == (trace.aiSummaryVisitCount == null)) {
        "AI要約と訪問数の一方だけが記録されています。"
    }
}
