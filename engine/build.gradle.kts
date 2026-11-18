import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    // A library: every public declaration is public on purpose, with a declared type.
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "agency.encrypt.glrenderer.engine"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Maven Central through the Central Portal. Credentials and the signing key come from
// ORG_GRADLE_PROJECT_mavenCentralUsername / _mavenCentralPassword / _signingInMemoryKey(Password),
// set by the release workflow from repository secrets.
mavenPublishing {
    publishToMavenCentral()
    // Central rejects unsigned artifacts; without a key (local publishToMavenLocal) skip signing instead of failing.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "glrenderer")

    pom {
        name.set("kmp-gl-renderer")
        description.set(
            "OpenGL ES 3 renderer and entity-component system for Kotlin Multiplatform (Android and iOS): " +
                "deferred and clustered forward+ pipelines, PBR, shadows, HDR, bloom, FXAA and instancing.",
        )
        inceptionYear.set("2020")
        url.set("https://github.com/rstanic12/kmp-gl-renderer")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("rstanic12")
                name.set("Renato Stanic")
                url.set("https://github.com/rstanic12")
            }
        }
        scm {
            url.set("https://github.com/rstanic12/kmp-gl-renderer")
            connection.set("scm:git:git://github.com/rstanic12/kmp-gl-renderer.git")
            developerConnection.set("scm:git:ssh://git@github.com/rstanic12/kmp-gl-renderer.git")
        }
    }
}
