package com.example.newproject.model.state

import com.example.newproject.model.Crystal

/**
 * この Vault の結晶の一覧。**Vault単位**で、ノートを切り替えても消さない
 * （→ `docs/dev/features/reflect_crystal.md` §6）。
 *
 * 生成中や失敗の状態は持たない — 結晶は自動で走り、失敗は黙って諦める（判断8）。
 */
sealed interface CrystalLogState {
    data object NotLoaded : CrystalLogState
    data object Loading : CrystalLogState

    /** [crystals] は新しい順。 */
    data class Loaded(val crystals: List<Crystal>) : CrystalLogState
}
