package com.example.newproject.ui.screen

/**
 * ノート画面の並べ方（→ features/rediscover.md 判断6）。
 *
 * **低い横長の画面だけ左右2列にする。** 縦に積むと、見出し・操作ボタン・再会カードが先に高さを取り、
 * 本文は残りだけになる。高さ443dpのカバー画面を横にすると、カードだけで本文の高さが尽きる。
 * 2列なら本文は画面の高さをまるごと使え、カードのボタンも右下のマスコットから離れる。
 *
 * 縦長の画面と、横でも十分に高い画面（タブレット・開いた折りたたみ）は今までどおり縦に積む。
 */
internal enum class ReaderLayout { Stacked, SideBySide }

internal fun readerLayoutFor(widthDp: Float, heightDp: Float): ReaderLayout =
    if (heightDp < SIDE_BY_SIDE_MAX_HEIGHT_DP && widthDp > heightDp) ReaderLayout.SideBySide else ReaderLayout.Stacked

/** これより低い横長の画面で2列にする。カバー画面の横向き（約443dp）と横にしたスマホを含み、開いた折りたたみを含まない。 */
internal const val SIDE_BY_SIDE_MAX_HEIGHT_DP = 480f

/** 2列のときの左列の幅。本文を広く残すため、画面の4割か360dpの小さいほう。 */
internal fun sideColumnWidthDp(widthDp: Float): Float = minOf(widthDp * 0.4f, 360f)
