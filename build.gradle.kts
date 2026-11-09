plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Room 注解处理改用 kapt：与 AGP 8.3.x / Kotlin 1.9.24 组合最稳，
    // 避免 KSP 版本必须与 Kotlin 版本严格配对导致的云端构建失败。
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}
