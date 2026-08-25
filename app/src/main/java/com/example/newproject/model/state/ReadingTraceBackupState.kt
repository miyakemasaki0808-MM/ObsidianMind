package com.example.newproject.model.state

import com.example.newproject.model.ReadingTraceBackupStep
import com.example.newproject.model.ReadingTraceImportPlan
import com.example.newproject.model.WithheldImport

/**
 * 読書痕跡の退避（書き出し／読み戻し）の状態。**Vault単位**（ノート切替では消えない）。
 *
 * **下見（[Planned]）と適用（[Imported]）を分けるのが要点。** 読み戻しは不可逆なので、
 * 「何件が上書きされるか」を見せてから確定させる（→ reading_trace_backup §9）。
 * 1つの状態に畳むと、確定前と確定後の区別が画面から消える。
 */
sealed interface ReadingTraceBackupState {
    data object Idle : ReadingTraceBackupState

    /**
     * 実行中。[total] が 0 のあいだは件数が未確定（列挙の前）。
     *
     * **段階を持たせる。** 書き出し・下見・適用は中断したときの意味が違い、
     * とくに [ReadingTraceBackupStep.IMPORT_APPLY] だけが「途中で止めると
     * 部分的に適用された状態が残る」段階である。
     */
    data class Working(
        val step: ReadingTraceBackupStep,
        val done: Int,
        val total: Int
    ) : ReadingTraceBackupState

    /**
     * 書き出せた。
     *
     * [unreadableKeys] は壊れていて束ねられなかった痕跡のファイル名（拡張子を除く）。
     * **相対パスは出せない** — 中身を読めていないうえ、ファイル名は相対パスの
     * ハッシュで不可逆だから。ファイル名なら Vault内の `_ReadingTraces/` で
     * ユーザー自身が現物へ辿り着ける。
     */
    data class Exported(
        val written: Int,
        val unreadableKeys: List<String>
    ) : ReadingTraceBackupState

    /**
     * 下見が済み、確定を待っている。**まだ1件も書いていない。**
     *
     * [revised] は「確定を押したが、端末側が下見の時点から変わっていたので作り直した」印。
     * **不可逆な操作は、画面に出したものだけを書く** — 変わっていたら書かずに計画を出し直し、
     * もう一度確定させる。この印が無いと、利用者は同じ画面をもう一度見せられた理由が分からない。
     */
    data class Planned(
        val plan: ReadingTraceImportPlan,
        val revised: Boolean = false
    ) : ReadingTraceBackupState

    /**
     * 読み戻した。[interrupted] が真なら、途中で中断したので
     * **[added] + [merged] 件までしか適用されていない。**
     */
    data class Imported(
        val added: Int,
        val merged: Int,
        val withheld: List<WithheldImport>,
        val interrupted: Boolean = false
    ) : ReadingTraceBackupState

    data class Error(val message: String) : ReadingTraceBackupState
}
