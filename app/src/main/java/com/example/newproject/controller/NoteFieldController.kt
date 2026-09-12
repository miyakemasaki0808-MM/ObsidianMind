package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.domain.NoteFieldAnswer
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.data.NoteFieldStore
import com.example.newproject.domain.noteFieldPathKey
import com.example.newproject.domain.noteFieldHint
import com.example.newproject.domain.noteFieldInputVersion
import com.example.newproject.domain.parseNoteFieldAnswer
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteFieldStateWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ノートの分野をAIに判定させる（→ `docs/dev/features/note_field_color.md` 判断6・判断8）。
 *
 * ## 他のAI機能と違うところ
 *
 * **失敗をユーザーへ一切見せない。** 状態も持たない — 結果は索引Aの色として出るだけで、
 * 進捗も失敗も画面に現れない。`ReadingTrace` と同じベストエフォート側だが、
 * あちらより更に静かで、**「判定中」の表示すら持たない**（誰も見ないので状態を作らない）。
 *
 * **モデルDLを自動で始めない。** 要約は始めるが、こちらは**色が付くのが少し遅れるだけ**なので、
 * 待ってまで通す価値が無い。DL中・未DLのときは黙って諦め、次に開いたときに試す。
 *
 * ## 何を止め、何を止めないか
 *
 * [cancelAndClear] は**ジョブだけを止め、索引Aには触らない。** 索引AはVault単位なので、
 * ノートを切り替えただけで色が消えるのは誤りである（他のControllerの `cancelAndClear` が
 * 状態を `Idle` へ戻すのとはここが違う）。
 */
