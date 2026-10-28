package com.zqc.tvnotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启。
 *
 * 电视端没有「后台常驻」的概念，系统重启后 App 不会自己活过来，
 * 只能靠 BOOT_COMPLETED（以及部分国产 ROM 的 QUICKBOOT_POWERON）拉起前台服务。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TVNotifyBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "收到开机广播: $action")
        try {
            NotifyService.start(context.applicationContext)
        } catch (e: Exception) {
            // API 31+ 后台启动前台服务受限，开机广播属于豁免场景，但仍可能被 ROM 拦截
            Log.e(TAG, "开机自启失败", e)
        }
    }
}
