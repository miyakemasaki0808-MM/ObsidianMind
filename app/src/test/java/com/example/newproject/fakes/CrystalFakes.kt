package com.example.newproject.fakes

import com.example.newproject.data.CrystalListing
import com.example.newproject.data.CrystalMaterialPersistence
import com.example.newproject.data.CrystalPersistence
import com.example.newproject.data.CrystalSaveResult
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalMaterialLog

/**
 * 結晶の置き場の偽物。Vault ごとにメモリへ持つ。
 *
 * [beforeAppend] は保存の**書き込みの開始後・完了前**に呼ばれる。実スレッドのテストは
 * ここでラッチを待たせ、書き込み中に切り替える交錯を作る。
 */
internal class InMemoryCrystalStore : CrystalPersistence {
    private val byVault = mutableMapOf<String, MutableList<Crystal>>()
    private var nextName = 0

    @Volatile var failAppend = false
    @Volatile var unavailable = false
    @Volatile var beforeAppend: () -> Unit = {}

    /** 一覧の読み込みで、**列挙した直後・返す前**に呼ばれる。読み込み中に保存が終わる交錯を作る。 */
    @Volatile var afterSnapshot: () -> Unit = {}

    /** 一覧の列挙が呼ばれた回数。**保存が終わる前に列挙していないか**を見るために要る。 */
    @Volatile var readAllCalls = 0
        private set

    /** 保存が呼ばれた回数（成功・失敗を問わない）。 */
    @Volatile var appendCalls = 0
        private set

    @Synchronized
    fun stored(vaultKey: String): List<Crystal> = byVault[vaultKey].orEmpty().toList()

    @Synchronized
    fun put(vaultKey: String, crystal: Crystal) {
        byVault.getOrPut(vaultKey) { mutableListOf() } += crystal
    }

    override fun readAll(vaultKey: String): CrystalListing {
        readAllCalls++
        val snapshot = synchronized(this) {
            if (unavailable) return CrystalListing.Unavailable("読めない")
            byVault[vaultKey].orEmpty().toList()
        }
        afterSnapshot()
        return CrystalListing.Available(snapshot, unreadable = 0)
    }

    override fun append(crystal: Crystal, vaultKey: String): CrystalSaveResult {
        appendCalls++
        beforeAppend()
        if (failAppend) return CrystalSaveResult.Failure("書けない")
        return synchronized(this) {
            val saved = crystal.copy(fileName = "crystal-${nextName++}.json")
            byVault.getOrPut(vaultKey) { mutableListOf() } += saved
            CrystalSaveResult.Saved(saved)
        }
    }
}

/** 材料の控えの偽物。Vault ごとにメモリへ持つ。 */
internal class InMemoryCrystalMaterials : CrystalMaterialPersistence {
    private val byVault = mutableMapOf<String, CrystalMaterialLog>()

    @Synchronized
    override fun load(vaultKey: String): CrystalMaterialLog = byVault[vaultKey] ?: CrystalMaterialLog.EMPTY

    @Synchronized
    override fun save(vaultKey: String, log: CrystalMaterialLog) {
        byVault[vaultKey] = log
    }
}
