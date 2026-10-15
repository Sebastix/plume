plugins { java }
repositories { google(); mavenCentral() }
dependencies {
    implementation("com.android.tools.build:gradle-api:9.4.1")
    implementation("org.ow2.asm:asm:9.9.1")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
