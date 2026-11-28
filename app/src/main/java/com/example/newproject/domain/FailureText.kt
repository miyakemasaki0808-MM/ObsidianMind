package com.example.newproject.domain

import com.example.newproject.ai.isAiCoreBusy

/**
 * 失敗を画面の文へ変える1か所（→ docs/dev/system/ui_copy.md §2・docs/dev/system/background_ai_ux.md §6）。
 * **例外の文（英語や内部の言葉）を画面の状態へ入れない。** 場面ごとに決めた日本語だけを返し、AICore の回数制限は
 * 「少し待てば使える」ことを場面に合わせて言う。
 */
internal enum class FailureScene(val text: String, val busyText: String = AI_BUSY_TEXT) {
    /** ノートを開く・読み込む。 */
    NoteLoad("ノートを読み込めませんでした。"),

    /** ノート全体の要約。失敗は保存しないので、開き直せば生成し直す（→ features/note_summary.md 判断9）。 */
    Summary("うまく要約できませんでした。", busyText = "端末のAIが混み合っています。少し待ってから開き直せます。"),

    /** 節を1つ頼んだ要約。 */
    SectionSummary("うまく要約できませんでした。"),

    /** さがす（AI のモードとことばのモード）。 */
    Search("さがせませんでした。"),

    /** さがすで、ノートの一覧を読む。 */
    NoteList("ノートの一覧を読めませんでした。"),

    /** 蒸留を始める。 */
    DistillStart("蒸留を始められませんでした。"),

    /** 端末のAIのモデルを取得する。 */
    ModelDownload("端末のAIを準備できませんでした。"),

    /** 蒸留の保存前の本文を書き出す。 */
    DistillExport("保存前の本文を書き出せませんでした。"),

    /** 冊子を編む。 */
    Booklet("冊子を作れませんでした。"),

    /** 冊子を引き直す。 */
    BookletRedraw("引き直せませんでした。"),

    /** 余白メモの一覧を読む。 */
    MemoLoad("前のメモを読めませんでした。"),

    /** 読書痕跡を書き出す。 */
    TraceExport("読書痕跡を書き出せませんでした。"),

    /** 退避ファイルを読む。 */
    TraceBackupRead("退避ファイルを読み取れませんでした。"),

    /** 読書痕跡を読み戻す。 */
    TraceImport("読書痕跡を読み戻せませんでした。"),

    /** 痕跡の整理の候補を調べる。 */
    TraceCleanup("痕跡の整理を始められませんでした。")
}

internal const val AI_BUSY_TEXT = "端末のAIが混み合っています。少し待つと使えます。"

/** [error] を [scene] の画面の文にする。回数制限で断られたときだけ、待てば使えることを言う。 */
internal fun failureText(error: Throwable, scene: FailureScene): String =
    if (isAiCoreBusy(error)) scene.busyText else scene.text
