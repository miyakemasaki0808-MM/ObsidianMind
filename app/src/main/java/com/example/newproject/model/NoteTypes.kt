package com.example.newproject.model


// lastModified はエポックミリ秒。SAFプロバイダが値を返さない場合は null。
//
// vaultRelativePath は Vault ルートからの相対パス（例 "ideas/habit.md"）。
// SAF の documentId は端末／権限グラントごとに異なり、同期した別端末では同じファイルでも
// 別IDになる＝可搬キーにならない。そのため ReadingTrace のサイドカー引き当てには
// 同期をまたいで安定するこの相対パスを使う。
// 再帰走査でのみ組み立てるので、非再帰の列挙（_AI補記 一覧）では既定の空文字が入る。
data class NoteFile(
    val name: String,
    val ref: DocumentRef,
    val lastModified: Long? = null,
    val vaultRelativePath: String = ""
)

// Vault 直下のフォルダ。documentId は配下をたどる起点に使う。
data class NoteFolder(val name: String, val documentId: String)

/**
 * Vault走査の結果。[unreadableFolderPaths] は**列挙に失敗したフォルダ**のVault相対パス
 * （ルートなら空文字）。
 *
 * **空でなければ [notes] は「読めた分」でしかなく、見つからないことは「存在しない」を意味しない。**
 * 表示系（Rediscover・さがす・関連ノート）は読めた分で動き続けてよい — 走査が部分的に
 * 失敗しただけでランダム表示が止まるほうが体験として悪い。
 * **完全性を要求するのは、不在を根拠に何かを消す処理だけ**にする。
 */
data class VaultScan(
    val notes: List<NoteFile>,
    val unreadableFolderPaths: Set<String> = emptySet()
) {
    val isComplete: Boolean get() = unreadableFolderPaths.isEmpty()
}

data class NoteMeta(
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val wikilinkTitles: Set<String> = emptySet()
)
