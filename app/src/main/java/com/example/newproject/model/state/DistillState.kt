package com.example.newproject.model.state

/**
 * 太字にする範囲の段。**狭い順に並ぶ。**
 *
 * 3段はすべて既存の分割器が出した要素（語句・句・親文）を引き直したもので、
 * 新しい境界規則を持たない。**表示名はUIが持つ。**
 */
enum class DistillRangePreset { Term, Clause, Sentence }

data class DistillCandidateItem(
    val id: String,
    /**
     * **太字になる原文そのもの。** 範囲を調整すると変わる。
     *
     * v1では常に候補文の全文だったが、確定範囲を動かせるようになったので、
     * 「カードに出ている文字列＝`**` で囲まれる文字列」という対応をこの欄が担う。
     */
    val text: String,
    val heading: String?,
    val positionLabel: String,
    /**
     * カードの文脈欄。**[parentText] とは別物。**
     *
     * 割れていない文では「直前の候補単位の親文」を出すため、この候補自身の親文とは限らない。
     */
    val context: String?,
    val isSelected: Boolean = true,
    /**
     * 括弧から取り出した語句候補。**太字になるのは文全体ではなく、この断片だけ。**
     *
     * 本文を書き換える直前の確認画面なので、変更単位が文なのか語句なのかを画面から読めるようにする。
     */
    val isTerm: Boolean = false,
    /** 調整シートに出すこの候補の親文。**確定範囲は必ずこの内側に閉じる。** */
    val parentText: String = text,
    /** [parentText] の中で太字になる位置。**原文offsetではなく表示文字列の相対位置。** */
    val boldStartInParent: Int = 0,
    val boldEndInParent: Int = text.length,
    /** 選べる段。**存在する段だけが入る**（押せない選択肢を出さない）。 */
    val availablePresets: List<DistillRangePreset> = emptyList(),
    /** いまどの段か。 */
    val currentPreset: DistillRangePreset? = null,
    /** 最初の範囲から動いているか。「最初の範囲に戻す」の可否。 */
    val isRangeAdjusted: Boolean = false
)

enum class DistillRecoveryKind { Diverged, Inaccessible, Corrupt }

sealed class DistillState {
    object Idle : DistillState()
    data class Analyzing(val sourceTitle: String) : DistillState()
    /**
     * 端末AIの状態をユーザーへ説明している。文言も導線も [AiStatusNotice] が持つ。
     *
     * **旧 `NeedsDownload` を畳んである。** 「DLが必要」「DL中」「非対応」「取得失敗」は
     * どれも同じ「押した機能が理由を説明する」場面で、違うのは添える導線だけだった。
     */
    data class AiNotice(val notice: AiStatusNotice) : DistillState()
    data class Downloading(val sourceTitle: String, val downloaded: Long, val total: Long) : DistillState()
    /** **ノート側の理由**で蒸留できない（本文が大きすぎる等）。端末AIの状態は [AiNotice] が持つ。 */
    data class Unavailable(val message: String) : DistillState()
    data class Candidates(
        val sourceTitle: String,
        val items: List<DistillCandidateItem>,
        val projectedBoldRatio: Double,
        val isWithinBoldLimit: Boolean,
        val isSingleCandidateException: Boolean = false,
        /**
         * 開いている調整シートの候補。閉じているときは null。
         *
         * **[DistillState] へ新しい variant を足さず、この欄で持つ。**
         * 範囲変更は状態を作り直すので、開閉情報を同じ更新の中で持ち回らないとシートが落ちる。
         */
        val rangeSheetCandidateId: String? = null,
        /**
         * 直近の重なり解消で選択を外した候補。**時間で消さない。**
         *
         * 外れる候補はシートの裏にいるので、一時的な通知にすると
         * 「見ていない場所の変化を、見ていない間に流す」ことになる。
         * 次に選択集合か確定範囲が変わったときに消える。
         */
        val overlapDeselectedIds: List<String> = emptyList()
    ) : DistillState() {
        val selectedCount: Int get() = items.count { it.isSelected }
        val canSaveSelection: Boolean
            get() = selectedCount > 0 && (isWithinBoldLimit || isSingleCandidateException)
        val rangeSheetItem: DistillCandidateItem?
            get() = items.firstOrNull { it.id == rangeSheetCandidateId }
    }
    data class Saving(val sourceTitle: String, val verifying: Boolean = false) : DistillState()
    /** [changedCount] は太字にした箇所の数。文とは限らない（句・語句も数える）。 */
    data class Saved(val sourceTitle: String, val changedCount: Int) : DistillState()
    data class Conflict(val message: String) : DistillState()
    data class RecoveryRequired(
        val kind: DistillRecoveryKind,
        val message: String,
        val canRestore: Boolean,
        val canExport: Boolean,
        val canKeepCurrent: Boolean
    ) : DistillState()
    data class RecoveryResolved(val message: String) : DistillState()
    data class Error(val message: String, val canRetry: Boolean = true) : DistillState()
}
