plugins {
    `kotlin-dsl`
}

dependencies {
    // Same version as the app's AGP (gradle/libs.versions.toml) so the plugin classes are shared.
    implementation("com.android.tools.build:gradle:8.5.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    implementation("org.ow2.asm:asm:9.7")
}
