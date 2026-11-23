package my.torrstream.app.player

/**
 * Уровень ночного режима звука для [NightModeProcessor]: процессор читает его на каждом буфере,
 * так что смена уровня действует сразу, без пересборки плеера. Хранится в Prefs.playerNightMode.
 */
object NightMode {
    const val OFF = 0
    /** −22 dBFS, 3:1 — заметно только на самых громких сценах. */
    const val GENTLE = 1
    /** −32 dBFS, 10:1 — громкость ровная, для динамиков ТВ ночью. */
    const val STRONG = 2

    @JvmField
    @Volatile
    var level = OFF
}
