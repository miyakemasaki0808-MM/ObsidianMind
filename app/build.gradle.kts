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
        // 棚卸しの手順は docs/dev/design/dependency_policy.md
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