class NoteFieldController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: NoteFieldStateWriter,
    /**
     * 抜粋の組み立てを逃がす先。**純粋だが軽くない** — 最大1MBの本文に比例するので、
     * Main で走らせるとノートを開いた瞬間に固まる（→ `docs/dev/lessons.md` L13）。
     * `NoteExcerptThreadingTest` がこの形を走査で固定している。
     */
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 確定の永続。**保存はこの機能の前提条件である**（→ 判断3）。 */
    private val store: NoteFieldStore? = null,
    /** いまのVaultの名前空間。**別Vaultの結果と混ざらないため**（→ 判断9）。 */
    private val vaultKey: () -> String? = { null }
) {
    private var job: Job? = null
    private var activeRequestId = 0L

    /**
     * 索引B（入力指紋 → AIが返した答え）。**いまはメモリだけ**で、永続はまだ無い。
     *
     * 当たれば **Nano を呼ばずに確定を復元できる** — 同じ本文を別の場所で開き直したとき、
     * 改名したときなど、**入力が同じなら結果も同じ**だからである。
     *
     * **値の null は「該当なし」**（正常な確定）。読めなかった応答はそもそもここへ入らない。
     */
    private val answersByInputVersion = LinkedHashMap<String, NoteField?>()

    /**
     * 索引Aの**復元材料**（鍵は `noteFieldPathKey`）。永続から読んだ確定の写しである。
     *
     * **永続そのものとは別物である**（→ 判断18）。走査は本文を読まないので入力版を照合できず、
     * 永続をそのまま補完材料にすると、**開いて失効を確認した確定まで走査が戻してしまう。**
     * ここから外せば戻らず、永続には残るので**入力を元へ戻したときは索引Bが当てる。**
     */
    private val restorableByPath = LinkedHashMap<String, NoteFieldClassification.Confirmed>()

    /**
     * 連続失敗の抑制（→ 判断8）。**アプリの起動単位で、永続しない。**
     *
     * 永続させると回復の契機が消え、前回の「永久確定」を**「永久停止」という別の形で残す**。
     * 起動のたびに1回は試すので、端末が本当に駄目なときの無駄は毎起動1回で止まる。
     */
    private val failuresByInputVersion = HashMap<String, Int>()

    /** 直前に観測した可用性が `Ready` だったか。**回復したら抑制を解く**ための材料。 */
    private var lastSeenReady = true

    /**
     * このノートの分野を判定する。**判定済みなら何もしない。**
     *
     * 呼ぶのはノートを表示した経路だけ。冊子からは呼ばない（→ 判断2）。
     */
    fun classify(ref: DocumentRef, content: String, vaultRelativePath: String) {
        val requestId = ++activeRequestId
        job?.cancel()
        job = scope.launch {
            val hint = noteFieldHint(vaultRelativePath)
            // **抜粋の組み立ては Main の外で行う**（→ excerptDispatcher）。
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(content, NoteExcerptLimits.FIELD)
            }
            // **プロンプトへ載せるタイトルと同じ値を指紋へ渡す。**
            // ここが食い違うと、指紋が「実際にAIへ渡したもの」を表さなくなる。
            val title = vaultRelativePath.substringAfterLast('/')
            val inputVersion = noteFieldInputVersion(title, excerpt, hint)
            // 永続と復元材料の鍵。**作れないことがある**（さがす経由で相対パスが取れない場合）。
            val pathKey = vaultRelativePath.takeIf { it.isNotBlank() }?.let(::noteFieldPathKey)

            // **「判定済み」ではなく「現在の入力版の確定」で見る。**
            // 本文・ヒント・語彙のどれかが変われば版が変わり、その確定はもう有効でない。
            val existing = state.current[ref]
            if (existing is NoteFieldClassification.Confirmed && existing.inputVersion == inputVersion) {
                // **有効な確定。索引Aへは書かない** — 落として索引Bから戻すと結果は同じだが、
                // 書けば画面は再描画され、ノートを開くたびに色が一度ヒントへ点滅する。
                return@launch
            }
            // **失効を見つけた時点で落とす**（→ 判断8・レビュー P2-4）。
            // ここを飛ばすと、この後 AI が使えなかったときに**古い色とラベルが残る** —
            // 「失敗したらヒントへ縮退する」と書いた仕様と食い違う。
            // **落とす先はいまのヒント**で、ヒントが無ければ未判定（無彩色）にする。
            if (existing is NoteFieldClassification.Confirmed && isCurrent(requestId)) {
                demote(ref, hint)
            }

            // 索引Bに当たれば Nano を呼ばない。**改名や複製で同じ入力になったときの自己修復**でもある。
            // **`containsKey` で見る** — 値の null は「該当なし」という正常な確定であって、不在ではない。
            if (answersByInputVersion.containsKey(inputVersion)) {
                if (isCurrent(requestId)) {
                    apply(ref, inputVersion, answersByInputVersion[inputVersion], pathKey)
                }
                return@launch
            }
            // **索引Bが外れた ＝ このノートの保存済みの答えは、いまの入力のものではない。**
            // 復元材料は読み込んだ時点で索引Bへも入れている（[restorePersisted]）ので、
            // 当たらないなら別の入力版の答えだと分かる。**索引Aを降格させるだけでは足りない** —
            // 次の走査が復元材料から同じ確定を補完し、**失効を確認済みの色が戻る**
            // （→ 判断18・レビュー P2-1）。
            //
            // **要求が最新かは見ない。** 落としているのは「このパスの保存済みの答えは
            // いまの本文のものではない」という事実で、**どのノートを表示しているかに依らない。**
            dropRestorable(pathKey)

            // **可用性の確認を抑制の判定より先に置く。**
            // 逆にすると、一度抑制へ入った時点で可用性を二度と観測できなくなり、
            // **解除条件が到達不能になる** — 「永久確定」を「永久停止」へ置き換えただけになる。
            //
            // **生の `FeatureStatus` では判定しない。** 対応端末の `UNAVAILABLE` は
            // `TemporarilyUnavailable` へ写るので、恒久非対応と区別できない（→ 判断8）。
            val availability = try {
                aiClient.checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 可用性の問い合わせ自体が落ちた。**失敗には数えない** — 生成を試していない。
                return@launch
            }
            if (availability is AiAvailability.Ready && !lastSeenReady) {
                // **回復したら抑制を解く。**
                failuresByInputVersion.clear()
            }
            lastSeenReady = availability is AiAvailability.Ready
            // 未DL・DL中は「まだ失敗していない」。**数えない**し、DLも始めない。
            if (availability !is AiAvailability.Ready) return@launch

            if (failuresByInputVersion.getOrDefault(inputVersion, 0) >= MAX_CONSECUTIVE_FAILURES) {
                return@launch
            }

            val prompt = PromptBuilder.buildNoteFieldPrompt(
                title = title,
                excerpt = excerpt,
                hint = hint
            )
            val answer = try {
                parseNoteFieldAnswer(aiClient.generate(prompt))
            } catch (e: CancellationException) {
                // **キャンセルは失敗ではない。** 数えず、何も書かない（→ 判断8）。
                throw e
            } catch (_: Exception) {
                recordFailure(inputVersion)
                return@launch
            }
            if (!isCurrent(requestId)) return@launch
            // 読めなかったものは保存しない。**次に開いたときやり直せるよう、失敗として数える**
            // （数えないと、散文しか返さないモデルで毎回呼び続けることになる）。
            val field = when (answer) {
                NoteFieldAnswer.Invalid -> {
                    recordFailure(inputVersion)
                    return@launch
                }
                // **「該当なし」は正常な確定。** 無彩色にするが、未判定とは違って再判定しない。
                NoteFieldAnswer.NoneOfThem -> null
                is NoteFieldAnswer.Chosen -> answer.field
            }
            answersByInputVersion[inputVersion] = field
            failuresByInputVersion.remove(inputVersion)
            apply(ref, inputVersion, field, pathKey)
        }
    }

    /**
     * ノート・Vault切替でジョブを止める。**索引Aには触らない。**
     *
     * 触ると、ノートを開き直しただけで冊子の色が消える。索引AはVault単位である。
     */
    fun cancelAndClear() {
        activeRequestId++
        job?.cancel()
        job = null
    }

    /**
     * 永続から読んだ確定を受け取る（→ 判断10・判断17・判断18）。
     *
     * **役目は2つある。**
     *
     * 1. **索引Bを作り直す。** 確定は入力版を持つので、読み込んだ時点で「入力指紋 → 分野」が揃う。
     *    **索引Bを永続しない判断は、これがあって初めて成立する** — 呼ばないと、移動・複製した先で
     *    保存済みの結果を再利用できず、再起動のたびに生成し直す。
     * 2. **索引Aの復元材料として持つ**（[restorableFields]）。走査は本文を読まないので、
     *    補完してよいかを判断できるのはこちらだけである。
     *
     * **呼ぶのはVaultを読み込む時点だけ**（起動復元と明示選択）で、そのとき両方とも空である
     * （[clearVaultScoped] の直後、あるいは生成前）。**「既にある要素を守る」ガードは置かない** —
     * 空に対して復元するので発火せず、**落ちるテストが書けないガードは足さない**
     * （→ `docs/dev/lessons.md` L11）。セッションの途中で呼ぶようになったら、そのとき考える。
     *
     * @param confirmed 鍵は `noteFieldPathKey`。
     */
    fun restorePersisted(confirmed: Map<String, NoteFieldClassification.Confirmed>) {
        restorableByPath.putAll(confirmed)
        for (entry in confirmed.values) {
            answersByInputVersion[entry.inputVersion] = entry.field
        }
    }

    /**
     * 走査が索引Aを補完するときの材料（→ 判断18）。
     *
     * **永続そのものを渡さない。** 開いて失効を確認した確定はここから外れている。
     */
    fun restorableFields(): Map<String, NoteFieldClassification.Confirmed> = restorableByPath

    /** Vault切替。**索引B・復元材料・抑制を捨てる** — 別Vaultの結果と失敗回数を持ち越さない。 */
    fun clearVaultScoped() {
        cancelAndClear()
        answersByInputVersion.clear()
        restorableByPath.clear()
        failuresByInputVersion.clear()
    }

    /**
     * 確定を索引Aへ書く。
     *
     * **個別の確定は常に置き換える**（→ 判断16）。一括復元だけが「補完のみ」で、
     * こちらは今まさに得た最新の結果なので、古い確定が載っていても上書きしてよい。
     */
    private fun apply(
        ref: DocumentRef,
        inputVersion: String,
        /** null は「該当なし」。**読めなかった応答はここへ来ない** — 呼び出し側で既に分岐している。 */
        field: NoteField?,
        pathKey: String?
    ) {
        val confirmed = NoteFieldClassification.Confirmed(field, inputVersion)
        state.update { it + (ref to confirmed) }
        // **永続するのは確定だけ**（暫定は走査で作り直せる → 判断14）。
        // 鍵が作れない経路（さがす経由で相対パスが取れなかった場合）は永続しない —
        // 無理に保存すると、次回どのノートの結果か分からなくなる。
        val key = vaultKey() ?: return
        if (pathKey == null) return
        store?.save(key, pathKey, confirmed)
    }

    /**
     * 失効した確定を、いまのヒントの暫定へ落とす（無ければ未判定＝索引から外す）。
     *
     * **永続には触らない。** 永続の確定は「その入力版での答え」として正しく、
     * 消すと**入力を元へ戻したときに再利用できなくなる**。
     * 走査から戻ってこないようにするのは [dropRestorable] の役目である。
     */
    private fun demote(ref: DocumentRef, hint: NoteField?) {
        state.update { current ->
            if (hint == null) current - ref else current + (ref to NoteFieldClassification.Provisional(hint))
        }
    }

    /**
     * 失効を確認した確定を、索引Aの**復元材料**から外す（→ 判断18・レビュー P2-1）。
     *
     * **永続は消さない。** 消すと入力を元へ戻したときに再利用できなくなる。
     * ここで落とすのは「**いまの本文に対して表示してよい確定ではない**」という事実だけで、
     * 「その入力版での答え」としての正しさは索引Bが引き継いでいる。
     *
     * **入力版の再照合はしない。** 呼ぶのは索引Bが外れた後だけで、
     * 復元材料は必ず索引Bにも入っているのだから、外れた時点で版が違うと決まっている。
     * ここで版を比べ直しても**落ちるテストが書けない**（実際に変異させて確かめた
     * → `docs/dev/lessons.md` L11）。
     */
    private fun dropRestorable(pathKey: String?) {
        if (pathKey == null) return
        restorableByPath.remove(pathKey)
    }

    private fun recordFailure(inputVersion: String) {
        failuresByInputVersion[inputVersion] = failuresByInputVersion.getOrDefault(inputVersion, 0) + 1
    }

    private fun isCurrent(requestId: Long) = requestId == activeRequestId

    private companion object {
        /**
         * 同じ入力版で何回まで試すか。
         *
         * **小さくてよい。** 失敗しても暫定の色が残るだけで、ユーザーには何も見えない。
         * 起動のたびに解けるので、取りこぼしは次の起動で拾える。
         */
        const val MAX_CONSECUTIVE_FAILURES = 2
    }
}
