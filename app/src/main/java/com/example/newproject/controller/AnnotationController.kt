package com.example.newproject.controller

import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.VaultHandle
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.AnnotationListStateWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 既存の補記ファイル（`_AI補記` フォルダ内の `.md`）の一覧と削除を担当する。**Vault単位。**
 *
 * **生成はもう持たない。** 「AI補記メモ」は「ノートへのひとこと」へ作り直され、
 * 出力が1文になったので保存先は読書痕跡サイドカーへ移った
 * （→ [RemarkController] / features/reflect_remark.md）。ここに残っているのは、
 * 作り直す前に生成された `.md` をユーザーが片付けるための導線だけである。
 *
 * したがってノート単位の契約（`cancelNoteScopedJobs` / `withNoteScopedReset`）には
 * 登録しない。一覧はノートを開き直しただけで消してはいけない。
 */
class AnnotationController(
    private val scope: CoroutineScope,
    private val vault: VaultBrowser,
    private val state: AnnotationListStateWriter,
    // Vault切替の世代。NoteViewModel が saveVault() で採番する。
    private val vaultGeneration: () -> Long
) {
    // 一覧・削除は同じ annotationListState を奪い合うのでJobは1本で共有する
    // （削除→再読込の途中で別の削除が走ると、消したはずの項目が戻って見える）。
    private var listJob: Job? = null

    /**
     * Vault切替時に NoteViewModel の saveVault() から呼ばれる契約。
     *
     * 一覧はノート切替では止めない。補記管理画面はノートと
     * 無関係なので、ノートを開き直しただけで一覧が消えるのは誤りになる。
     * 止めるのはVaultが変わったときだけで、そのとき旧Vaultの一覧は無効になる。
     */
    fun onVaultChanged() {
        listJob?.cancel()
        listJob = null
        state.update { AnnotationListState.Idle }
    }

    fun loadList() {
        val handle = vault.current()
        if (handle == null) {
            state.update { AnnotationListState.Error("Vault が選択されていません。") }
            return
        }
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            state.update { AnnotationListState.Loading }
            reloadList(handle, generation)
        }
    }

    fun delete(ref: DocumentRef) {
        val handle = vault.current() ?: return
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            val deleted = handle.deleteDocument(ref)
            reloadList(handle, generation, failureCount = if (deleted) 0 else 1)
        }
    }

    /**
     * 表示中の一覧をまとめて削除する。
     *
     * 削除対象は「起動時に表示されていた一覧」で固定する。走行中にVaultが
     * 切り替わっても、拾い直した新Vaultのファイルを消しにいかないようにするため。
     */
    fun deleteAll() {
        val current = state.current as? AnnotationListState.Success ?: return
        val handle = vault.current() ?: return
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            var failureCount = 0
            current.files.forEach { file ->
                // 旧Vaultのファイルを消し続けないよう、1件ごとに世代を見る。
                // 永続URI権限が残っている端末では、切替後もURIが有効なまま消せてしまう。
                if (generation != vaultGeneration()) return@launch
                if (!handle.deleteDocument(file.ref)) failureCount++
            }
            reloadList(handle, generation, failureCount)
        }
    }

    /**
     * 一覧を読み直して [AnnotationListState] へ反映する。
     *
     * 起動時の世代と食い違っていたら書かない。`cancel()` だけに頼ると、
     * SAF列挙から戻った直後にVault切替が起きた場合に旧Vaultの補記が並ぶ。
     *
     * **[handle] は呼び出し側が取ったものを受け取る。ここで引き直さない。**
     * 引き直すと、削除は旧Vaultへ・読み直しは新Vaultへ、という食い違いが起こる。
     * 表示は世代照合で弾かれるが、**切り替えた先のVaultへ無駄なSAF列挙が飛ぶ**うえ、
     * 将来この関数から世代照合が外れたときに表示混入まで戻る。
     * [VaultHandle] の「開始時に1回だけ取る」規約はここにも効く。
     *
     * @param failureCount 直前の削除で失敗した件数。読み直した一覧に添えて表示する。
     */
    private suspend fun reloadList(
        handle: VaultHandle,
        generation: Long,
        failureCount: Int = 0
    ) {
        try {
            val files = handle.listAnnotationFiles()
            if (generation != vaultGeneration()) return
            state.update { AnnotationListState.Success(files, failureCount) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != vaultGeneration()) return
            state.update { AnnotationListState.Error(e.message ?: "Unknown error") }
        }
    }

}
