package com.example.newproject.model

// ---------------------------------------------------------------------------
// 読書痕跡の退避（エクスポート／インポート）で層をまたいで共有する純データ型。
//
// 束ね方（`ReadingTraceBackupJson`）は `data`、突き合わせ規則（`mergeReadingTraces`）は
// `domain` にあるが、結果の型は `controller` と `ui` の両方が触るためここへ置く。
// **孤児判定（`ReadingTraceOrphanTypes`）と同じ切り分け**。
// ---------------------------------------------------------------------------

/**
 * 退避ファイルの形式と上限。
 *
 * **[FORMAT_VERSION] は痕跡のスキーマ版（[READING_TRACE_SCHEMA_VERSION]）とは別に持つ。**
 * 束ね方が変わっても中身の版は変わらないし、その逆もあるため。
 * 中身1件ずつの版と checksum は `ReadingTraceJson` がそのまま担う。
 */
internal object ReadingTraceBackupLimits {
    /** 退避ファイルであることの目印。別のJSONを渡されたときに中身を解釈する前に落とす。 */
    const val FORMAT_ID = "vigilith.readingTraces"

    /** 現行の退避形式。**読み書きするのはこの版だけ**（→ reading_trace_backup §11）。 */
    const val FORMAT_VERSION = 1

    /**
     * 退避ファイルに束ねてよい痕跡の件数。
     *
     * 痕跡はノート数に比例するので、上限が無いと大きなVaultで破綻する。
     * 実測の目安は1件あたり最大3.3KB（→ current_issues の索引作成コストの項）なので、
     * 5,000件でも通常は20MB弱に収まらず [MAX_FILE_BYTES] のほうが先に効く。
     * **両方持つのは、片方だけでは効かない相手がいるため** — 巨大な返事を持つ
     * 少数の痕跡はバイト側で、小さい痕跡が大量にある場合は件数側で止まる。
     */
    const val MAX_ENTRIES = 5_000

    /**
     * 退避ファイル全体の上限。**読み込み側の防壁でもある。**
     *
     * 退避ファイルはアプリの管理外にあり、ユーザーが任意のファイルを選べる。
     * 上限を置かないと、無関係な巨大ファイルを選んだだけでメモリを食い潰す。
     * 8MB は通常のVault（2,000ノートで約1.4MB）の5倍以上にあたる。
     */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
}

/** 退避ファイルの処理段階。画面の進捗表示に使う。 */
enum class ReadingTraceBackupStep {
    /** 痕跡を列挙して読み出している（書き出し）。 */
    EXPORT_READ,

    /** 退避ファイルの中身と端末側の痕跡を突き合わせている（読み戻しの下見）。 */
    IMPORT_SCAN,

    /** 突き合わせ結果を書き込んでいる（読み戻しの本番）。**ここから先は不可逆。** */
    IMPORT_APPLY
}

/**
 * 読み戻しで適用できなかった1件の理由。
 *
 * **「端末側に無い」はここに来ない** — それは適用できる（新規として受け入れる）。
 * ここへ来るのは**適用してはいけない**ものだけである。
 */
enum class ReadingTraceImportWithholdReason {
    /** 退避ファイル内のその1件を読めなかった（版違い・checksum不一致・改変）。 */
    UNREADABLE_ENTRY,

    /**
     * 端末側に痕跡がある**はず**なのに読み出せなかった。
     *
     * **「無い」へ畳んではいけない。** 畳むと退避側を新規として丸ごと書き、
     * 読めなかっただけの**端末側の返事を警告なしで消す**。SAF の一時的な読取失敗で成立する。
     */
    LOCAL_UNREADABLE,

    /**
     * 下見のあとに端末側が変わり、**見せた計画と食い違った**まま書き込み直前に達した。
     *
     * 不可逆な操作は、承認された内容だけを書く。
     */
    LOCAL_CHANGED,

    /** 突き合わせはできたが、端末側へ書き込めなかった。 */
    SAVE_FAILED
}

/**
 * 読み戻しで適用できなかった1件。
 *
 * **[vaultRelativePath] は null になりうる。** 中身を読めなかった場合、その痕跡が
 * どのノートのものかは分からない（退避ファイルの中では相対パスは中身側にしかない）。
 */
data class WithheldImport(
    val vaultRelativePath: String?,
    val reason: ReadingTraceImportWithholdReason
)

/**
 * 読み戻しの下見。**適用前にこれを見せて確定させる**（→ reading_trace_backup §9）。
 *
 * **返事の損失は方向ごとに数える。** 突き合わせ規則は「返事を持つ側／新しい側が残る」なので、
 * 端末側が新しければ**退避側の返事が失われる**。1つの件数にまとめて
 * 「退避ファイル側に置き換わる」と言うと、通常の往復（書き出した後に返事を書き足す）で
 * **実際と逆の告知**になる。どちらも [merged] の内数。
 */
data class ReadingTraceImportPlan(
    val added: Int,
    val merged: Int,
    /** この端末に書いた返事が、退避ファイル側の返事に置き換わる件数。 */
    val localReplyReplaced: Int,
    /** 退避ファイル側の返事が使われない件数（この端末の返事のほうが新しい）。 */
    val importedReplyDropped: Int,
    val withheld: List<WithheldImport>
)
