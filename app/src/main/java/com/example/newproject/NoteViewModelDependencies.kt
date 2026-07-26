package com.example.newproject

import android.app.Application
import com.example.newproject.ai.AICoreClient
import com.example.newproject.ai.AiClient
import com.example.newproject.data.AppPreferences
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryStore
import com.example.newproject.data.DistillWriteRepository
import com.example.newproject.data.HistoryStore
import com.example.newproject.data.NoteHistoryStore
import com.example.newproject.data.NoteRepository
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.data.SafDistillDocumentGateway
import com.example.newproject.data.SafReadingTraceDocumentGateway
import com.example.newproject.data.SharedAppPreferences
import com.example.newproject.data.VaultLocation
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * [NoteViewModel] が使う依存の一式。
 *
 * 以前は ViewModel のプロパティ初期化子で直接 `new` していたため差し替え口が無く、
 * ViewModel を通るテストが1件も書けなかった。生成をここへ出したことで、
 * 組み立て（Android依存）と調停（純Kotlin）が別々に読めるようになる。
 *
 * DIライブラリは入れない。差し替えたいのは1クラスの依存だけで、
 * グラフを解決してもらう必要が無いため。
 */
internal class NoteViewModelDependencies(
    val preferences: AppPreferences,
    val history: HistoryStore,
    val repository: NoteRepository,
    val aiClient: AiClient,
    val summarizeUseCase: SummarizeUseCase,
    val relatedNotesUseCase: RelatedNotesUseCase,
    val searchPickerUseCase: SearchPickerUseCase,
    val distillPersistence: DistillPersistence,
    val readingTracePersistence: ReadingTracePersistence,
    /**
     * Vaultの現在地。ViewModel と痕跡のSAFゲートウェイが**同じ実体**を見る必要がある
     * （書き込み時点のVaultを引き直すため）。共有先をここで固定する。
     */
    val vaultLocation: VaultLocation,
    /**
     * ノート単位ジョブを載せるスコープ。null なら `viewModelScope` を使う。
     * 既定を null にしているのは、`viewModelScope` が ViewModel の生成後にしか
     * 参照できず、依存の組み立て時点では決められないため。
     */
    val scope: CoroutineScope? = null
) {
    companion object {
        /** 本番の組み立て。1つの [AiClient] を全機能で共有する（生成はその中でMutex直列化される）。 */
        fun default(application: Application): NoteViewModelDependencies {
            val prefs = application.getSharedPreferences(
                SharedAppPreferences.PREFS_NAME,
                Application.MODE_PRIVATE
            )
            val aiClient: AiClient = AICoreClient()
            val vaultLocation = VaultLocation()
            return NoteViewModelDependencies(
                preferences = SharedAppPreferences(prefs),
                history = NoteHistoryStore(prefs),
                repository = NoteRepository(),
                aiClient = aiClient,
                summarizeUseCase = SummarizeUseCase(aiClient),
                relatedNotesUseCase = RelatedNotesUseCase(aiClient),
                searchPickerUseCase = SearchPickerUseCase(aiClient),
                distillPersistence = DistillWriteRepository(
                    gateway = SafDistillDocumentGateway(application.contentResolver),
                    recoveryStore = DistillRecoveryStore(application.noBackupFilesDir),
                    cacheDirectory = File(application.cacheDir, "distill")
                ),
                readingTracePersistence = ReadingTraceStore(
                    SafReadingTraceDocumentGateway(application.contentResolver) { vaultLocation.uri }
                ),
                vaultLocation = vaultLocation
            )
        }
    }
}
