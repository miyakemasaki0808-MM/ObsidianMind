package com.example.newproject.model

/**
 * 反証の一文で、候補のどれも今のノートと食い違わないとAIが判断したときの表明語。
 *
 * **空振りは失敗ではない。** 候補は文の形だけで列挙しているので、食い違う文が無いことは普通に起きる。
 * 指示文（`ai`）と応答の判定（`controller`）の両方が見るので、葉に置く。
 */
const val REBUTTAL_NONE_TOKEN = "NONE"

/**
 * 応答が「食い違うものは無い」の表明かどうか。
 *
 * **前後の飾りは許し、部分一致にはしない**（判定の形は再会カードの表明語と同じ）。
 * 飾りを許さないと空振りが約束違反に化け、部分一致にすると候補文に含まれる `NONE` と区別できない。
 */
fun isRebuttalNone(response: String): Boolean =
    response.trim().trim('-', '*', '•', '`', '"', '\'', ' ', '.', '。')
        .equals(REBUTTAL_NONE_TOKEN, ignoreCase = true)
