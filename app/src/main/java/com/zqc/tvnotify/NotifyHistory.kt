package com.zqc.tvnotify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 传音简录（通知历史）单条记录。
 */
data class NotifyHistoryItem(
    val id: String,
    val timestamp: Long,
    val title: String,
    val text: String,
    val level: String,
    val position: String,
    val hasMedia: Boolean,
    val sticky: Boolean,
    val tts: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("title", title)
        put("text", text)
        put("level", level)
        put("position", position)
        put("has_media", hasMedia)
        put("sticky", sticky)
        if (!tts.isNullOrBlank()) put("tts", tts)
    }

    companion object {
        fun fromJson(obj: JSONObject): NotifyHistoryItem? {
            return try {
                NotifyHistoryItem(
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    title = obj.optString("title", ""),
                    text = obj.optString("text", ""),
                    level = obj.optString("level", "info"),
                    position = obj.optString("position", "top"),
                    hasMedia = obj.optBoolean("has_media", false),
                    sticky = obj.optBoolean("sticky", false),
                    tts = obj.optString("tts", "").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 传音简录（通知历史）持久化与内存环形缓存（上限 20 条）。
 */
object NotifyHistory {
    private const val MAX_HISTORY = 20
    private val memoryList = mutableListOf<NotifyHistoryItem>()
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        initialized = true
        val raw = Prefs.getHistoryRaw(context) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = NotifyHistoryItem.fromJson(array.getJSONObject(i))
                if (item != null) {
                    memoryList.add(item)
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun record(context: Context, item: NotifyHistoryItem) {
        ensureLoaded(context)
        memoryList.removeAll { it.id == item.id }
        memoryList.add(0, item)
        while (memoryList.size > MAX_HISTORY) {
            memoryList.removeAt(memoryList.size - 1)
        }
        persist(context)
    }

    @Synchronized
    fun getAll(context: Context): List<NotifyHistoryItem> {
        ensureLoaded(context)
        return ArrayList(memoryList)
    }

    @Synchronized
    fun clear(context: Context) {
        ensureLoaded(context)
        memoryList.clear()
        persist(context)
    }

    @Synchronized
    fun toJsonArray(context: Context): JSONArray {
        ensureLoaded(context)
        val array = JSONArray()
        for (item in memoryList) {
            array.put(item.toJson())
        }
        return array
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        for (item in memoryList) {
            array.put(item.toJson())
        }
        Prefs.setHistoryRaw(context, array.toString())
    }
}
