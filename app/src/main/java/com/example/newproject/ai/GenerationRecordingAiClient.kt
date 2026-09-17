package com.example.newproject.ai

/**
 * 生成を呼ぶたびに [onGenerate] を呼ぶ [AiClient]。**要約の生成回数を実機で数えるためにある**
 * （→ `docs/dev/features/note_summary.md` §10）。
 *
 * 保存済みの要約が当たったかどうかは、保存のファイル名や更新時刻では判別できない。
 * 同じプロンプトで生成し直しても、同じ名前のファイルが置き換わって更新時刻が進むだけだからである。
 * 判別できるのは「その要約のために生成を呼んだか」だけなので、生成の入口で数える。
 * 呼んだ時点で数えるので、失敗した生成も1回に数える。
 *
 * **`SummarizeUseCase` にだけ渡す。** 他の機能へ渡すと分野判定などの生成まで数え込み、
 * 実機ケースの「初めて開いたノートは1回」が崩れる。[onGenerate] には本文もプロンプトも渡さない。
 */
internal class GenerationRecordingAiClient(
    private val delegate: AiClient,
    private val onGenerate: () -> Unit
) : AiClient by delegate {

    override suspend fun generate(prompt: String): String {
        onGenerate()
        return delegate.generate(prompt)
    }
}
