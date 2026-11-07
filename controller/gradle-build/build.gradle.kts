plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}
android {
    namespace = "com.lmqr.ha9_comp_service"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lmqr.ha9_comp_service"
        minSdk = 35
        targetSdk = 35
        versionCode = 81
        versionName = "hibreak-trial-41-slim-light-sliders"
        // -Ppublic=true: the public release (no adb test hook, no owner-specific defaults).
        val public = (project.findProperty("public") as String?) == "true"
        if (public) {
            versionCode = 99
            versionName = "hibreak-eink-0.1.19"
        }
        buildConfigField("boolean", "PUBLIC_RELEASE", public.toString())
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].apply {
        manifest.srcFile("generated/AndroidManifest.xml")
        java.srcDirs("../a9_accessibility_service/src")
        res.srcDirs("../a9_accessibility_service/res")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes.getByName("release") { isMinifyEnabled = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    compileOnly(files("generated/hidden-api-stubs.jar"))
}
