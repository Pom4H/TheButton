# TheButton

Android-приложение (Kotlin + Jetpack Compose, minSdk 33) для управления светодиодом BLE-устройства `BUMBLE--FFFFFFFF` через characteristic `0000fff4-0000-1000-8000-00805f9b34fb` (READ / WRITE REQUEST).

При старте запрашивает `BLUETOOTH_SCAN` и `BLUETOOTH_CONNECT`, находит устройство, подключается и читает состояние светодиода. Кнопка «Включить»/«Выключить» пишет `1`/`0`; при потере соединения доступна кнопка «Повторить подключение».

## Сборка и запуск

Откройте проект в Android Studio и запустите конфигурацию `app` на телефоне с BLE (эмулятор к реальному устройству не подключится), либо:

```bash
./gradlew installDebug
adb shell am start -n com.thebutton.ble/.MainActivity
```

Логи — в Logcat по тегу `BleClient`.

## Структура

- `MainActivity.kt` — permissions и запуск UI
- `ble/BleClient.kt` — scan, GATT connect, чтение/запись characteristic
- `ui/` — Compose-экран, ViewModel, тема
