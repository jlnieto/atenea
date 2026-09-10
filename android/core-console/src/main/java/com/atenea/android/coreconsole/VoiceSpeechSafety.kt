package com.atenea.android.coreconsole

internal fun String.forRealtimeSpeech(): String {
    return replace(
        Regex("\\b(?:Di\\s+)?(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+confirmo\\b", RegexOption.IGNORE_CASE),
        "confirma"
    ).replace(
        Regex("\\b(?:o\\s+)?(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+cancela(?:\\s+el\\s+envio)?\\b", RegexOption.IGNORE_CASE),
        "o cancela"
    ).replace(
        Regex("\\b(?:con|di|diciendo)?\\s*(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+fin\\b", RegexOption.IGNORE_CASE),
        "con la orden de cierre"
    ).replace(
        Regex("\\b(?:Atenea|Athenea|Antena|Antenea|Atenia|Aterea)\\s*,?\\s+(para|repite|continua|sigue|siguiente|anterior)\\b", RegexOption.IGNORE_CASE),
        "la orden de control"
    ).replace(Regex("\\s+"), " ").trim()
}
