package com.example.newproject.data

import android.net.Uri

/**
 * 選択中Vaultの現在地を1箇所で持つ共有参照。
 *
 * SAFゲートウェイ（[SafReadingTraceDocumentGateway]）は書き込みのたびに現在のVaultを
 * 引き直す必要があるため、`() -> Uri?` を受け取る形になっている。その供給元が
 * [com.example.newproject.NoteViewModel] のプロパティだと、ゲートウェイの生成が
 * ViewModel の内側に固定されて外から差し替えられない。実体をここへ出すことで、
 * 依存の組み立てを ViewModel の外（[NoteViewModelDependencies]）で完結させられる。
 *
 * 更新はメインスレッドだが、読み取りは痕跡保存などIOスレッドからも走る。
 * 可視性を保証しないと、切替がIO側へいつ伝わるか決まらない。
 */
class VaultLocation {
    @Volatile
    var uri: Uri? = null
}
