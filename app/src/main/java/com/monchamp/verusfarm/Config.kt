package com.monchamp.verusfarm

object Config {

    // ======================================================================
    //  MODIFIE CETTE LIGNE AVANT DE COMPILER : mets ton adresse VRSC.
    //  (Tu pourras aussi la changer dans l'application, bouton « Modifier ».)
    // ======================================================================
    const val DEFAULT_WALLET = "RXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

    // Serveurs Luckpool, du plus proche au plus lointain.
    // Vérifie les adresses et le port sur luckpool.net avant de publier.
    val POOLS = listOf("eu.luckpool.net", "na.luckpool.net", "ap.luckpool.net")
    const val POOL_PORT = 3956
    const val POOL_PASSWORD = "x"

    // Programmes de minage cherchés dans jniLibs, dans cet ordre :
    //  1) libccminer.so         : version optimisée (instructions crypto ARM)
    //  2) libccminer_compat.so  : version de secours pour les vieux processeurs
    val ENGINES = listOf("libccminer.so", "libccminer_compat.so")

    const val MAX_LOG_LINES = 60

    private val WALLET_REGEX = Regex("^R[1-9A-HJ-NP-Za-km-z]{33}$")

    fun isPlaceholder(wallet: String): Boolean =
        wallet.isBlank() || wallet.contains("XXXXXXXX")

    fun isUsableWallet(wallet: String): Boolean =
        !isPlaceholder(wallet) && WALLET_REGEX.matches(wallet)
}
