package com.example.newproject.fakes

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * テスト用の [AiClient]。**これ1本に寄せる。**
 *
 * 統一前は9ファイルに10個の private double が散らばっており、その全部が
 * **`checkAvailability()` から例外を投げられなかった**（[availabilityFailure] に相当する口が
 * どこにも無かった）。`checkAvailability()` が4つの事象を1つへ畳んでいた誤りが
 * 長く見つからなかったのは、**そこを揺らすテストを1本も書けなかったから**である。
 * さらに `downloadModel()` が本物のチャンネルを返すダブルは1つだけで、
 * DL経路も1機能でしか動いていなかった。
 *
 * 散らばりの再発は `AiClientDoubleTest` がソース走査で止める。
 *
 * @param availability [checkAvailability] が返す値。テストの途中で差し替えてよい
 * @param downloads [downloadModel] が流すチャンネル。null なら空の Flow
 * @param onGenerate [generate] の中身。即返し・保留・例外送出をここで決める
 */
class FakeAiClient(
    var availability: AiAvailability = AiAvailability.Ready,
    private val downloads: Channel<DownloadStatus>? = null,
    var onGenerate: suspend FakeAiClient.(prompt: String) -> String = { "" }
) : AiClient {

    /**
     * [checkAvailability] から投げさせる例外。
     *
     * **これは `AiClient` 契約の違反側を突くための口である。** 修正後の `AICoreClient` は
     * 投げない（例外は [AiAvailability.TemporarilyUnavailable] という値になる）が、実装は他にもあり得るので、
     * 呼び出し側が例外に耐えることと、`CancellationException` を素通しすることを固定する。
     */
    var availabilityFailure: (() -> Throwable)? = null

    /** 投げられたプロンプトを順に控える。どの本文で生成が走ったかを見るために要る。 */
    val prompts = mutableListOf<String>()
    val generateCalls: Int get() = prompts.size
    val lastPrompt: String? get() = prompts.lastOrNull()

    /**
     * [downloadModel] が呼ばれた回数。
     *
     * **「呼ばれないこと」を確かめるために要る。** `downloadModel()` を呼んでよいのは
     * `DOWNLOADABLE`（[AiAvailability.NeedsDownload]）のときだけで、DL実行中に呼ぶと
     * 合流できたように見えて**モデルが揃う前に生成へ進む**恐れがある。
     */
    var downloadCalls = 0
        private set

    /** 保留中の生成。[completeAll] で一斉に返す。 */
    private val pending = mutableListOf<CompletableDeferred<String>>()

    override suspend fun checkAvailability(): AiAvailability {
        availabilityFailure?.let { throw it() }
        return availability
    }

    override suspend fun generate(prompt: String): String {
        prompts += prompt
        return onGenerate(prompt)
    }

    override fun downloadModel(): Flow<DownloadStatus> {
        downloadCalls++
        return downloads?.receiveAsFlow() ?: emptyFlow()
    }

    /** 生成を保留し、[completeAll] を呼ぶまで返さないようにする。 */
    fun suspendGenerations() = apply {
        onGenerate = { CompletableDeferred<String>().also { pending += it }.await() }
    }

    /** 保留中の生成をすべて完了させる。 */
    fun completeAll(result: String) {
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { it.complete(result) }
    }

    companion object {
        /** 常に同じ文字列を返す。 */
        fun returning(
            response: String,
            availability: AiAvailability = AiAvailability.Ready
        ): FakeAiClient = FakeAiClient(availability) { response }

        /** 生成が必ず失敗する。 */
        fun failingGeneration(error: () -> Throwable): FakeAiClient =
            FakeAiClient { throw error() }

        /** 生成を保留する。[completeAll] を呼ぶまで返らない。 */
        fun deferred(availability: AiAvailability = AiAvailability.Ready): FakeAiClient =
            FakeAiClient(availability).suspendGenerations()
    }
}
