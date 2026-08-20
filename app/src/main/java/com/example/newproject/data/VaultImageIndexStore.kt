package com.example.newproject.data

import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageEntry
import com.example.newproject.domain.image.NoteImageIndex
import com.example.newproject.domain.image.resolveImage
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 走査で集めた画像と、その走査が完全だったか。 */
data class VaultImageScan(
    val entries: List<NoteImageEntry>,
    val isComplete: Boolean
)

/** `.obsidian` はテーマ・プラグインの設定で、ユーザーのノートが参照する画像は入らない。 */
internal const val OBSIDIAN_CONFIG_FOLDER_NAME = ".obsidian"

/**
 * 画像索引のキャッシュと解決。
 *
 * ## なぜ解決までここが持つのか
 *
 * 「見つからなければ作り直す」という再走査の規則は**キャッシュの規則**なので、
 * 呼び出し側へ配ると歯止め（下記）を書き忘れた経路が必ず出る。
 * 索引を持つ側が解決まで引き受けることで、規則を1箇所に閉じる。
 *
 * ## 3つの無効化の契機
 *
 * | 契機 | 何が起きるか |
 * |---|---|
 * | Vault切替（[vaultGeneration] の変化） | 索引を捨てる。旧Vaultの画像を新Vaultで出さない |
 * | 解決に失敗し、かつ索引が [ttlMillis] より古い | **1回だけ**作り直して再試行する |
 * | それ以外 | 作り直さない |
 *
 * **2つ目の歯止めが要点。** 「解決に失敗したら作り直す」を無条件に入れると、
 * リンク切れの画像が1つあるだけで**再コンポーズのたびにVault全走査が走る**。
 * 逆に作り直さないと、Obsidian側で画像を足してもVault切替まで出てこない。
 * 失敗したときだけ、しかも古いときだけ、が両方を満たす。
 *
 * 再走査してもなお見つからなければ読込時刻を更新するので、
 * 壊れたリンクが TTL 未満のあいだ再走査を誘発し続けることはない。
 *
 * ## ヒットしても、載っている値は古くなりうる
 *
 * 上の3つは**索引を作り直す**契機で、**索引に載っている値の鮮度は別の問題**である。
 * 当たっている限り作り直されないので、更新日時は初回走査時の値で固定され、
 * 同じ参照を上書きされても復号キャッシュの鍵が変わらない。
 * そこで **TTL を過ぎたヒットに限り、当たった1件だけ更新日時を引き直す**（[verified]）。
 * Vault全体をもう一度歩くのに比べて桁違いに安く、同じ問いに答えられる。
 *
 * **TTL 未満では引き直さない。** 走査直後は索引の値が新しいと分かっているうえ、
 * 1ノートは画像を何枚も持つので、ここを無条件にすると表示のたびに照会が積み上がる。
 * **確かめた結果は参照ごとに控え**、次のTTLまで再利用する（[CheckedVersion]）。
 * 控えないと、TTL を過ぎた後は解決のたびに外部I/Oが走る。
 *
 * ## 不完全な索引もキャッシュする
 *
 * 設計書の初版は「不完全な索引はキャッシュしない」だったが、**段階2で
 * [NoteImageFailure.Unverifiable] を型として分けた時点で不要になった。**
 * キャッシュを禁じていたのは「無い」と断定させないためで、その保証は
 * いま型が持っている。禁じたままにすると、読めないフォルダが1つあるだけで
 * **画像1枚ごとにVault全走査**が走る（ノートは画像を何枚も持つ）。
 */
