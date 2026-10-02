package my.torrstream.app.torrserver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import my.torrstream.app.App
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Поиск TorrServer в локальной сети по mDNS. С недавних версий TorrServer объявляет
 * себя сервисом `_torrserver._tcp` (по умолчанию включено, настройка EnableBonjour) с
 * TXT `version=…` и `path=/` — см. server/bonjour/bonjour.go в YouROK/TorrServer.
 *
 * Только в приложении: у браузера доступа к mDNS нет. Веб запускает поиск и опрашивает
 * результат через AndroidJS (tsDiscoverStart / tsDiscoverStatus).
 *
 * resolveService до Android 14 не принимает второй запрос, пока не закончен первый
 * (FAILURE_ALREADY_ACTIVE), поэтому найденные сервисы разрешаем по очереди.
 */
object TorrServerDiscovery {
    private const val TAG = "TorrServerDiscovery"
    private const val SERVICE_TYPE = "_torrserver._tcp."
    private const val DURATION_MS = 8000L

    private val main = Handler(Looper.getMainLooper())
    private val nsd: NsdManager? by lazy {
        App.context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    @Volatile var discovering = false; private set
    @Volatile var error: String? = null; private set
    private val found = LinkedHashMap<String, JSONObject>()      // ключ — url
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun statusJson(): String = synchronized(found) {
        JSONObject().apply {
            put("discovering", discovering)
            put("error", error ?: JSONObject.NULL)
            put("servers", JSONArray(found.values.toList()))
        }.toString()
    }

    /** Новый поиск на [DURATION_MS]; найденное в прошлый раз сбрасывается */
    fun start() = main.post {
        stopDiscovery()
        val manager = nsd
        if (manager == null) {
            error = "Поиск в сети недоступен на этом устройстве"
            return@post
        }
        synchronized(found) { found.clear() }
        pending.clear()
        resolving = false
        error = null
        discovering = true
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start failed $errorCode")
                error = "Не удалось начать поиск (код $errorCode)"
                discovering = false
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    pending.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            error = e.message
            discovering = false
            listener = null
            return@post
        }
        main.postDelayed({ finish() }, DURATION_MS)
    }

    private fun finish() {
        stopDiscovery()
        discovering = false
    }

    private fun stopDiscovery() {
        val l = listener ?: return
        listener = null
        try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) { }
    }

    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        val manager = nsd ?: return
        resolving = true
        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve ${serviceInfo.serviceName}: $errorCode")
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val addr = serviceInfo.host
                if (addr != null) {
                    val ip = addr.hostAddress?.substringBefore('%') ?: ""
                    val host = if (addr is Inet6Address) "[$ip]" else ip
                    val url = "http://$host:${serviceInfo.port}"
                    val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        serviceInfo.attributes["version"]?.toString(Charsets.UTF_8) else null
                    val entry = JSONObject().apply {
                        put("name", serviceInfo.serviceName)
                        put("url", url)
                        put("host", ip)
                        put("port", serviceInfo.port)
                        put("version", version ?: JSONObject.NULL)
                        put("ipv6", addr is Inet6Address)
                        put("self", isOwnAddress(ip))
                    }
                    synchronized(found) { found[url] = entry }
                }
                main.post { resolving = false; resolveNext() }
            }
        })
    }

    /** Адрес этого же устройства — в списке пометим «это устройство» */
    private fun isOwnAddress(ip: String): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            ni.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == ip }
        }
    } catch (_: Exception) {
        false
    }
}
