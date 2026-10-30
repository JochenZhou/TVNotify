package com.zqc.tvnotify

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 访问 token 的唯一持久化入口。
 *
 * 设计要点：
 * 1. token 由「首次使用」时随机生成 8 位字母数字，写入 SharedPreferences；
 * 2. HTTP 服务端每次请求都通过 [getToken] 实时读取，因此「重置 Token」后
 *    旧地址立即失效、新地址立即生效，无需重启服务；
 * 3. 写入使用 commit() 而不是 apply()，保证重置后紧接着的请求一定能读到新值。
 */
object Prefs {

    private const val FILE_NAME = "tvnotify_prefs"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_THEME = "ui_theme"

    private const val TOKEN_LENGTH = 8

    /**
     * 字母表刻意剔除 i/l/o/0/1 等易混字符，避免用户在电视上用遥控器抄写地址时出错。
     * 仍然是「字母 + 数字」组合。
     */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    private val random = SecureRandom()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 读取 token；不存在则生成并持久化。 */
    fun getToken(context: Context): String {
        val store = prefs(context)
        val cached = store.getString(KEY_TOKEN, null)
        if (!cached.isNullOrEmpty()) {
            return cached
        }
        synchronized(this) {
            // 双重检查：HTTP 线程与 UI 线程可能同时首次读取
            val again = store.getString(KEY_TOKEN, null)
            if (!again.isNullOrEmpty()) {
                return again
            }
            val fresh = generateToken()
            store.edit().putString(KEY_TOKEN, fresh).commit()
            return fresh
        }
    }

    /** 重新生成 token，返回新值。运行中的 HTTP 服务因每次请求都读取，会自动生效。 */
    fun resetToken(context: Context): String {
        val store = prefs(context)
        synchronized(this) {
            val fresh = generateToken()
            store.edit().putString(KEY_TOKEN, fresh).commit()
            return fresh
        }
    }

    /** 读取已保存的主题 id；无记录返回 null，由调用方回落默认主题。 */
    fun getThemeId(context: Context): String? =
        prefs(context).getString(KEY_THEME, null)

    /** 保存主题 id。UI 切换后立即生效，不需要重启服务。 */
    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_THEME, id).apply()
    }

    private fun generateToken(): String {
        val builder = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }
}
