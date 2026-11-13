package com.enlace.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every message the app sends, one builder each, so ContractTest can compare
 * them with contract/fixtures.json (which the PC parses in its own tests).
 */
object Messages {
    fun hello(
        deviceId: String, deviceName: String, modules: List<String>,
        pair: String? = null, token: String? = null,
    ): JSONObject {
        val msg = JSONObject()
            .put("type", "hello")
            .put("v", Protocol.VERSION)
            .put("device", JSONObject().put("id", deviceId).put("name", deviceName))
            .put("modules", JSONArray(modules))
        if (pair != null) msg.put("pair", pair)
        if (token != null) msg.put("token", token)
        return msg
    }

    fun discoveryAsk(pcId: String): JSONObject =
        JSONObject().put("enlace", Protocol.VERSION).put("find", pcId)

    fun micStart(rate: Int, channels: Int): JSONObject = JSONObject()
        .put("module", "mic").put("type", "start")
        .put("format", "s16le").put("rate", rate).put("channels", channels)

    fun micAudio(): JSONObject = JSONObject().put("module", "mic").put("type", "audio")

    fun micStop(): JSONObject = JSONObject().put("module", "mic").put("type", "stop")

    fun shareItem(mime: String, name: String?, toClipboard: Boolean): JSONObject {
        val msg = JSONObject().put("module", "share").put("type", "item").put("mime", mime)
        if (name != null) msg.put("name", name)
        return msg.put("to", if (toClipboard) "clipboard" else "downloads")
    }

    fun actionsList(): JSONObject = JSONObject().put("module", "actions").put("type", "list")

    fun actionsRun(id: String): JSONObject =
        JSONObject().put("module", "actions").put("type", "run").put("id", id)
}
