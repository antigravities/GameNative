// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dagger.hilt) apply false
    alias(libs.plugins.jetbrains.jvm) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.secrets.gradle) apply false
}

configurations.configureEach {
    // 24-hour TTL for SNAPSHOTs (e.g. JavaSteam). Use --refresh-dependencies to force an immediate re-check
    // when a new JavaSteam snapshot is actually wanted; otherwise this avoids a network round-trip on every
    // build that lands more than an hour after the previous one.
    resolutionStrategy.cacheChangingModulesFor(24, TimeUnit.HOURS)
    resolutionStrategy.cacheDynamicVersionsFor(24, TimeUnit.HOURS)
}
