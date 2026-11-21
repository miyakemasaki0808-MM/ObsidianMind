package com.example.newproject.model

/**
 * ノートの表示の番号。[NoteUiStateStore.beginNoteLoad] がノートを開くたびに [count] を進める。
 * 画面の一時的な状態（節の開閉）を画面の保存値に置き、今の表示のものかを見分けるのに使う。
 *
 * **[issuer] は番号を発行した Store ごとに違う。** 画面の保存値はプロセスの作り直しをまたいで戻るが、
 * Store は作り直されて [count] を0から数え直すので、[count] だけで比べると前のプロセスの番号と一致する。
 */
data class NoteLoadId(val issuer: Long, val count: Long)
