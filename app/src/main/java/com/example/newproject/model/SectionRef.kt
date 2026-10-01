package com.example.newproject.model

/**
 * 本文の節を指す（→ features/margin_pane.md §5.3）。**見出し名と、同じ名前の見出しの中での順番**で指す。
 *
 * 名前だけで指すと、同名の見出しが2つあるノートで別の節と取り違える。
 * 順番は今の解析の中でだけ意味を持ち、**保存形式には入れない。** 本文を解析し直したら、同じ名前と順番で引き直す。
 *
 * [title] が null は見出しより前の部分（見出しの無いノートでは全体）。
 */
data class SectionRef(val title: String?, val ordinal: Int = 0)
