package my.torrstream.app.torrserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import my.torrstream.app.MainActivity
import my.torrstream.app.R

/**
 * Держит запущенный TorrServer ([TorrServerManager]) — foreground-сервис с уведомлением,
 * чтобы система не выгружала процесс, пока приложение свёрнуто или открыт плеер.
 *
 * Процесс — дочерний процесс приложения. Упал сам — перезапускаем, но не чаще
 * [MAX_RESTARTS] раз за [RESTART_WINDOW_MS]: сломанный файл или занятый порт иначе
 * крутили бы перезапуск бесконечно.
 */
class TorrServerService : Service() {

    @Volatile private var stopping = false
    private val restarts = ArrayList<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        stopping = false
        if (process?.isAliveCompat() != true) launch()
        // Систему, выгрузившую сервис, просим поднять его снова
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        process?.let { p ->
            try { p.destroy() } catch (_: Exception) { }
        }
        process = null
        super.onDestroy()
    }

    private fun launch() {
        val bin = TorrServerManager.binary
        if (!bin.canExecute()) {
            Log.e(TAG, "Нет исполняемого файла ${bin.path}")
            stopSelf()
            return
        }
        TorrServerManager.dataDir.mkdirs()
        val p = try {
            ProcessBuilder(
                bin.absolutePath,
                "-p", TorrServerManager.PORT.toString(),
                "-d", TorrServerManager.dataDir.absolutePath,
                "-l", TorrServerManager.logFile.absolutePath
            ).redirectErrorStream(true).directory(TorrServerManager.dataDir).start()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить TorrServer", e)
            stopSelf()
            return
        }
        process = p
        Log.i(TAG, "TorrServer запущен")
        // Вывод процесса нужно вычитывать: заполненный буфер останавливает процесс.
        // Сам журнал TorrServer пишет в файл (-l), здесь — то, что мимо него.
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line -> Log.d(TAG, line) }
            } catch (_: Exception) { }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            onExit(p, code)
        }, "torrserver-output").start()
    }

    private fun onExit(p: Process, code: Int) {
        if (stopping || process !== p) return
        Log.w(TAG, "TorrServer завершился сам, код $code")
        process = null
        val now = System.currentTimeMillis()
        synchronized(restarts) {
            restarts.removeAll { now - it > RESTART_WINDOW_MS }
            if (restarts.size >= MAX_RESTARTS) {
                Log.e(TAG, "TorrServer падает раз за разом — больше не перезапускаем")
                stopSelf()
                return
            }
            restarts += now
        }
        Thread.sleep(RESTART_DELAY_MS)
        if (!stopping) launch()
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.torrserver_notification_title))
            .setContentText(getString(R.string.torrserver_notification_text, TorrServerManager.PORT))
            .setSmallIcon(R.drawable.lampa_icon)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.torrserver_channel),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "TorrServer"
        private const val CHANNEL_ID = "torrserver"
        private const val NOTIF_ID = 8090
        private const val MAX_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L
        private const val RESTART_DELAY_MS = 3000L

        @Volatile private var process: Process? = null

        val isProcessAlive: Boolean get() = process?.isAliveCompat() == true

        fun start(context: Context) {
            try {
                val intent = Intent(context, TorrServerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TorrServerService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop failed", e)
            }
        }

        /** Process.isAlive есть только с API 26 */
        private fun Process.isAliveCompat(): Boolean = try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }
}
