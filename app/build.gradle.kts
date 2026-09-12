import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Lint の `warningsAsErrors` は Android Lint にしか効かない。Kotlin コンパイラの
        // 警告はそちらでは止まらないので、同じ歯止めをこちらにも置く。
        // これが無いと「警告0にした」と言えるのは Lint に限った話になる。
        allWarningsAsErrors = true
    }
}

android {
    // `namespace`（＝生成されるRクラスとソースのパッケージ）は据え置く。
    // `applicationId` と揃える必要は無く、揃えに行くと全ソースのパッケージ宣言と
    // import を書き換えることになる。見返りは名前の一致だけなので取らない。
    namespace = "com.example.newproject"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Play Storeへ公開すると二度と変更できない。未公開の今のうちに確定させる。
        applicationId = "com.vigilith.ai"
        minSdk = 26
        targetSdk {
            version = release(36)
        }
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8はまだ有効にしない。Compose と ML Kit GenAI の keep ルールを確認しないと
            // オンデバイスAIの呼び出しが実機でだけ落ちる可能性があり、確認には release
            // ビルドでの実機検証が要る。まず「構成が存在する」状態を作るのが目的。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    lint {
        // 依存更新系は**ゲートに載せず、報告だけさせる**（hint 扱い）。
        //
        // 他のLint警告は「自分のコードに欠陥がある」ので直せば消えるが、これらは
        // 上流が新版を出しただけで指摘が生える。`warningsAsErrors` と組み合わせると、
        // こちらが1行も触っていないのに lintDebug（とCI）が失敗し、赤から
        // 「直すべきもの」という意味が失われる。追随の強制は「依存を一括更新しない。
        // 機能単位で上げ、実機確認を伴う」とも衝突する。
        //
        // かといって `disable` にすると指摘ごと消え、更新を誰も催促しなくなる。
        // `informational` は両方を避ける — 実測で「0 errors, 0 warnings, 12 hints」／
        // BUILD SUCCESSFUL となり、12件はレポートに残る（`--offline` でも同じ）。
        //
        // **件数はレポートを開くだけでは確かめられない。** `lintAnalyzeDebug` が
        // UP-TO-DATE だと前回のXMLがそのまま残るので、解析を走らせずに読むと
        // **実際とは違う件数を読むことがある**（2026-09-12 にこれで0件と誤認した）。
        // 数えるときは `--rerun-tasks` を付け、タスクが実行されたことを確認する。
        // 棚卸しの手順は docs/dev/system/dependency_policy.md
        informational += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency")
        // `OldTargetApi` はコードではなく実行環境（Lintが把握する「最新API」の定義）に
        // 依存する。ローカルでは compileSdk 36.1 / targetSdk 36 で警告ゼロだったが、
        // CI（GitHub Actions）のSDKコンポーネントはより新しく、同じ組み合わせを
        // 「最新でない」と判定して Error にした。targetSdk の新DSLは
        // `minorApiLevel` を受け付けず compileSdk 側とマイナーAPIレベルを
        // 揃えられないため、値ではなく判定自体を止める。
        disable += setOf("OldTargetApi")
        // 残りは0件にしたので、増えたら失敗させる。件数を数えて見張るより、
        // 増やせないようにするほうが確実（baselineは「見なかったことにする」側なので使わない）。
        warningsAsErrors = true
        abortOnError = true
    }
}

/**
 * **マージ後マニフェストの `uses-permission` を数え、期待と違えばビルドを落とす。**
 *
 * ## なぜ要るか
 *
 * このアプリはネットワーク権限を宣言しない（→ docs/dev/decisions/ADR-0002-on-device-ai-only.md）。
 * だが **推移依存はマニフェストを持っている**。実際 ML Kit GenAI が引く
 * `transport-backend-cct` が `INTERNET` と `ACCESS_NETWORK_STATE` を持ち込み、
 * **ソースには無いのに成果物には入っている**状態が続いていた。
 * `INTERNET` は通常権限なのでインストール時に黙って付与され、誰も気づかない。
 *
 * **危ないのは今回混ざった2件そのものより、混ざったことに気づく手段が無かったこと**である。
 * 依存を1つ足すだけで同じことが起きるので、**人の注意ではなくビルドで数える**。
 *
 * ## なぜ JVM テストではなく Gradle タスクか
 *
 * 見たいのは**ビルド出力**（マージ後マニフェスト）であって、ソースではない。
 * JVM テストからは `app/build/intermediates/...` を入力として宣言できず、
 * `testDebugUnitTest` 単独ではそのファイルが生成すらされない。
 *
 * ## 増減の両方を見る
 *
 * 「`INTERNET` が無いこと」だけを見ると、**次に別の権限を持ち込む依存**を素通しする。
 * 逆に期待側が消えたこと（AICore への接続に要る権限を `tools:node="remove"` で
 * 巻き込む事故）も落としたいので、**集合として双方向で突き合わせる**。
 *
 * 対象はアプリ本体のマージ後マニフェストだけで、`androidTest` 側は見ない。
 * 端末に入るのはこちらであり、テスト依存の権限は成果物に影響しない。
 */
