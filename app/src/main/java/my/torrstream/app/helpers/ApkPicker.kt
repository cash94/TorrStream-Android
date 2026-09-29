package my.torrstream.app.helpers

import my.torrstream.app.models.Asset

/**
 * APK под архитектуру устройства. С libVLC релиз выходит отдельными APK на каждую
 * архитектуру (`TorrStream-1.2.0-arm64-v8a.apk`, `…-armeabi-v7a.apk`): ABI перебираем
 * в порядке предпочтения системы, так что arm64-приставка получает arm64, а 32-битная —
 * armeabi-v7a. Релизы до разделения несут один APK без архитектуры в имени — берём его.
 *
 * Старые версии приложения (до этой) берут последний APK из списка, поэтому сборка
 * выкладывает armeabi-v7a последним: он ставится на любое ARM-устройство.
 */
fun pickApk(assets: List<Asset>, abis: List<String>): String? {
    val apks = assets.filter { it.browser_download_url.endsWith(".apk", true) }
    if (apks.isEmpty()) return null
    fun Asset.fileName() = name ?: browser_download_url.substringAfterLast('/')
    for (abi in abis) {
        apks.lastOrNull { it.fileName().endsWith("-$abi.apk", true) }
            ?.let { return it.browser_download_url }
    }
    // Архитектуры устройства среди сборок нет (x86-эмулятор) или релиз старый, с одним
    // APK на всех: как и прежде — последний
    return apks.last().browser_download_url
}
