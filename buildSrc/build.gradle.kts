plugins { java }
repositories { google(); mavenCentral() }
dependencies {
    implementation("com.android.tools.build:gradle:9.4.1")
    // Keep AGP's bundled Kotlin dependency aligned with the app/Compose compiler.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.ow2.asm:asm:9.9.1")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
