package my.torrstream.app.browser

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import kotlin.system.exitProcess

/**
 * Перезапуск приложения после смены движка WebView. Живёт в своём процессе (:restart):
 * гасит главный процесс и процессы-заглушки рендерера (:sandboxed_process*), запускает
 * приложение заново и завершается сам. Запуск идёт из видимой активности, поэтому
 * ограничения Android 10+ на старт активностей из фона его не касаются.
 */
class RestartActivity : Activity() {

    companion object {
        const val EXTRA_PID = "main_pid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_PID, -1)
        if (mainPid > 0) Process.killProcess(mainPid)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.forEach {
            if (it.pid != Process.myPid() && it.processName.startsWith("$packageName:")) {
                Process.killProcess(it.pid)
            }
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finish()
        exitProcess(0)
    }
}
