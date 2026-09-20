package com.example.newproject.domain

import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.truncateToUtf8Bytes

// ---------------------------------------------------------------------------
// 余白メモの入力を、保存できる形へ整える純関数。
//
// **上限は壁だが、超えた入力を無かったことにはしない。**
// 受け取ってから切り、切ったら必ず示す（→ features/reflect_margin_memo.md §5）。
// ---------------------------------------------------------------------------

/**
 * 静かに知らせる目安。**壁ではなく合図。**
 *
 * ここを超えても切らない。「短い断片を置く口」という意図を、
 * 拒否ではなく合図で伝える（長く書きたかった回に言葉を消さない）。
 */
internal const val SOFT_MEMO_CHARS = 200

/** 整えた結果。**切ったかどうかを必ず持ち帰る**（黙って切ると気づけない）。 */
internal data class MarginMemoDraft(
    val text: String,
    val wasTruncated: Boolean
) {
    /** 空白だけの入力は「無い」と区別できないので置かせない。 */
    val isBlank: Boolean get() = text.isBlank()
}

/**
 * 入力を保存できる形へ整える。
 *
 * **制御文字（改行・タブを除く）を落とすのが要点。** JSONは制御文字を `\u00XX` の
 * 6バイトへ広げるので、落とさないと**各欄の上限を満たしたままファイル全体の上限を
 * 超えられる**。容量の見積もりが「最悪2倍」で成り立つのはこの正規化があるため
 * （→ features/reflect_margin_memo.md 判断11）。
 *
 * **正規化をやめるなら、件数上限を測り直すこと。**
 */
internal fun composeMarginMemo(raw: String): MarginMemoDraft {
    val normalized = raw
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .trim()
    val truncated = truncateToUtf8Bytes(normalized, ReadingTraceLimits.MAX_MEMO_BYTES)
    return MarginMemoDraft(text = truncated, wasTruncated = truncated != normalized)
}

/** 静かに知らせる目安を超えたか。**切る前に出す合図**なので、正規化前の見た目で数える。 */
internal fun isMemoOverSoftLimit(raw: String): Boolean = raw.trim().length > SOFT_MEMO_CHARS
