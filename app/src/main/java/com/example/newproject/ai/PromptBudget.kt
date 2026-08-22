package com.example.newproject.ai

import com.example.newproject.model.PromptLimits

/**
 * 完成プロンプトの入力上限を**1箇所で強制する**。
 *
 * ## なぜ1箇所なのか
 *
 * 用途別の本文上限（`NoteExcerptLimits`）はあったが、**完成プロンプト全体を閉じる制約が無かった。**
 * 会話履歴・質問・候補名はどれも本文抜粋の外側にあるので、抜粋を絞っても入力は伸びうる。
 * 各 builder が思い思いに切ると、**どこが効いているのか誰も言えない状態**になるため、
 * 最後の1回をここへ集約する。
 *
 * ## 何を削るか
 *
 * **指示文と締め（質問）は削らない。** 指示文を欠くとモデルが何をすべきか分からなくなり、
 * 質問を欠くと答えるものが消える。**削れるのは材料（[body]）だけ**である。
 *
 * ## ここへ来ること自体が異常である
 *
 * 各 builder は自分の可変部を部分予算で閉じているので、**通常運用でこの切り詰めは起きない。**
 * `PromptBudgetTest` が「設計が意図する最大構成」で切り詰めが起きないことを固定しており、
 * 落ちたら部分予算のどれかが上限と噛み合わなくなったという意味になる。
 */
internal object PromptBudget {

    /** 切ったことをモデルへ明示する。黙って切ると、途中で切れた文と区別できない。 */
    const val TRUNCATION_MARKER = "\n(truncated)"

    /**
     * [instructions]（指示文）＋[body]（材料）＋[closing]（締め）を連結し、
     * [PromptLimits.MAX_PROMPT_CHARACTERS] を超えるぶんを [body] の末尾から削る。
     */
    fun assemble(instructions: String, body: String, closing: String = ""): String {
        val allowedBody = PromptLimits.MAX_PROMPT_CHARACTERS - instructions.length - closing.length
        if (body.length <= allowedBody) return instructions + body + closing

        val keep = (allowedBody - TRUNCATION_MARKER.length).coerceAtLeast(0)
        return instructions + body.take(keep) + TRUNCATION_MARKER + closing
    }
}
