package com.example.newproject.domain

// 関連ノートの決定的チャンネルと話題スコアリングで共用する採番抽出。
// Regexを呼び出しごとに作らず、全Vaultの候補評価でも軽量に保つ。
private val HEX_PREFIX_REGEX = Regex("^([0-9A-Fa-f]{4})")

// 先頭4桁の16進プレフィックス（例: 0F01）を大文字で返す。該当しなければ null。
internal fun extractHexPrefix(title: String): String? =
    HEX_PREFIX_REGEX.find(title)?.groupValues?.get(1)?.uppercase()
