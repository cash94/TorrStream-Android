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

Вариант один — `lite`: `./gradlew assembleLiteRelease`. В нём включено автообновление
через релизы этого репозитория (`app/src/main/java/my/torrstream/app/helpers/Updater.kt`)
и задан адрес сервера по умолчанию `http://torrstream.online` — его можно сменить
в настройках приложения.

### Подпись релиза

Ключ подписи и пароли в репозиторий не входят. Чтобы собрать подписанный релиз, создайте
файл `app/keystore/keystore_config` (он в `.gitignore`):

```properties
storeFile=/полный/путь/к/keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Вместо файла можно задать переменные окружения `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`RELEASE_SIGN_KEY_ALIAS`, `RELEASE_SIGN_KEY_PASSWORD` — так собирает GitHub Actions.

### Сборка APK в GitHub Actions

Workflow `.github/workflows/build-lite.yml` собирает подписанный `lite`-релиз. Запуск —
вручную (Actions → Build lite APK → Run workflow) или пушем тега `v*`: по тегу APK
дополнительно прикладывается к релизу, откуда его забирает автообновление.

Ключ подписи хранится в секретах репозитория (Settings → Secrets and variables →
Actions), они видны только владельцу и в лог не попадают:

| Секрет | Значение |
|---|---|
| `KEYSTORE_BASE64` | файл ключа, закодированный base64 |
| `KEYSTORE_PASSWORD` | пароль хранилища |
| `RELEASE_SIGN_KEY_ALIAS` | алиас ключа |
| `RELEASE_SIGN_KEY_PASSWORD` | пароль ключа |

Версия берётся из git: `versionName` — последний тег (`git describe --tags`),
`versionCode` — число коммитов в `origin/main`. Без тегов используется запасное
значение `1.0.1`, поэтому релизы нужно помечать тегами вида `v1.0.2`.

### Выпуск новой версии

```bash
git push                                        # изменения в main
git tag -a v1.0.5 -m "что изменилось"
git push origin v1.0.5
```

Дальше всё делает Actions: собирает подписанный APK, создаёт релиз и прикладывает
файл. Установленные приложения увидят обновление при следующей проверке.

Тег ставится на коммит, а не наоборот: без нового коммита `versionCode` не вырастет,
и Android воспримет установку как переустановку той же сборки.
