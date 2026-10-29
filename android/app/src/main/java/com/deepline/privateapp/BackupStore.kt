package com.deepline.privateapp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable local backup. Backup ZIP includes API keys, so it should be kept private. */
object BackupStore {
    fun export(context: Context, source: JSONObject, target: Uri) {
        val copy = JSONObject(source.toString())
        context.contentResolver.openOutputStream(target, "w")?.use { out ->
            ZipOutputStream(out).use { zip ->
                val mediaEntries = linkedMapOf<String, File>()
                val fileToZipRef = linkedMapOf<String, String>()
                // File URIs can be standalone values (avatars/covers) or embedded in message markers like [[IMAGE:file:///...]].
                rewriteStrings(copy) { value ->
                    Regex("file://[A-Za-z0-9%:/._-]+", RegexOption.IGNORE_CASE).replace(value) { match ->
                        val rawUri = match.value
                        fileToZipRef.getOrPut(rawUri) {
                            val path = Uri.parse(rawUri).path
                            val file = path?.let(::File)
                            if (file != null && file.isFile && file.exists()) {
                                val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
                                val key = "${UUID.randomUUID()}$ext"
                                mediaEntries[key] = file
                                "zipmedia:$key"
                            } else rawUri
                        }
                    }
                }
                zip.putNextEntry(ZipEntry("data.json")); zip.write(copy.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                mediaEntries.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry("media/$name")); file.inputStream().use { input -> input.copyTo(zip) }; zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("README.txt")); zip.write("DeepLine local backup. Includes AI API keys and chat history. Keep this file private.".toByteArray()); zip.closeEntry()
            }
        } ?: throw IllegalStateException("无法写入所选 ZIP 文件")
    }

    fun import(context: Context, source: Uri): JSONObject {
        val entries = linkedMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(source)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && (entry.name == "data.json" || entry.name.startsWith("media/"))) {
                        entries[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("无法读取所选 ZIP 文件")
        val json = entries["data.json"]?.toString(Charsets.UTF_8)?.let(::JSONObject)
            ?: throw IllegalStateException("ZIP 中没有 data.json，不是 DeepLine 备份文件")
        val mediaDir = File(context.filesDir, "deepline-imported-media").apply { mkdirs() }
        entries.filterKeys { it.startsWith("media/") }.forEach { (entry, bytes) ->
            val safeName = entry.removePrefix("media/").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            File(mediaDir, safeName).writeBytes(bytes)
        }
        rewriteStrings(json) { value ->
            Regex("zipmedia:[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE).replace(value) { match ->
                val name = match.value.removePrefix("zipmedia:").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(mediaDir, name)
                if (file.exists()) Uri.fromFile(file).toString() else match.value
            }
        }
        return json
    }

    private fun rewriteStrings(node: Any?, rewrite: (String) -> String) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                keys.forEach { key ->
                    val value = node.opt(key)
                    when (value) {
                        is String -> node.put(key, rewrite(value))
                        is JSONObject, is JSONArray -> rewriteStrings(value, rewrite)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val value = node.opt(i)
                when (value) {
                    is String -> node.put(i, rewrite(value))
                    is JSONObject, is JSONArray -> rewriteStrings(value, rewrite)
                }
            }
        }
    }
}
