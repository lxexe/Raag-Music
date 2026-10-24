package com.raagmusic.app.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Disposable, bounded JSON metadata. Callers perform all access on IO, never the UI thread. */
internal class DiskMetadataCache(private val directory: File?, private val capacity: Int = 64,
    private val byteBudget: Long = 4 * 1024 * 1024, private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun read(key: String, staleForMs: Long = 0): JSONObject? = runCatching {
        val file = file(key)?.takeIf { it.isFile && it.length() <= MAX_ENTRY_BYTES } ?: return null
        val json = JSONObject(file.readText())
        val age = now() - json.getLong("created")
        val lifetime = json.getLong("lifetime").coerceIn(0, 7 * DAY)
        if (age !in 0 until lifetime + staleForMs.coerceIn(0, 7 * DAY)) return null
        json.getJSONObject("value")
    }.getOrNull()
    @Synchronized fun write(key: String, value: JSONObject, lifetimeMs: Long) {
        val target = file(key) ?: return
        runCatching {
            val data = JSONObject().put("created", now()).put("lifetime", lifetimeMs.coerceIn(0, 7 * DAY)).put("value", value).toString()
            if (data.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_BYTES) return
            directory!!.mkdirs()
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(data)
            if (!temporary.renameTo(target)) { temporary.delete(); return }
            val files = directory.listFiles { item -> item.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
            var bytes = 0L
            files.forEachIndexed { index, item ->
                bytes += item.length()
                if (index >= capacity || bytes > byteBudget) item.delete()
            }
        }
    }
    private fun file(key: String): File? = directory?.let {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        File(it, "$name.json")
    }
    companion object { const val DAY = 86_400_000L; private const val MAX_ENTRY_BYTES = 512_000 }
}
