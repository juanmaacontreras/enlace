package com.enlace.app

/** Every text the user reads, in one place. Rioplatense Spanish. */
object Strings {
    const val APP = "Enlace"

    const val UNLINKED_TITLE = "Vinculá este dispositivo con tu compu"
    const val UNLINKED_STEPS = "1. En la compu abrí «Enlace: vincular dispositivo».\n2. Tocá el botón y apuntá al QR."
    const val SCAN = "Escanear QR"
    const val SCAN_PROMPT = "Apuntá al QR de la compu"
    const val PAIRING = "Vinculando…"
    const val PAIR_BAD_QR = "Ese QR no es de Enlace"
    const val PAIR_EXPIRED = "El QR venció. Generá uno nuevo en la compu."
    const val PAIR_UNREACHABLE = "No llegué a la compu. Fijate que estén en el mismo Wi-Fi."
    const val PAIRED = "Vinculado con"

    const val SEARCHING = "Buscando la compu…"
    const val CONNECTED = "conectada"
    const val UNREACHABLE = "no la encuentro"
    const val UNAUTHORIZED = "La compu ya no reconoce este dispositivo. Vinculalo de nuevo."
    const val RETRY = "Reintentar"
    const val UNLINK = "Desvincular"
    const val UNLINK_CONFIRM = "¿Desvincular este dispositivo de la compu?"
    const val CANCEL = "Cancelar"

    const val UPDATE = "Hay una versión nueva"
    const val UPDATE_ABOUT = "Se descarga y te pide confirmar la instalación."
    const val UPDATE_NOW = "Actualizar"
    const val UPDATE_PERMISSION = "Permití «instalar apps desconocidas» para Enlace y volvé a tocar Actualizar."
    const val UPDATE_DOWNLOADING = "Descargando…"
    const val UPDATE_FAILED = "No se pudo descargar. Probá de nuevo."

    const val MIC = "Micrófono"
    const val MIC_ABOUT = "La compu usa el micrófono de este dispositivo."
    const val MIC_TURN_ON = "Prender"
    const val MIC_TURN_OFF = "Apagar"
    const val MIC_CONNECTING = "Conectando…"
    const val MIC_ON = "Micrófono prendido"
    const val MIC_RECONNECTING = "Se cortó. Reconectando…"
    const val MIC_LOST = "Micrófono apagado: se perdió la conexión con la compu"
    const val MIC_NO_PERMISSION = "Sin permiso de micrófono no puedo prenderlo"
    const val MIC_CHANNEL = "Micrófono"
    const val MIC_TILE = "Mic a la compu"

    const val SHARE = "Compartir"
    const val SHARE_ABOUT = "Desde cualquier app: Compartir → A la compu. Texto e imágenes quedan listos para pegar."
    const val SHARE_CLIPBOARD = "Enviar lo que copié"
    const val SHARE_SENDING = "Enviando a la compu…"
    const val SHARE_CLIPBOARD_DONE = "Listo: pegalo en la compu"
    const val SHARE_SAVED = "Guardado en Descargas de la compu"
    const val SHARE_EMPTY = "No hay nada para enviar"
    const val SHARE_TOO_BIG = "Es demasiado grande para enviar"
    const val SHARE_FAILED = "No encontré la compu"
    const val SHARE_NOT_LINKED = "Este dispositivo no está vinculado. Abrí Enlace."

    const val ACTIONS = "Acciones"
    const val ACTIONS_EMPTY = "Sin botones. Se agregan en la compu, en ~/.config/enlace/actions.toml"
    const val ACTION_FAILED = "No se pudo"
}
