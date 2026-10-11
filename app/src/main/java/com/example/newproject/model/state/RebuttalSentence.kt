package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

/**
 * 反証の一文（→ features/rebuttal_sentence.md）。null のとき枠を出さない。
 *
 * [text] は [source] の本文にある1文を**原文のまま**持つ。AIの応答の本文は使わない —
 * 言い換えや翻訳が混ざると「本文にある1文」でなくなる。
 *
 * **読み込み中も失敗も持たない。** 自動で走る機能なので、結果があるときだけ枠が現れる。
 */
data class RebuttalSentence(val text: String, val source: RelatedNote)
