package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.data.CrystalListing
import com.example.newproject.data.CrystalMaterialPersistence
import com.example.newproject.data.CrystalPersistence
import com.example.newproject.data.CrystalSaveResult
import com.example.newproject.domain.CrystalAnswer
import com.example.newproject.domain.crystalAttemptedToday
import com.example.newproject.domain.crystalFragmentOf
import com.example.newproject.domain.markCrystalAttempted
import com.example.newproject.domain.mergeCrystals
import com.example.newproject.domain.parseCrystalResponse
import com.example.newproject.domain.recordCrystalMaterial
import com.example.newproject.domain.selectCrystalCandidates
import com.example.newproject.domain.shouldAttemptCrystal
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalMaterialLog
import com.example.newproject.model.CrystalSource
import com.example.newproject.model.CrystalStateWriter
import com.example.newproject.model.state.CrystalLogState
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 結晶 — 読んできたノートの要約から共通する筋を1文にして、Vault に溜める
 * （→ `docs/dev/features/reflect_crystal.md`）。
 *
 * ## 他のAI機能と違うところ
 *
 * **生成（ノート単位）と保存（Vault単位）の寿命を分けてある**（→ 判断9）。
 * ノートを切り替えると生成は止まるが、保存に入った結晶は書き終え、同じ Vault なら一覧へ足す。
 * SAF の書き込みは取消を付けても書き終わりうるので、同じ Job にすると
 * 「ファイルはあるのに一覧に出ない」になる。
 *
 * **照合は2つあり、守るものが違う。** requestId は「この生成を保存へ渡してよいか」、
 * Vault の世代は「この保存を今の一覧へ足してよいか」。
 *
 * **失敗を見せない。** 状態は一覧だけで、生成中も失敗も持たない。モデルのDLも始めない（判断8）。
 */
