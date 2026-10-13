plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
val qqMailAuthCode = rootProject.file("docs/keep/QQmail.txt").readText().trim()
require(qqMailAuthCode.matches(Regex("[A-Za-z0-9]{16}"))) { "QQ 邮箱授权码文件须为 16 位字母数字" }
android {
    namespace = "com.niuma.checkin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.niuma.ding"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "QQ_MAIL_AUTH_CODE", "\"$qqMailAuthCode\"")
    }
    packaging { resources.merges += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md") }
    lint { abortOnError = true }

    applicationVariants.all {
        if (buildType.name == "debug") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "niuma-ding-debug-v${defaultConfig.versionName}-${defaultConfig.versionCode}.apk"
            }
        }
    }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(project(":core"))
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
