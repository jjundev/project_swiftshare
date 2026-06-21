plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    test {
        resources.srcDir(rootProject.file("../contracts/fixtures"))
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.test {
    useJUnit()
}