abstract class VerifyManifestPermissions : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val expected: SetProperty<String>

    @TaskAction
    fun verify() {
        val manifestFile = mergedManifest.get().asFile
        val declared = USES_PERMISSION.findAll(manifestFile.readText())
            .map { it.groupValues[1] }
            .toSet()
        val expectedPermissions = expected.get()
        val unexpected = (declared - expectedPermissions).sorted()
        val missing = (expectedPermissions - declared).sorted()
        if (unexpected.isEmpty() && missing.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("マージ後マニフェストの権限が期待と違います: $manifestFile")
                if (unexpected.isNotEmpty()) {
                    appendLine("  増えた: ${unexpected.joinToString()}")
                    appendLine(
                        "  出どころは app/build/outputs/logs/manifest-merger-*-report.txt の " +
                            "`ADDED from` 行で分かります。意図しない持ち込みなら " +
                            "app/src/main/AndroidManifest.xml へ tools:node=\"remove\" を足してください。"
                    )
                }
                if (missing.isNotEmpty()) {
                    appendLine("  消えた: ${missing.joinToString()}")
                    appendLine("  AICore への接続に要る権限まで除いていないか確認してください。")
                }
                append(
                    "期待値そのものを動かすなら、app/build.gradle.kts の expected と " +
                        "docs/dev/system/dependency_policy.md（判断5）を同時に直すこと。"
                )
            }
        )
    }

    private companion object {
        val USES_PERMISSION = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
    }
}

// **`processXMainManifest` の後始末として走らせる。** こうしておくと
// マニフェストをマージするビルドは必ず検査を通る — `lintDebug` も `assembleDebug*` も
// その途中で `processDebugMainManifest` を実行するので、debug 側は素通りできない。
//
// **release はこれだけでは守れない。** CIが release をビルドしないので、
// 後始末の契機そのものが来ない。ci.yml が `verifyReleaseManifestPermissions` を
// 名指しで呼ぶのはそのためである（**未署名で実機に入れられない release 成果物の権限を、
// 実機に入れずに保証する**ための唯一の経路）。
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyManifestPermissions>(
            "verify${variantName}ManifestPermissions"
        ) {
            description = "マージ後マニフェストに想定外の権限が無いことを確かめる"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            // AICore への接続と、Compose/AndroidX が使う自己定義の受信権限だけ。
            // applicationId から組み立てるのは、後者が `<applicationId>.` で始まるため。
            expected.set(
                variant.applicationId.map { applicationId ->
                    setOf(
                        "com.google.android.apps.aicore.service.BIND_SERVICE",
                        "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                    )
                }
            )
        }
        // **`named` は使えない。** `onVariants` はマニフェストのマージタスクが
        // 登録されるより前に走るので、名前で引くと「タスクが無い」で構成に失敗する。
        // `matching` なら後から登録されたものにも当たる。
        tasks.matching { it.name == "process${variantName}MainManifest" }
            .configureEach { finalizedBy(verify) }
    }
}

// `ReviewFindingsLedgerTest` は docs/ を読む。Gradle は既定でそれを入力と見なさないため、
// **文書だけを直したときにテストが UP-TO-DATE で飛ぶ**（＝受付漏れの検査が発火しない）。
// 実際、導入時に変異検証が1件も落ちずこの穴が判明した。入力として明示する。
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("docs"))
        .withPropertyName("docsForLedgerTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // androidTest のソースは JVM テストのコンパイル対象ではないため、
    // 明示しないと **androidTest だけを直したときに UP-TO-DATE で飛ぶ**
    // （InstrumentationTestShapeTest が一度も走らない）。docs と同じ理由。
    inputs.dir(layout.projectDirectory.dir("src/androidTest"))
        .withPropertyName("androidTestSourcesForShapeTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    // これまで lifecycle 経由で入っていた版（1.13.1）をそのまま明示する。
    // `SharedPreferences.edit {}` と `String.toUri()` を直接使うため、
    // 推移的依存に頼ったままにしない。版は変えていない。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.window:window:1.3.0")
    // AICore (Gemini Nano on-device) — ML Kit GenAI Prompt API
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // android.jar の org.json は unit test では Stub!（呼ぶと例外）になるため、
    // 実装を test スコープだけに載せる。ReadingTrace のサイドカーJSONを
    // 素のJVMテストで検証するのに必要。
    testImplementation("org.json:json:20240303")

    // instrumentation テストの土台。Runner／Context と Compose 描画のスモークテストを
    // 分け、環境とUI同期のどちらが壊れたかを個別に観測できるようにしている。
    // SAF走査・端末AI・Compose Navigation・画面回転は素のJVMでは覆えない。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
