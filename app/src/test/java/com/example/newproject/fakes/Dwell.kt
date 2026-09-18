package com.example.newproject.fakes

/**
 * 自動生成の門番を**置かない**（ノートに留まったものとして、すぐ生成へ進む）。
 *
 * 門番そのものを見ないテスト用。門番で待つことを確かめるテストは、
 * 自前の `CompletableDeferred` を渡して開閉を操作する。
 */
val passDwell: suspend () -> Unit = {}