internal class VaultImageIndexStore(
    private val vault: VaultBrowser,
    private val vaultGeneration: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = INDEX_TTL_MS
) {
    private val mutex = Mutex()
    private var cached: NoteImageIndex? = null
    private var cachedGeneration = Long.MIN_VALUE
    private var loadedAt = 0L

    /** 参照ごとの鮮度確認の記帳。**索引を作り直したら捨てる**（走査した値のほうが新しい）。 */
    private val checked = HashMap<DocumentRef, CheckedVersion>()

    /** 走査した回数。**歯止めが効いていることの検証にだけ使う。** */
    internal var scanCount = 0
        private set

    /** 世代を引き直した回数。**走査の代わりに引いた回数**を数える（同じく検証専用）。 */
    internal var probeCount = 0
        private set

    /**
     * 要求を解決する。索引が無ければ作り、失敗して索引が古ければ1回だけ作り直す。
     *
     * Vault未選択なら [NoteImageFailure.Unverifiable]（「無い」とは言えないため）。
     */
    internal suspend fun resolve(request: ImageRequest): ImageResolution {
        // **索引を要らない要求で走査を起こさない。** 外部URLと空は Vault を見るまでもなく
        // 結論が出る。先に索引を作ると、外部画像しか無いノートを開いただけで全走査が走る。
        if (request !is ImageRequest.Lookup) return resolveImage(request, NoteImageIndex.EMPTY_INCOMPLETE)
        return resolveInVault(request)
    }

    private suspend fun resolveInVault(request: ImageRequest): ImageResolution = mutex.withLock {
        val handle = vault.current() ?: return ImageResolution.Failed(NoteImageFailure.Unverifiable)
        val generation = vaultGeneration()
        val index = indexFor(handle, generation)
        val first = resolveImage(request, index)
        // TTL 未満なら索引をそのまま信じる。**当たったときも外したときも照会しない**ので、
        // 走査直後の連続表示（1ノートに画像が何枚もある）で照会が増えない。
        if (now() - loadedAt < ttlMillis) return first
        val checked = verified(handle, first)
        if (!checked.isMiss()) return checked
        // 古い索引で外したときだけ作り直す。作り直した時刻を控えるので、
        // 壊れたリンクが TTL 未満のあいだ再走査を誘発し続けることはない。
        return resolveImage(request, rebuild(handle, generation))
    }

    /**
     * 当たった参照の**中身の世代を引き直す**。
     *
     * 索引は当たっている限り作り直されないので、[NoteImageEntry.lastModified] は
     * 初回走査時の値のまま固定される。それが復号キャッシュの鍵に入るため、
     * **同じ参照を上書きされても古い Bitmap を返し続ける**（→ note_image_rendering §6）。
     * ここで1件だけ引き直すことで、全走査を増やさずに鍵を正しくする。
     *
     * | 引き直した結果 | 扱い | なぜ |
     * |---|---|---|
     * | 値が取れた | 実測値を鍵へ載せる | 索引の値より新しい |
     * | 存在するが列を返さない | 索引の値のまま | 世代で見分けられないと分かるだけ。**ここで作り直すと走査が永久に繰り返される** |
     * | 存在を確かめられない | **miss へ落とす** | 索引が古い可能性がある。作り直せば引き当て直せる |
     *
     * miss へ落としても全走査が連発しないのは、合流先が
     * 「miss かつ索引が [ttlMillis] より古いときだけ1回」だからである。
     * **照会が失敗し続けても、作り直しは TTL ごとに1回**に抑えられる。
     */
    private suspend fun verified(handle: VaultHandle, resolution: ImageResolution): ImageResolution {
        if (resolution !is ImageResolution.Resolved) return resolution
        val ref = resolution.ref
        // **確かめた結果は参照ごとに控える。** 索引の [loadedAt] は作り直しでしか動かないので、
        // これが無いと TTL 超過後は「解決するたびに外部I/O」になる
        // （1枚の表示でも `measure` と `load` の二段があり、スクロールや全画面遷移で作り直される）。
        checked[ref]?.takeIf { now() - it.at < ttlMillis }?.let { return it.applyTo(resolution) }
        return when (val lookup = probe(handle, ref)) {
            is DocumentVersionLookup.Found ->
                CheckedVersion(lookup.lastModified, now())
                    .also { checked[ref] = it }
                    .applyTo(resolution)
            // 確かめられなかったものは控えない。合流先で索引ごと作り直す。
            DocumentVersionLookup.Unconfirmed -> ImageResolution.Failed(NoteImageFailure.NotFound)
        }
    }

    /**
     * 参照1件について「いつ・どの世代だと確かめたか」。
     *
     * **`loadedAt` を代わりに進める案は採らない。** 索引全体が新しくなったことにすると、
     * **まだ確かめていない別の画像まで新鮮**と誤認し、その画像には古い世代を返してしまう。
     */
    private data class CheckedVersion(val version: Long?, val at: Long) {
        fun applyTo(resolution: ImageResolution.Resolved): ImageResolution =
            version?.let { resolution.copy(contentVersion = it) } ?: resolution
    }

    private suspend fun probe(handle: VaultHandle, ref: DocumentRef): DocumentVersionLookup {
        probeCount++
        return try {
            handle.documentVersion(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 落ちたということは確かめられていない。索引を作り直す側へ倒す
            // （消えたドキュメントの照会は、実プロバイダでは例外で返る）。
            DocumentVersionLookup.Unconfirmed
        }
    }

    // 明示的な invalidate() は置かない。**世代照合が既に同じ仕事をしている**ため、
    // Vault切替で呼ぶ相手が存在しなかった（書いてはみたが呼び出し側が現れず、
    // テストのためだけに残る形になった）。2026-07-31 に requestId ガードを
    // 変異検証で冗長と判断して削除したのと同じ判断。再追加するなら、
    // 世代照合では消せない無効化の契機を先に示すこと。

    private suspend fun indexFor(handle: VaultHandle, generation: Long): NoteImageIndex {
        val current = cached
        if (current != null && cachedGeneration == generation) return current
        return rebuild(handle, generation)
    }

    private suspend fun rebuild(handle: VaultHandle, generation: Long): NoteImageIndex {
        scanCount++
        val scan = try {
            handle.collectImages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 走査そのものが落ちたら「不完全な空索引」として扱う。
            // 空の完全な索引にすると、全画像が「Vaultにありません」と断定される。
            null
        }
        val index = if (scan == null) {
            NoteImageIndex.EMPTY_INCOMPLETE
        } else {
            NoteImageIndex.of(scan.entries, isComplete = scan.isComplete)
        }
        cached = index
        cachedGeneration = generation
        loadedAt = now()
        // **これは正しさの保証ではなく、記帳の上限である。** 作り直し前の記帳は、
        // 読まれる時点で必ず期限切れになっている（記帳は最後の作り直し以降にしか
        // 作られないので `at >= loadedAt`。読むのは索引が TTL より古いときだけなので
        // `now - at >= now - loadedAt >= ttl`）。捨てるのは、Vaultを開いたまま
        // 大量の画像を辿ったときに死んだ記帳が積み上がるのを防ぐため。
        checked.clear()
        return index
    }

    /** 「無い／断定できない」で外した状態。作り直して再試行する価値があるのはこの2つだけ。 */
    private fun ImageResolution.isMiss(): Boolean =
        this is ImageResolution.Failed &&
            (reason is NoteImageFailure.NotFound || reason is NoteImageFailure.Unverifiable)

    internal companion object {
        /** `collectAllNotesCached` と同じ 60 秒。走査の重さが同程度なので揃える。 */
        internal const val INDEX_TTL_MS = 60_000L
    }
}
