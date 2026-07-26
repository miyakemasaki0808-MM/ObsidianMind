package com.example.newproject.model

import android.net.Uri

// lastModified はエポックミリ秒。SAFプロバイダが値を返さない場合は null。
//
// vaultRelativePath は Vault ルートからの相対パス（例 "ideas/habit.md"）。
// SAF の documentId は端末／権限グラントごとに異なり、同期した別端末では同じファイルでも
// 別IDになる＝可搬キーにならない。そのため ReadingTrace のサイドカー引き当てには
// 同期をまたいで安定するこの相対パスを使う。
// 再帰走査でのみ組み立てるので、非再帰の列挙（_AI補記 一覧）では既定の空文字が入る。
data class NoteFile(
    val name: String,
    val uri: Uri,
    val lastModified: Long? = null,
    val vaultRelativePath: String = ""
)

// Vault 直下のフォルダ。documentId は配下をたどる起点に使う。
data class NoteFolder(val name: String, val documentId: String)

data class NoteMeta(
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val wikilinkTitles: Set<String> = emptySet()
)
