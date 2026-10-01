package com.example.newproject.controller

import com.example.newproject.domain.MemoSendAction
import com.example.newproject.domain.SubmissionCheck
import com.example.newproject.domain.checkSubmission
import com.example.newproject.domain.edited
import com.example.newproject.domain.sendAction
import com.example.newproject.domain.settled
import com.example.newproject.domain.submissionOf
import com.example.newproject.domain.submitted
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MarginMemoDraftStore
import com.example.newproject.model.MarginMemoSlice
import com.example.newproject.model.MarginMemoStateWriter
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 余白メモ — 読んでいる最中に置く短い断片。
 *
 * **AIを1回も呼ばない。** Reflect 系で唯一、端末AIの可否と無関係に動く。
 * `generateMutex` の待ち行列にも並ばないので、要約・関連ノート・痕跡要約の
 * 待ち時間を1ミリ秒も増やさない（→ features/reflect_margin_memo.md 判断1）。
 *
 * **保存そのものは持たない。** 3箇所（永続ファイル・セッションの預かり・退避）の
 * 合流は [ReadingTraceController] の契約で、ここは**画面の状態と書きかけ**を持つ。
 *
 * **寿命が2つある**（→ docs/dev/system/architecture.md 判断4の3行目）。読み書きのジョブと一覧はノート単位で、
 * ノート切替で止めて捨てる。書きかけ（[drafts]）は Vault 単位で、ノートを替えても預かり、Vault 切替でだけ捨てる。
 */
