# TorrStream для Android

Android-приложение TorrStream: клиент для просмотра видео через TorrServer на телефонах,
планшетах и Android TV.

Приложение показывает веб-интерфейс TorrStream в WebView и добавляет к нему нативные
возможности, недоступные из браузера: встроенный плеер с аппаратным декодированием,
запуск внешних плееров, строку «Продолжить просмотр» на главном экране Android TV.

- Минимальная версия Android: 6.0 (API 23)
- Встроенный плеер: Media3 / ExoPlayer, с программным декодированием AC3 / E-AC3 / DTS / TrueHD

## Лицензия

Проект распространяется под **GNU General Public License v3.0** — полный текст в файле
[LICENSE](LICENSE).

Лицензия выбрана не произвольно: приложение включает библиотеку
[media3-ffmpeg-decoder](https://github.com/jellyfin/jellyfin-androidx-media) под GPL-3.0,
и это обязывает распространять всё приложение под той же лицензией с открытыми исходниками.

## Сторонние компоненты

| Компонент | Лицензия | Назначение |
|---|---|---|
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Apache-2.0 | Движок встроенного плеера |
| [media3-ffmpeg-decoder](https://github.com/jellyfin/jellyfin-androidx-media) | **GPL-3.0** | Программное декодирование AC3 / DTS / TrueHD |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 | Сетевые запросы |
| [Glide](https://github.com/bumptech/glide) | Apache-2.0 / BSD | Загрузка изображений |
| [Lottie](https://github.com/airbnb/lottie-android) | Apache-2.0 | Анимация загрузки |
| [Conscrypt](https://github.com/google/conscrypt) | Apache-2.0 | TLS 1.3 на старых Android |
| [Rhino](https://github.com/mozilla/rhino) | MPL-2.0 | Выполнение PAC-скриптов прокси |

Приложение начиналось как форк [LAMPA](https://github.com/lampa-app/LAMPA).

## Сборка

```bash
./gradlew assembleLiteRelease
```

Готовый APK: `app/build/outputs/apk/lite/release/`.

### Требуется JDK 11

Сборка использует Gradle 7.5, который официально поддерживает Java не выше 18. На Java 19 и
новее сборка падает с ошибкой `Unable to establish loopback connection` — в новых JDK
изменилась реализация сокетов, на которых Gradle общается со своим демоном.

Если в системе стоит более новая Java, укажите JDK 11 явно:

```bash
JAVA_HOME=/path/to/jdk-11 ./gradlew assembleLiteRelease
```

### Варианты сборки

| Флейвор | Отличие |
|---|---|
| `lite` | Встроенное автообновление включено |
| `full` | Автообновление выключено |
| `ruStore` | Без автообновления, преднастроенный адрес сервера, minSdk 24 |

### Подпись релиза

Ключ подписи и пароли в репозиторий не входят. Чтобы собрать подписанный релиз, создайте
файл `app/keystore/keystore_config` (он в `.gitignore`):

```properties
storeFile=/полный/путь/к/keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Вместо файла можно задать переменные окружения `KEYSTORE_PASSWORD`,
`RELEASE_SIGN_KEY_ALIAS`, `RELEASE_SIGN_KEY_PASSWORD`.
