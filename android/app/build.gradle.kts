import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signingProperties = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String) = System.getenv(env) ?: signingProperties.getProperty(key)
val releaseStore = signingValue("storeFile", "POCKET_KEYSTORE")
val huaweiAppId = providers.gradleProperty("pocketHuaweiAppId").orElse(providers.environmentVariable("POCKET_HUAWEI_APP_ID")).orElse("").get()
require(huaweiAppId.isEmpty() || huaweiAppId.matches(Regex("[0-9]{4,32}"))) { "Invalid Huawei App ID" }
val huaweiManifest = layout.buildDirectory.file("generated/pocketHuawei/AndroidManifest.xml")
val generateHuaweiManifest = tasks.register("generatePocketHuaweiManifest") {
    inputs.file("src/main/AndroidManifest.xml"); inputs.file("src/huawei/AndroidManifest.xml")
    outputs.file(huaweiManifest)
    doLast {
        val source = file("src/main/AndroidManifest.xml").readText()
        val overlay = file("src/huawei/AndroidManifest.xml").readText().substringAfter("<application>").substringBefore("</application>")
        huaweiManifest.get().asFile.apply { parentFile.mkdirs(); writeText(source.replace("</application>", "$overlay</application>")) }
    }
}
android {
    namespace = "dev.codexpocket.app"
    testBuildType = providers.gradleProperty("pocketTestBuildType").orElse("debug").get().also {
        require(it in setOf("debug", "release"))
    }
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.codexpocket.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.6.3"
        buildConfigField("String", "HUAWEI_APP_ID", "\"$huaweiAppId\"")
        manifestPlaceholders["pocketHuaweiAppId"] = huaweiAppId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) create("production") {
            storeFile = rootProject.file(releaseStore)
            storePassword = signingValue("storePassword", "POCKET_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "POCKET_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "POCKET_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    if (huaweiAppId.isNotBlank()) sourceSets.getByName("main") {
        java.srcDir("src/huawei/java")
        manifest.srcFile(huaweiManifest)
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
if (huaweiAppId.isNotBlank()) tasks.named("preBuild") { dependsOn(generateHuaweiManifest) }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    if (huaweiAppId.isNotBlank()) implementation("com.huawei.hms:push:6.13.0.301")
    implementation(project(":wire"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2") { exclude(group = "org.jetbrains", module = "annotations-java5") }
    implementation("io.noties:prism4j:2.0.0") { exclude(group = "org.jetbrains", module = "annotations-java5") }
    kapt("io.noties:prism4j-bundler:2.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
