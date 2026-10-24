package com.raagmusic.app.data.youtube

import org.json.JSONObject
import java.io.File

/** Short-lived anonymous native-client context. Accessed only by the serialized audio IO lane. */
internal class AudioSessionStore(directory: File?, private val now: () -> Long = System::currentTimeMillis) {
    private val file = directory?.let { File(it, "native-session.json") }
    fun read(country: String): String? = readSession(country)?.first
    fun readSession(country: String): Pair<String, Long>? = runCatching {
        val source = file?.takeIf { it.isFile && it.length() <= 8_192 } ?: return null
        val json = JSONObject(source.readText())
        val age = now() - json.optLong("created")
        if (json.optString("country") != country || age !in 0 until LIFETIME_MS) return null
        json.optString("visitor").takeIf(::valid)?.let { it to (LIFETIME_MS - age) }
    }.getOrNull()
    fun write(country: String, visitor: String) {
        if (!valid(visitor)) return
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(JSONObject().put("country", country).put("visitor", visitor).put("created", now()).toString())
            if (!temporary.renameTo(target)) temporary.delete()
        }
    }
    fun clear() { file?.delete() }
    private fun valid(value: String) = value.length in 10..2_048 && value.all { it.isLetterOrDigit() || it in "_-=.%" }
    companion object { const val LIFETIME_MS = 20 * 60_000L }
}
