// Сборка только в GitHub Actions: GITHUB_ACTIONS=true выставляет сам GitHub.
// На ПК (и в любой другой среде) конфигурация останавливается сразу.
if (System.getenv("GITHUB_ACTIONS") != "true") {
    throw GradleException("Сборка возможна только в GitHub Actions. Локальная сборка отключена.")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    packaging {
        resources {
            // multi-release служебные файлы (META-INF/versions/N) Android не использует,
            // а JSch и BouncyCastle приносят их с конфликтом: исключаем все
            excludes += setOf(
                "META-INF/versions/**",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
    namespace = "ru.omoda.autopilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.uiautomator"
        minSdk = 23
        targetSdk = 28
        versionCode = 259
        versionName = "1.10.237"
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
    implementation("com.github.mwiede:jsch:0.2.16")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
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

// Защита сборки: рабочий APK получается только с секретами GitHub.
// Без файлов конфигурации обновления сборка останавливается, а не выдаёт APK без обновления и без ключа.
val requiredBuildAssets = listOf("sftp_config.txt", "sftp_read_key.b64")
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        doFirst {
            val missing = requiredBuildAssets.filter { !file("src/main/assets/$it").exists() }
            if (missing.isNotEmpty()) {
                throw GradleException("Нет файлов конфигурации сборки: ${missing.joinToString()}. Сборка только через GitHub с секретами SFTP.")
            }
        }
    }
}
