package com.example.newproject.data

import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageEntry
import com.example.newproject.domain.image.NoteImageIndex
import com.example.newproject.domain.image.resolveImage
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
 * ## 不完全な索引もキャッシュする
 *
 * 設計書の初版は「不完全な索引はキャッシュしない」だったが、**段階2で
 * [ImageResolution.Unverifiable] を型として分けた時点で不要になった。**
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

    /** 走査した回数。**歯止めが効いていることの検証にだけ使う。** */
    internal var scanCount = 0
        private set

    /**
     * 要求を解決する。索引が無ければ作り、失敗して索引が古ければ1回だけ作り直す。
     *
     * Vault未選択なら [ImageResolution.Unverifiable]（「無い」とは言えないため）。
     */
    internal suspend fun resolve(request: ImageRequest): ImageResolution = mutex.withLock {
        val handle = vault.current() ?: return ImageResolution.Unverifiable
        val generation = vaultGeneration()
        val index = indexFor(handle, generation)
        val first = resolveImage(request, index)
        if (!first.isMiss() || now() - loadedAt < ttlMillis) return first
        // 古い索引で外したときだけ作り直す。作り直した時刻を控えるので、
        // 壊れたリンクが TTL 未満のあいだ再走査を誘発し続けることはない。
        return resolveImage(request, rebuild(handle, generation))
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
        return index
    }

    private fun ImageResolution.isMiss(): Boolean =
        this is ImageResolution.NotFound || this is ImageResolution.Unverifiable

    internal companion object {
        /** `collectAllNotesCached` と同じ 60 秒。走査の重さが同程度なので揃える。 */
        internal const val INDEX_TTL_MS = 60_000L
    }
}
