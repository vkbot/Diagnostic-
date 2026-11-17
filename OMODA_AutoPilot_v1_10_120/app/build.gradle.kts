plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.omoda.autopilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.uiautomator"
        minSdk = 23
        targetSdk = 28
        versionCode = 142
        versionName = "1.10.120"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // targetSdk 28 намеренно: на Android 11 setWifiEnabled() не работает с targetSdk 29+.
        disable.add("ExpiredTargetSdkVersion")
        abortOnError = true
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
}

// Модель Vosk скачивается при сборке и кладётся в assets, поэтому распознавание работает без интернета на ГУ.
val voskModelName = "vosk-model-small-ru-0.22"
val downloadVoskModel by tasks.registering {
    val assetsDir = file("src/main/assets")
    val marker = file("src/main/assets/$voskModelName")
    val zipFile = layout.buildDirectory.file("vosk/$voskModelName.zip").get().asFile
    // Папка может уже существовать пустой, поэтому смотрим на содержимое, а не на сам факт наличия
    fun modelReady() = marker.isDirectory && (marker.listFiles()?.isNotEmpty() == true)
    outputs.upToDateWhen { modelReady() }
    doLast {
        if (!modelReady()) {
            if (!zipFile.exists()) {
                zipFile.parentFile.mkdirs()
                uri("https://alphacephei.com/vosk/models/$voskModelName.zip").toURL().openStream().use { input ->
                    zipFile.outputStream().use { input.copyTo(it) }
                }
            }
            if (zipFile.length() != 46236750L) {
                throw GradleException("Архив Vosk скачан неполностью: ${zipFile.length()} байт вместо 46236750")
            }
            copy {
                from(zipTree(zipFile))
                into(assetsDir)
            }
            if (!modelReady()) {
                throw GradleException("Модель Vosk не распаковалась в $marker")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(downloadVoskModel)
    }
}