internal class MarginMemoController(
    private val scope: CoroutineScope,
    private val state: MarginMemoStateWriter,
    /** 書きかけの置き場。**ノートの識別で引く**（→ [currentNoteKey]）。 */
    private val drafts: MarginMemoDraftStore,
    /** 今のノートの識別。本文を表示していないときは null。 */
    private val currentNoteKey: () -> String?,
    /** このノートのメモを読む。**3箇所を合流した結果**と、ファイルについて分かったことが返る。 */
    private val loadMemos: suspend (vaultRelativePath: String, confirmAbsence: Boolean) -> MemoLoad,
    private val appendMemo: suspend (vaultRelativePath: String, memo: MarginMemo) -> MemoSaveOutcome,
    private val deleteMemo: suspend (vaultRelativePath: String, memo: MarginMemo) -> MemoDeleteOutcome,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var loadJob: Job? = null

    /**
     * 走行中の書き込み。**保存と削除で1本を共有しない。**
     *
     * 共有すると互いを取り消し合い、「保存中に削除すると `Saving` が残る」
     * 「削除中に保存すると、消えていないメモが画面から消えたままになる」という
     * 両方向の壊れ方をする。**取り消してよいのはノート切替のときだけ。**
     */
    private val writeJobs = mutableSetOf<Job>()

    /** 書き込みを直列化する。取り消しの代わりに順番待ちにする。 */
    private val writeMutex = Mutex()

    /**
     * ノート切替の世代。**操作ごとには増やさない。**
     *
     * 操作ごとに増やしていたころは、削除が走行中の保存を無効化して
     * 結果の反映を落としていた。ここが見たいのは「まだ同じノートか」だけである。
     */
    private var generation = 0L

    /**
     * 相対パスが分かる前に読もうとした。**分かったら読む**（[onPathBound]）。
     *
     * さがす・関連から開いたノートは、走査のキャッシュが冷えていると表示の後にパスが埋まる。
     * パスの無いまま読んで空の一覧で確定すると、そのノートの間は一覧が空のまま置けなくなる。
     * ノート単位の値なので [cancelAndClear] で落とす。
     */
    private var loadWhenPathBound = false

    /**
     * 読む。**世代を進めるので、走行中の保存の結果を捨てる。** ノートの表示とシートやペインを出すときは
     * [ensureLoaded] を通し、読み込み済みなら読み直さない（→ features/margin_pane.md §6.3）。
     */
    fun open(vaultRelativePath: String?) {
        val requestId = ++generation
        val path = vaultRelativePath?.takeIf { it.isNotBlank() }
        if (path == null) {
            // 相対パスが分からないノートには保存先が無い。空で開いて、書ける状態にはしない。
            loadWhenPathBound = true
            state.update { it.copy(marginMemoState = MarginMemoState.Ready(memos = emptyList())) }
            return
        }
        loadWhenPathBound = false
        // **照合する送信は、頼んだ時点で決める**（→ lessons L26）。無いことの確認（全列挙）もそのときだけ頼む。
        val noteKey = currentNoteKey()
        val pending = noteKey?.let { drafts.draft(it).pending }
        state.update { it.copy(marginMemoState = MarginMemoState.Loading) }
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = try {
                loadMemos(path, pending != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(requestId)) return@launch
                state.update { it.copy(marginMemoState = MarginMemoState.Error(e.message ?: "メモを読めませんでした。")) }
                return@launch
            }
            if (!isCurrent(requestId)) return@launch
            // **離れている間に頼んだ送信は、戻って読んだ一覧で決める**（→ features/margin_pane.md §6.2 の②）。
            val check = if (noteKey != null && pending != null) {
                checkSubmission(pending, loaded.memos, loaded.fileRead).also { check ->
                    drafts.update(noteKey) { it.settled(pending, check) }
                }
            } else {
                null
            }
            state.update {
                it.copy(
                    marginMemoState = MarginMemoState.Ready(
                        memos = loaded.memos.newestFirst(),
                        status = when (check) {
                            null -> MemoSaveStatus.None
                            SubmissionCheck.Accepted -> MemoSaveStatus.Saved
                            SubmissionCheck.NotAccepted -> MemoSaveStatus.Failed
                            SubmissionCheck.Unconfirmed -> MemoSaveStatus.Unconfirmed
                        },
                        // 戻った後に受理が分かったときも、切ったことは知らせる。
                        wasTruncated = check == SubmissionCheck.Accepted && pending?.wasTruncated == true
                    )
                )
            }
        }
    }

    /**
     * 読み込み済みか読み込み中なら何もしない。まだ読んでいないか、読めなかったときだけ読む。
     * **パスがまだ無ければ読まずに待つ** — 状態は変えず、パスが分かったときに [onPathBound] が読む。
     */
    fun ensureLoaded(vaultRelativePath: String?) {
        when (state.current.marginMemoState) {
            is MarginMemoState.Ready, MarginMemoState.Loading -> return
            MarginMemoState.Idle, is MarginMemoState.Error -> Unit
        }
        if (vaultRelativePath.isNullOrBlank()) {
            loadWhenPathBound = true
            return
        }
        open(vaultRelativePath)
    }

    /** 今のノートの相対パスが分かった。パスを待っていた読み込みがあれば、ここで読む。 */
    fun onPathBound(vaultRelativePath: String) {
        if (!loadWhenPathBound) return
        open(vaultRelativePath)
    }

    /**
     * 入力を変えた。**書き始めた瞬間の本文の節を書き込み先にする**（→ features/margin_pane.md §5.3）。
     * 本文を表示していないときは何もしない。
     */
    fun edit(text: String, bodySection: SectionRef?) {
        val noteKey = currentNoteKey() ?: return
        drafts.update(noteKey) { it.edited(text, bodySection) }
    }

    /**
     * 置くボタン。**役目は今の入力を整えた本文で決める**（→ [sendAction]）。
     *
     * 未確定の送信と同じ内容なら、新しく置かずに読み直して元の送信を確かめる。
     * 置くときは書き込み先の節（まだ無ければ [bodySection]）の見出しを添える。
     *
     * **入力欄を楽観的に空にしない。** 空にするのは受け取れたと分かったときで、
     * 送った原文のままの入力だけ（→ [com.example.newproject.domain.accepted]）。
     */
    fun submit(vaultRelativePath: String?, bodySection: SectionRef?) {
        val noteKey = currentNoteKey() ?: return
        val current = state.current.marginMemoState as? MarginMemoState.Ready ?: return
        if (current.status == MemoSaveStatus.Saving) return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return
        val draft = drafts.draft(noteKey)
        when (draft.sendAction()) {
            MemoSendAction.None -> return
            MemoSendAction.Verify -> {
                open(path)
                return
            }
            MemoSendAction.Place -> Unit
        }
        val section = draft.target ?: bodySection
        val submission = submissionOf(draft.text, section?.title, clock()) ?: return
        // **送った記録を先に残す。** 結果が同期で返っても、受理を取りこぼさない。
        drafts.update(noteKey) { it.submitted(submission) }

        val requestId = generation
        state.update {
            it.copy(marginMemoState = current.copy(status = MemoSaveStatus.Saving, wasTruncated = false))
        }
        val memo = submission.memo
        launchWrite {
            val outcome = try {
                appendMemo(path, memo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外も「どこにも残っていない」として扱う。握り潰すと
                // 画面だけ保存済みになり、メモが黙って消える。
                MemoSaveOutcome.Lost
            }
            val accepted = outcome == MemoSaveOutcome.Saved || outcome == MemoSaveOutcome.Held
            // **結果は送信を作ったノートの書きかけにだけ届ける。** 照合は送信そのもので行うので、
            // 追わなくなった古い送信の結果は新しい入力を消さない。
            drafts.update(noteKey) {
                it.settled(submission, if (accepted) SubmissionCheck.Accepted else SubmissionCheck.NotAccepted)
            }
            if (!isCurrent(requestId)) return@launchWrite
            state.update { latest ->
                val ready = latest.marginMemoState as? MarginMemoState.Ready ?: return@update latest
                // **置けた場合だけ一覧へ足す。** Full・Lost で足すと、
                // 画面には在るのにどこにも保存されていないメモができる。
                latest.copy(marginMemoState = ready.copy(
                    memos = if (accepted) (ready.memos + memo).newestFirst() else ready.memos,
                    status = when (outcome) {
                        MemoSaveOutcome.Saved -> MemoSaveStatus.Saved
                        // **保存済みとは呼ばない。** 離脱時の書き込みで確定する。
                        MemoSaveOutcome.Held -> MemoSaveStatus.Held
                        MemoSaveOutcome.Full -> MemoSaveStatus.Full
                        MemoSaveOutcome.Lost -> MemoSaveStatus.Failed
                    },
                    // 切り詰めは保存の成否と独立に示す（切ったうえで保存は成功しうる）。
                    wasTruncated = accepted && submission.wasTruncated
                ))
            }
        }
    }

    /**
     * メモを1件消す。**不可逆なので画面側が確認を挟む。**
     *
     * 消えるのは3箇所すべてから（→ [ReadingTraceController] の契約5）。
     * 1箇所でも残ると、次の書き込み契機で合流して復活する。
     */
    fun delete(vaultRelativePath: String?, memo: MarginMemo) {
        val current = state.current.marginMemoState as? MarginMemoState.Ready ?: return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return

        val requestId = generation
        // 先に画面から消す。消えたように見えてから失敗したら、下で戻す。
        state.update { slice ->
            slice.copy(marginMemoState = current.copy(memos = current.memos.filterNot { it == memo }))
        }
        launchWrite {
            val outcome = try {
                deleteMemo(path, memo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MemoDeleteOutcome.Failed
            }
            if (!isCurrent(requestId)) return@launchWrite
            if (outcome == MemoDeleteOutcome.Deleted) return@launchWrite
            // **消せなかったら戻す。** 画面から消したままにすると、
            // 次に開いたとき復活して「消したはずのものが戻った」に見える。
            state.update { latest ->
                val ready = latest.marginMemoState as? MarginMemoState.Ready ?: return@update latest
                latest.copy(
                    marginMemoState = ready.copy(
                        memos = (ready.memos + memo).newestFirst(),
                        status = MemoSaveStatus.Failed
                    )
                )
            }
        }
    }

    /** シートの開閉。**読み込みとは切り離す** — 開閉のたびに読み直すと、走行中の保存の結果を捨てる。 */
    fun setSheetVisible(visible: Boolean) {
        state.update { it.copy(isMarginMemoSheetVisible = visible) }
    }

    /**
     * ノート・Vault切替で読み書きを止め、旧ノートの結果が後から混入するのを防ぐ。
     * **シートも閉じる** — 開いたままだと前のノートのメモを載せた面が残る。
     *
     * **書きかけには触らない。** 同じ Vault の間は預かり、戻れば書き込み先ごと元に戻す。
     * 止めた保存が受理されたかは、戻って読んだときの照合で決まる。
     */
    fun cancelAndClear() {
        generation++
        loadWhenPathBound = false
        loadJob?.cancel()
        loadJob = null
        // **ここだけが取り消してよい場所。** 保存と削除は互いを取り消さない。
        writeJobs.toList().forEach { it.cancel() }
        writeJobs.clear()
        state.update { MarginMemoSlice(MarginMemoState.Idle, isMarginMemoSheetVisible = false) }
    }

    /**
     * 書き込みを1本ずつ順に流す。**取り消しではなく順番待ちで競合を解く。**
     *
     * 取り消しで解くと、片方の結果が画面へ反映されないまま消える
     * （保存が消えれば `Saving` が残り、削除が消えれば「消したように見えるだけ」になる）。
     */
    private fun launchWrite(block: suspend () -> Unit) {
        lateinit var job: Job
        job = scope.launch {
            writeMutex.withLock { block() }
        }
        writeJobs += job
        job.invokeOnCompletion { writeJobs -= job }
    }

    /** Vault 切替。**書きかけを全ノート分捨てる** — 別の Vault のノートへ置けてしまう。 */
    fun clearVaultScoped() {
        drafts.clear()
    }

    private fun isCurrent(requestId: Long): Boolean = generation == requestId

    /** 保存は追記順（古い順）。**読むのは書いた順の逆**なので、表示の直前で反転する。 */
    private fun List<MarginMemo>.newestFirst(): List<MarginMemo> =
        sortedByDescending { it.writtenAtEpochMillis }
}