internal class CrystalController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    /** 生成の直前に通す門番。ノートを離れたら `CancellationException` で抜ける。 */
    private val awaitDwell: suspend () -> Unit,
    /** このノートの要約の終わりを待つ。`Success` なら要約、`Error`・`AiUnavailable` なら null。 */
    private val awaitSummary: suspend () -> String?,
    /** このノートの再会カードの照合が終わるのを待つ（→ 判断10）。 */
    private val awaitReunionSettled: suspend () -> Unit,
    private val state: CrystalStateWriter,
    private val store: CrystalPersistence,
    private val materials: CrystalMaterialPersistence,
    private val currentVaultKey: () -> String?,
    private val vaultGeneration: () -> Long,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var generateJob: Job? = null
    private var activeRequestId = 0L
    private var loadJob: Job? = null

    /**
     * この Vault の世代で自分が保存した結晶。**読み込み中に保存が終わっても落とさない**ために、
     * 読み込みの完了時に列挙結果へ重ねる（列挙より後に書いたファイルは列挙に現れない）。
     */
    private val localAppends = mutableListOf<Crystal>()

    /** Vault ごとの走っている保存の数。一覧の読み込みはこれが0になるのを待ってから列挙する。 */
    private val pendingSaves = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 控えの read-modify-write を直列化する。前のノートの試行が書き終える前に次が読むのを防ぐ。 */
    private val materialsMutex = Mutex()

    /** 一覧をまだ読んでいなければ読む。AIを呼ばないので門番を待たない。 */
    fun ensureLogLoaded() {
        if (state.current.log != CrystalLogState.NotLoaded) return
        val vaultKey = currentVaultKey() ?: return
        val generation = vaultGeneration()
        state.update { it.copy(log = CrystalLogState.Loading) }
        loadJob = scope.launch {
            // **その Vault への保存が終わってから列挙する。** A→B→A と選び直した直後でも漏れない。
            pendingSaves.first { (it[vaultKey] ?: 0) == 0 }
            val listing = withContext(ioDispatcher) { store.readAll(vaultKey) }
            if (generation != vaultGeneration()) return@launch
            val next = when (listing) {
                is CrystalListing.Available -> CrystalLogState.Loaded(mergeCrystals(listing.crystals, localAppends))
                // 読めなかったら未読込へ戻し、次に必要になったとき読み直す。
                is CrystalListing.Unavailable -> CrystalLogState.NotLoaded
            }
            state.update { it.copy(log = next) }
        }
    }

    /**
     * ノートを表示し、相対パスが確定した契機で呼ぶ。材料を控え、条件がそろえば1回だけ生成する。
     *
     * 流れと順序の正本は reflect_crystal.md §7。**判定・送信・記録は同じ「渡す候補」で行う**（判断5）。
     */
    fun onNoteShown(vaultRelativePath: String, noteTitle: String) {
        val requestId = ++activeRequestId
        generateJob?.cancel()
        if (vaultRelativePath.isBlank()) return
        state.update { it.copy(notePath = vaultRelativePath) }
        // どのVaultへの試行かは、サスペンドする前のこの時点で決める。
        val vaultKey = currentVaultKey() ?: return
        generateJob = scope.launch {
            awaitDwell()
            val summary = awaitSummary() ?: return@launch
            val fragment = crystalFragmentOf(summary)
            if (fragment.isEmpty()) return@launch
            val log = updateMaterials(vaultKey) {
                recordCrystalMaterial(it, vaultRelativePath, noteTitle, fragment, clock())
            }
            val now = clock()
            // 暦日だけの前段。候補を作る前に抜ける（材料数の判定を代用しない）。
            if (crystalAttemptedToday(log.lastAttemptAt, now, zone)) return@launch
            val prompt = PromptBuilder.buildCrystalPrompt(selectCrystalCandidates(log, vaultRelativePath))
            val sent = prompt.sentMaterials
            if (!shouldAttemptCrystal(sent, log, now, zone)) return@launch

            // **再会カードより先に錠を取らない**（→ 判断10）。材料を控えるのは待たない。
            awaitReunionSettled()
            val availability = try {
                aiClient.checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (availability !is AiAvailability.Ready) return@launch

            val response = try {
                aiClient.generate(prompt.text)
            } catch (e: CancellationException) {
                // **キャンセルは試行に数えない。** ノートを切り替えただけで、その日の1回を失わない。
                throw e
            } catch (_: Exception) {
                null
            }
            // **生成が返ったら数える**（成功・空振り・不正・失敗のいずれも）。返った後に届いた取消で
            // 記録を落とさないよう、記録だけは取り消せない文脈で書く。
            withContext(NonCancellable) {
                updateMaterials(vaultKey) { markCrystalAttempted(it, sent, clock()) }
            }
            val answer = response?.let { parseCrystalResponse(it, prompt.validIds) }
            if (answer !is CrystalAnswer.Chosen) return@launch
            // **ここが生成と保存の境目。** これより前の取消では何も書かない。
            if (!isCurrent(requestId)) return@launch
            val byId = prompt.candidates.associateBy { it.id }
            val crystal = Crystal(
                createdAt = clock(),
                sentence = answer.sentence,
                sources = answer.ids.map { id ->
                    val material = byId.getValue(id).material
                    CrystalSource(material.vaultRelativePath, material.noteTitle, material.fragment)
                },
                fileName = ""
            )
            requestSave(crystal, vaultKey, vaultGeneration())
        }
    }

    /** ノート切替。**生成だけを止める。** 保存に入った結晶と一覧には触らない（→ 判断9）。 */
    fun cancelAndClear() {
        activeRequestId++
        generateJob?.cancel()
        generateJob = null
    }

    /**
     * Vault切替。読み込みと生成を止める。**保存は止めない** —
     * 依頼した Vault へ書き終え、その完了は世代の照合で新しい Vault の一覧へは足さない。
     * 一覧の状態は `withVaultScopedReset()` が未読込へ戻す。
     */
    fun clearVaultScoped() {
        cancelAndClear()
        loadJob?.cancel()
        loadJob = null
        localAppends.clear()
    }

    /**
     * 保存は Vault 単位で、ノートを切り替えても止めない。宛先は依頼の時点で固定し、
     * 一覧へ足すのは成功して、かつ Vault の世代が依頼時と同じときだけ。
     */
    private fun requestSave(crystal: Crystal, vaultKey: String, generation: Long) {
        pendingSaves.update { it + (vaultKey to (it[vaultKey] ?: 0) + 1) }
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { store.append(crystal, vaultKey) }
                if (result is CrystalSaveResult.Saved && generation == vaultGeneration()) {
                    reflect(result.crystal)
                }
            } finally {
                pendingSaves.update { saves ->
                    val remaining = (saves[vaultKey] ?: 1) - 1
                    if (remaining <= 0) saves - vaultKey else saves + (vaultKey to remaining)
                }
            }
        }
    }

    /** 保存できた結晶を足す。読込済みならその場で、読込中なら完了時に重ねる。未読込なら次の読み込みが拾う。 */
    private fun reflect(saved: Crystal) {
        localAppends += saved
        state.update { slice ->
            val log = slice.log
            if (log is CrystalLogState.Loaded) {
                slice.copy(log = CrystalLogState.Loaded(mergeCrystals(log.crystals, listOf(saved))))
            } else {
                slice
            }
        }
    }

    /**
     * 控えを読み、変えて、書く。**書けなくても試行は進める** — 控えは失っても読めば溜まり直す。
     */
    private suspend fun updateMaterials(
        vaultKey: String,
        transform: (CrystalMaterialLog) -> CrystalMaterialLog
    ): CrystalMaterialLog = materialsMutex.withLock {
        withContext(ioDispatcher) {
            val next = transform(materials.load(vaultKey))
            try {
                materials.save(vaultKey, next)
            } catch (_: Exception) {
                // 端末内のファイルに書けなかった。次に開いたとき、材料を控え直す。
            }
            next
        }
    }

    private fun isCurrent(requestId: Long) = requestId == activeRequestId
}
