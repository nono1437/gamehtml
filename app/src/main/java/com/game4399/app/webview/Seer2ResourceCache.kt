package com.game4399.app.webview

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Stores only validated, versioned public game resources in the app's disposable cache. */
internal object Seer2ResourceCache {
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 800
    private const val MAX_AGE_SECONDS = 24 * 60 * 60L

    @Synchronized
    fun read(directory: File, key: String): ByteArray? {
        val file = entry(directory, key)
        if (!file.isFile) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age !in 0 until MAX_AGE_SECONDS * 1000 || file.length() !in 1..MAX_ENTRY_BYTES.toLong()) {
            file.delete()
            return null
        }
        return try {
            val data = file.readBytes()
            if (Seer2Assets.validPayload(key.substringBefore('?'), data)) return data
            file.delete()
            null
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun store(directory: File, key: String, data: ByteArray, cacheControl: String?, age: Long) {
        if (data.size !in 1..MAX_ENTRY_BYTES) return
        val directives = cacheControl.orEmpty().lowercase().split(',').map { it.trim() }
        if (directives.any { it.substringBefore('=') in setOf("private", "no-cache", "no-store") }) return
        val maxAge = directives.firstOrNull { it.startsWith("max-age=") }?.substringAfter('=')?.toLongOrNull()
        if (maxAge == null || maxAge < MAX_AGE_SECONDS || age !in 0..(maxAge - MAX_AGE_SECONDS)) return
        if (!directory.isDirectory && !directory.mkdirs()) return

        try {
            val target = entry(directory, key)
            val temporary = File(directory, "pending.tmp")
            try {
                temporary.writeBytes(data)
                if (temporary.renameTo(target)) trim(directory)
            } finally {
                temporary.delete()
            }
        } catch (_: IOException) {
            // A full or unavailable disk should not interrupt delivery of a freshly downloaded asset.
        }
    }

    private fun entry(directory: File, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(directory, hash.joinToString("") { "%02x".format(it) } + ".asset")
    }

    private fun trim(directory: File) {
        val files = directory.listFiles { file -> file.name.endsWith(".asset") } ?: return
        var remaining = files.size
        var bytes = files.sumOf { it.length() }
        if (remaining <= MAX_ENTRIES && bytes <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            val length = file.length()
            if (file.delete()) {
                remaining--
                bytes -= length
            }
            if (remaining <= MAX_ENTRIES && bytes <= MAX_CACHE_BYTES) break
        }
    }
}
