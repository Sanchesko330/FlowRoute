# Релизная сборка

## Ключ подписи

Release подписывается ключом RSA, который хранится отдельно от исходного кода:

```text
D:\android_apps\FlowRoute-signing\flowroute-release.jks
D:\android_apps\FlowRoute-signing\keystore.properties
```

Потеря keystore или паролей не даст публиковать обновления поверх установленных release-версий. Нужна отдельная резервная копия обоих файлов. Не добавляйте их в Git.

Можно использовать другой путь через переменные окружения:

```text
FLOWROUTE_STORE_FILE
FLOWROUTE_STORE_PASSWORD
FLOWROUTE_KEY_ALIAS
FLOWROUTE_KEY_PASSWORD
```

Сборка:

```bat
gradlew.bat clean assembleRelease
```

APK появится в `app\build\outputs\apk\release\app-release.apk`.

Проверка подписи:

```bat
apksigner verify --verbose --print-certs app-release.apk
```

## Переход с debug-версии

1. Обновите debug-версию до 1.4.2 или новее.
2. На главной в разделе «Резервная копия» нажмите «Экспорт» и сохраните JSON-файл.
3. Удалите debug-версию. Android не разрешит установить release поверх неё из-за другой подписи.
4. Установите release APK.
5. Нажмите «Импорт» и выберите сохранённый файл.
6. Проверьте группы, серверы и правила перед подключением.

После этого новые release-версии будут устанавливаться как обычные обновления через GitHub Releases.

## Публикация

Перед новым релизом увеличьте `versionCode` и `versionName` в `app/build.gradle.kts`, затем выполните:

```bat
gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

В GitHub Release нужно загрузить именно release APK. Тег релиза должен быть опубликован не как draft и не как prerelease: приложение запрашивает `/releases/latest`.
