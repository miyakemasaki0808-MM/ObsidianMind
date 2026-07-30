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
        // 依存更新系は「いつ・どこまで上げるか」の方針が未定。方針を決めるまでは
        // ビルドのたびに出しても行動につながらないので黙らせる。方針が決まったら外す。
        // （CLAUDE.md の「依存ライブラリを一括更新しない」に対応する枠でもある）
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency")
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

    // instrumentation テストの土台。テスト本体はまだ無いが、依存とRunnerが揃って
    // いないと「書こうとした時点で環境構築から始まる」状態が続くため先に置く。
    // SAF走査・端末AI・Compose Navigation・画面回転は素のJVMでは覆えない。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
