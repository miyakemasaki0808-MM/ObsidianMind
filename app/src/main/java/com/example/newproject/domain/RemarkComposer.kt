package com.example.newproject.domain

// ノートへのひとこと（旧「AI補記メモ」）の応答検証と整形。純粋ロジック・Uri非依存。
//
// 旧補記は「4項目の固定選択肢＋3行」を出させながら、守られたかを誰も確認していなかった。
// 蒸留が parseDistillResponseIds で validIds と照合しているのに対して非対称で、
// 同じ製品内で検証の強さが揃っていなかった（→ design/reflect_remark.md §0）。
//
// **一般論の禁止は指示ではなく検査で担保する。** プロンプトに "no generic advice" と
// 書くだけでは守られたか分からないため、ノート由来の語を含むことを機械的に測る。

import com.example.newproject.model.REMARK_NONE_TOKEN

internal object RemarkLimits {
    /**
     * 短すぎる応答の下限。「なるほど」「特にありません」のような相槌を弾く。
     * 仕様の下限（80文字程度）より緩いのは、**検査で落とすと画面には
     * 「見つかりませんでした」しか出ない**ため、長さの好みで機能を殺さないようにするもの。
     */
    const val MIN_CHARS = 15

    /**
     * 上限。日本語1文字≒3バイトなので、[com.example.newproject.model.ReadingTraceLimits.MAX_REMARK_BYTES]
     * （512）に収まる 160 文字とする。**保存側の上限を先に超える値にしてはいけない** —
     * 検査を通ったものが保存で弾かれると、原因が2箇所に散る。
     */
    const val MAX_CHARS = 160

    /**
     * ノート由来と認めるのに必要な、原文と一致する連続文字数。
     *
     * **この値は実Vaultで未検証である。** 小さすぎると「について」「ている」のような
     * 一般的な言い回しが偶然一致して検査が素通りし、大きすぎると妥当なひとことまで落ちる。
     * 4 は「一般的な助詞句より長く、専門語より短い」という見積もりでしかない。
     * 実機で「何も出ない」が続くようなら、まずここを疑う。
     */
    const val MIN_GROUNDING_RUN = 4
}

internal enum class RemarkRejection {
    /** モデルが NONE を返した、または空だった。 */
    NothingToSay,
    TooShort,
    TooLong,
    /** ノートに出てこない言葉だけで書かれている（一般論）。 */
    NotGrounded,
    /** 候補集合に無いノートを [[...]] で参照した。 */
    UnknownLink;

    /**
     * モデルが指示に従えなかったか（＝もう一度きけば変わりうるか）。
     *
     * **[NothingToSay] だけが「本当に出すものが無い」。** 残りはすべて
     * 「言おうとしたが形式を守れなかった」なので、**ユーザーの次の行動が違う** —
     * 前者は再試行しても同じ、後者は再試行が効く。
     * ここを畳むと、モデルの書式失敗が「問いが見つかりませんでした」に化ける。
     */
    val isModelFailure: Boolean get() = this != NothingToSay
}

internal sealed interface RemarkResult {
    data class Accepted(val remark: String) : RemarkResult
    data class Rejected(val reason: RemarkRejection) : RemarkResult
}

/** 候補の一時ID（C01..）。関連ノートと同じ形式・同じ理由で採番する。 */
internal fun remarkCandidateId(index: Int): String = relatedCandidateId(index)

// 行頭の飾り（箇条書き・引用・コードフェンス）を剥がす。モデルは「1文だけ」と指示しても
// 見出しや箇条書きを付けてくることがある。
private val DECORATION_PREFIX = Regex("^\\s*(?:[-*•>]|\\d+[.)])\\s*")
private val REMARK_LINK = Regex("\\[\\[([^\\[\\]]+)]]")

/**
 * モデル応答をひとことへ変換する。
 *
 * @param response モデルの生応答
 * @param groundingSource **モデルへ実際に渡した抜粋**。原文全体ではない。
 *   モデルが参照できたのは抜粋だけなので、根拠の判定もそこに対して行う
 *   （原文で測ると、抜粋から切り落とされた区間の語をモデルが知っていたことになってしまう）。
 * @param candidateTitlesById 提示した候補。`[[C03]]` を実タイトルへ差し戻すのに使う。
 */
internal fun composeRemark(
    response: String,
    groundingSource: String,
    candidateTitlesById: Map<String, String>
): RemarkResult {
    val cleaned = response
        .lineSequence()
        .map { it.trim().trim('`').replace(DECORATION_PREFIX, "") }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()

    if (cleaned.isBlank() || cleaned.equals(REMARK_NONE_TOKEN, ignoreCase = true)) {
        return RemarkResult.Rejected(RemarkRejection.NothingToSay)
    }

    // リンクの差し戻しを先に行う。ID（C03）は原文に出てこないので、
    // 差し戻す前に根拠を測ると必ず不利になる。
    var unknownLink = false
    val resolved = REMARK_LINK.replace(cleaned) { match ->
        val raw = match.groupValues[1].trim()
        val title = candidateTitlesById[raw.uppercase()]
        if (title == null) {
            unknownLink = true
            match.value
        } else {
            "[[$title]]"
        }
    }
    // 候補外を黙って落とすと文が意味を成さなくなる（「[[C09]]とつなげると」が
    // 宙に浮く）ため、丸ごと捨てる。AIピッカーが mapNotNull で黙って落として
    // 件数が減る問題と同じ轍を踏まない。
    if (unknownLink) return RemarkResult.Rejected(RemarkRejection.UnknownLink)

    if (resolved.length < RemarkLimits.MIN_CHARS) {
        return RemarkResult.Rejected(RemarkRejection.TooShort)
    }
    if (resolved.length > RemarkLimits.MAX_CHARS) {
        return RemarkResult.Rejected(RemarkRejection.TooLong)
    }
    // **リンクの有無で免除しない。** 当初は「実在ノートへのリンク自体がノート固有の参照」
    // として検査を飛ばしていたが、それだと
    // 「[[問いを立てる技術]]と並べると、まったく別の角度から捉え直せるかもしれません」
    // のような**残り全部が一般論の文**が通る。今回いちばん潰したかった形そのものだった。
    //
    // 根拠は `[[...]]` を除いた地の文で測る。リンクへ差し戻した実タイトルが
    // たまたま原文に出てくると、それだけで検査を通ってしまうため
    // （wikilinkを本文に持つノートでは普通に起きる）。
    if (!isGroundedInSource(resolved.withoutLinks(), groundingSource)) {
        return RemarkResult.Rejected(RemarkRejection.NotGrounded)
    }
    return RemarkResult.Accepted(resolved)
}

/** `[[...]]` を落とした地の文。根拠はここで測る（リンク先の名前を根拠に数えない）。 */
private fun String.withoutLinks(): String = REMARK_LINK.replace(this, " ")

/**
 * ひとことが原文由来の言葉を含むかを、連続一致の有無で判定する。
 *
 * bigram の重なり率ではなく連続一致を見るのは、日本語では仮名の bigram が
 * どんな文どうしでも一定量重なるため、率にすると閾値の意味が薄れるから。
 * 「原文に出てくる 4 文字以上の並びを含む」なら、語をなぞったことがはっきりする。
 */
private fun isGroundedInSource(remark: String, source: String): Boolean {
    val run = RemarkLimits.MIN_GROUNDING_RUN
    if (source.length < run || remark.length < run) return false
    val haystack = source.lowercase()
    val needle = remark.lowercase()
    for (start in 0..needle.length - run) {
        if (haystack.contains(needle.substring(start, start + run))) return true
    }
    return false
}
