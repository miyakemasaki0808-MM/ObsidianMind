package com.example.newproject.model.state

import com.example.newproject.model.BookletEntry

/**
 * 束1つ。**冊子は束を2つ持つ**（引く束と編む束）ので、
 * 「10枚とページ位置と世代」の組をここで型にする。
 *
 * [page] を束と同じ場所に置くのは、**寿命が同じだから。**
 * 「戻れば同じ10枚が同じページ位置で残る」は束とページ位置の2つで1つの条件なのに、
 * 束をVault単位・ページ位置を画面ローカルに置いていたため、
 * **実機の `冊子 → ノート → 戻る` でページ位置だけが失われた**（2026-08-31）。
 * **束が2つになっても同じ**で、引く⇄編むを行き来してもそれぞれの位置が残る。
 *
 * [bundleId] は**束の世代**で、束を作るたびに必ず進む。**2つの束で通し番号**にするので、
 * 画面から見ると「表示中の束が別の値になった」が引き直しでもモード切替でも同じ形になる
 * （→ 判断12。積み直りの再生とページ位置の合わせ直しがこの1つの値で駆動される）。
 * **中身の比較では代わりにならない** — 引き直した結果が同じ並びになることがある。
 */
data class BookletBundle(
    val entries: List<BookletEntry>,
    val page: Int = 0,
    val bundleId: Long = 0L
)

/** 今どちらの束を見ているか。 */
enum class BookletMode { Draw, Weave }

/**
 * 編む束の状態。**トグルの見せ方3通りがそのまま型になっている**（→ features/booklet_mode.md 判断12）。
 */
sealed interface WeaveState {
    /**
     * 種が無い（冊子へ入る直前にノートを開いていなかった）。**トグル自体を出さない。**
     * 行き先が1つしか無いのに選択肢を見せないため。
     */
    data object NoSeed : WeaveState

    /**
     * 種はあるが編めない。**トグルは出すが、編む側は押せない。**
     * 押せない理由を短く添える — 出さないと「なぜ押せないのか」が画面のどこにも無い。
     */
    data class Blocked(val seedTitle: String, val reason: WeaveBlockedReason) : WeaveState

    /** 編めた。[bundle] は**冊子へ入った時点のコピー**で、以後ノート側が変わっても動かない。 */
    data class Ready(val seedTitle: String, val bundle: BookletBundle) : WeaveState
}

/**
 * 編めない理由。**3つを畳まない** — 「探している最中」と「見つからなかった」は
 * 待てば変わるかどうかが違い、利用者の次の行動が変わる。
 */
enum class WeaveBlockedReason {
    /** 関連ノートAIがまだ走っている。 */
    Pending,

    /** 走り終えたが候補が1件も無かった。 */
    Empty,

    /** 関連ノートの算出そのものが失敗した。 */
    Failed
}

/**
 * 冊子（10枚の束）の状態。
 *
 * **束は2つある。** 引く束（純粋ランダム）と編む束（済んだAI推薦から）で、
 * **行き来しても両方残る**（→ features/booklet_mode.md 判断6・判断12）。
 * 切り替えるたびに引き直すと引く側が毎回別の10枚になり、「戻ってこられる」が壊れる。
 */
sealed interface BookletState {
    /** 冊子を開いていない。 */
    data object Idle : BookletState

    /** 束を作っている（Vault走査の待ち）。 */
    data object Loading : BookletState

    /**
     * **[drawn] の [BookletBundle.entries] が空なら「Vaultにノートが無い」。** 別のvariantを作らない。
     */
    data class Open(
        val drawn: BookletBundle,
        val weave: WeaveState = WeaveState.NoSeed,
        val mode: BookletMode = BookletMode.Draw,
        /**
         * 直近の「もう10枚引く」が失敗した理由。
         *
         * **[Failed] と分ける。** あちらは「冊子そのものが作れなかった」で、束が1つも無い。
         * こちらは**引き直しだけが失敗した**状態で、前の引く束・編む束・種はすべて生きている。
         * 畳むと、引き直しの失敗が**編む束と種まで巻き添えにする**（2026-09-07 のレビュー `P2-1`）。
         */
        val redrawError: String? = null
    ) : BookletState

    /** 束そのものが作れなかった（走査の失敗）。ページ単位の失敗は [com.example.newproject.model.BookletCover.Failed]。 */
    data class Failed(val message: String) : BookletState
}

/**
 * いま画面に出ている束。
 *
 * **`mode` が [BookletMode.Weave] でも編む束が [WeaveState.Ready] でなければ引く束を返す。**
 * 押せないトグルからその状態へは入れないが、**表示側に「あり得ない組み合わせ」の分岐を
 * 書かせない**ために、導出をここ1箇所へ寄せる。
 */
val BookletState.Open.visibleBundle: BookletBundle
    get() = when (mode) {
        BookletMode.Draw -> drawn
        BookletMode.Weave -> (weave as? WeaveState.Ready)?.bundle ?: drawn
    }

/** トグルを出すか。**種が無いときだけ出さない**（押せるかどうかは [canWeave] が別に持つ）。 */
val BookletState.Open.showsModeToggle: Boolean
    get() = weave !is WeaveState.NoSeed

/** 編む側へ切り替えられるか。 */
val BookletState.Open.canWeave: Boolean
    get() = weave is WeaveState.Ready

/**
 * [bundleId] の束だけを差し替える。**どちらの束かを id で決める。**
 *
 * 扉の読み込みは束ごとに走るので、**引く束向けの結果を編む束の同じ位置へ書かない**ために要る。
 * 該当する束が既に無ければ（引き直しで消えた等）何もしない。
 */
fun BookletState.Open.updateBundle(
    bundleId: Long,
    transform: (BookletBundle) -> BookletBundle
): BookletState.Open {
    if (drawn.bundleId == bundleId) return copy(drawn = transform(drawn))
    val ready = weave as? WeaveState.Ready ?: return this
    if (ready.bundle.bundleId != bundleId) return this
    return copy(weave = ready.copy(bundle = transform(ready.bundle)))
}

/** 表示中の束だけを差し替える。 */
fun BookletState.Open.updateVisibleBundle(
    transform: (BookletBundle) -> BookletBundle
): BookletState.Open = updateBundle(visibleBundle.bundleId, transform)
