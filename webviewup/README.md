# webviewup

Исходники модуля `core` библиотеки [WebViewUpgrade](https://github.com/JonaNorman/WebViewUpgrade)
(Apache License 2.0, см. `LICENSE`), коммит `5a5a7d1`. Библиотека подменяет движок WebView
только внутри приложения: APK движка (Google WebView, AOSP WebView, Chrome) берётся из файла
или из установленного пакета, систему она не трогает.

Используется в `app/src/main/java/my/torrstream/app/browser/WebViewEngine.kt`.
