package my.torrstream.app.helpers

import my.torrstream.app.models.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterPickApkTest {

    private fun asset(name: String) = Asset(
        url = "", id = 0, node_id = "", name = name, label = null, uploader = null,
        content_type = "application/vnd.android.package-archive", state = "uploaded",
        size = 0, download_count = 0, created_at = "", updated_at = "",
        browser_download_url = "https://github.com/cash94/TorrStream-Android/releases/download/v1.2.0/$name",
    )

    private val split = listOf(
        asset("TorrStream-1.2.0-arm64-v8a.apk"),
        asset("TorrStream-1.2.0-armeabi-v7a.apk"),
    )

    private fun pick(assets: List<Asset>, vararg abis: String) =
        pickApk(assets, abis.toList())?.substringAfterLast('/')

    @Test
    fun arm64DeviceGetsArm64() =
        assertEquals("TorrStream-1.2.0-arm64-v8a.apk", pick(split, "arm64-v8a", "armeabi-v7a", "armeabi"))

    @Test
    fun armv7DeviceGetsArmv7() =
        assertEquals("TorrStream-1.2.0-armeabi-v7a.apk", pick(split, "armeabi-v7a", "armeabi"))

    @Test
    fun oldArmeabiOnlyNameDoesNotMatchV7a() =
        // «armeabi» не должен совпасть с «…-armeabi-v7a.apk» по подстроке: берём запасной
        assertEquals("TorrStream-1.2.0-armeabi-v7a.apk", pick(split, "armeabi"))

    @Test
    fun oldSingleApkRelease() =
        assertEquals("TorrStream-1.0.9.apk", pick(listOf(asset("TorrStream-1.0.9.apk")), "arm64-v8a"))

    @Test
    fun noApkAtAll() = assertNull(pick(listOf(asset("notes.txt")), "arm64-v8a"))
}
