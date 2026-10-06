import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.monchamp.verusfarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.monchamp.verusfarm"
        minSdk = 26          // Android 8.0 et plus
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Les deux familles de processeurs de téléphones
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clé de signature fixe : chaque nouvelle version peut remplacer l'ancienne
    // sans désinstaller l'application (utile avec l'administrateur de l'appareil).
    signingConfigs {
        create("farm") {
            storeFile = file("verusfarm.keystore")
            storePassword = "verusfarm"
            keyAlias = "verusfarm"
            keyPassword = "verusfarm"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("farm")
        }
        debug {
            signingConfig = signingConfigs.getByName("farm")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Indispensable : extrait libccminer.so sur le téléphone pour pouvoir l'exécuter
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// =====================================================================
//  Téléchargement automatique du programme de minage
//  Lancé tout seul avant chaque compilation. Lecture des liens dans
//  miner-sources.properties (à la racine du projet).
//  À la main : onglet Gradle > app > Tasks > verusfarm > fetchMiner
// =====================================================================
val minerProps = Properties().apply {
    val f = rootProject.file("miner-sources.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

tasks.register("fetchMiner") {
    group = "verusfarm"
    description = "Télécharge les programmes de minage et les place dans jniLibs"

    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        val kinds = mapOf("optimized" to "libccminer.so", "compat" to "libccminer_compat.so")
        val machineCode = mapOf("arm64-v8a" to 183, "armeabi-v7a" to 40)   // code ELF du processeur

        for (abi in abis) {
            for ((kind, fileName) in kinds) {
                val dest = file("src/main/jniLibs/$abi/$fileName")
                if (dest.exists() && dest.length() > 0L) {
                    println("[VerusFarm] $abi/$fileName : déjà présent")
                    continue
                }

                val url = minerProps.getProperty("$abi.$kind.url", "").trim()
                val sha = minerProps.getProperty("$abi.$kind.sha256", "").trim().lowercase()
                if (url.isEmpty()) {
                    println("[VerusFarm] $abi/$fileName : aucun lien dans miner-sources.properties, ignoré")
                    continue
                }
                if (sha.length != 64) {
                    throw GradleException("[VerusFarm] Empreinte SHA-256 manquante ou invalide pour $abi.$kind.")
                }

                val tmp = File(temporaryDir, "$abi-$fileName")
                try {
                    println("[VerusFarm] Téléchargement de $abi/$fileName ...")
                    URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } catch (e: IOException) {
                    // Pas d'internet : on ne bloque pas la compilation
                    println("[VerusFarm] ATTENTION : téléchargement impossible (${e.message}). $abi/$fileName ignoré.")
                    continue
                }

                val bytes = tmp.readBytes()

                // 1) Le fichier est-il bien celui attendu ?
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (actual != sha) {
                    tmp.delete()
                    throw GradleException(
                        "[VerusFarm] Empreinte incorrecte pour $abi/$fileName.\n  attendue : $sha\n  reçue    : $actual"
                    )
                }

                // 2) Est-ce un programme Android du bon processeur ?
                val isElf = bytes.size > 20 && bytes[0] == 0x7f.toByte() &&
                    bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
                if (!isElf) {
                    throw GradleException("[VerusFarm] $abi/$fileName n'est pas un programme Linux/Android valide.")
                }
                val machine = (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
                if (machine != machineCode[abi]) {
                    throw GradleException("[VerusFarm] $abi/$fileName est prévu pour un autre processeur.")
                }

                // 3) Compilé pour Termux ? Alors il ne marchera pas dans l'application.
                if (String(bytes, Charsets.ISO_8859_1).contains("com.termux")) {
                    throw GradleException(
                        "[VerusFarm] $abi/$fileName est compilé pour Termux : il dépend de fichiers absents d'Android. " +
                            "Utilise une version compilée pour Android (voir LISEZMOI.txt)."
                    )
                }

                dest.parentFile.mkdirs()
                tmp.copyTo(dest, overwrite = true)
                dest.setExecutable(true)
                println("[VerusFarm] $abi/$fileName installé")
            }
        }
    }
}

// Le téléchargement se fait automatiquement avant chaque compilation
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("fetchMiner")
}
