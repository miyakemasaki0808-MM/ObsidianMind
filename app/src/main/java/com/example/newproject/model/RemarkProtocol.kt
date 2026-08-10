package com.example.newproject.model

/**
 * ノートへのひとことで、モデルが「出すものが無い」と表明するための語。
 *
 * **`model`（葉）に置くのは、プロンプトを組む `ai` と応答を検証する `domain` の
 * 双方が同じ語を見る必要があるため。** `ai → domain` は依存方向で禁止されており
 * （→ system/architecture.md 2026-07-27）、どちらかに置くと片方が literal を
 * 二重定義することになる。語がずれると「NONE と答えたのに検査が拾わない」という、
 * 実機でしか出ない形で壊れる。
 */
const val REMARK_NONE_TOKEN = "NONE"
