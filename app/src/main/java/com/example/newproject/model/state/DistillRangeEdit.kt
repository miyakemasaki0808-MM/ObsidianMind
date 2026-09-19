package com.example.newproject.model.state

/** 自由範囲で動かす端。 */
enum class DistillRangeEdge { Start, End }

/**
 * 1境界ずつの微調整。**押せる向きだけを画面へ出すための語彙。**
 *
 * 端とその向きを1つの値にしてあるのは、ボタン4つの並びがそのまま列挙順になるため。
 */
enum class DistillRangeEdgeMove(val edge: DistillRangeEdge, val isOutward: Boolean) {
    ExpandStart(DistillRangeEdge.Start, true),
    ShrinkStart(DistillRangeEdge.Start, false),
    ShrinkEnd(DistillRangeEdge.End, false),
    ExpandEnd(DistillRangeEdge.End, true)
}
