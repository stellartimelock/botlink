package org.stellartimelock.botlink.protocol

import org.json.JSONObject

/**
 * LAN hello only. After hello-ok both sides use [Relay] SSND frames.
 * The public relay does not use these ops.
 */
object Wire {
    const val VERSION = 1
    const val APP = "botlink"

    fun hello(role: String, room: String): String = JSONObject()
        .put("v", VERSION)
        .put("op", "hello")
        .put("app", APP)
        .put("role", role)
        .put("room", room)
        .toString()

    fun helloOk(role: String, room: String): String = JSONObject()
        .put("v", VERSION)
        .put("op", "hello-ok")
        .put("app", APP)
        .put("role", role)
        .put("room", room)
        .toString()

    fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (_: Exception) {
        null
    }
}
