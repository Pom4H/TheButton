# TheButton

Android-приложение (Kotlin + Jetpack Compose, minSdk 33) для управления светодиодом BLE-устройства `BUMBLE- -FFFFFFFF` (PB-03F / PHY6252).

- Сервис: `0000fff0-0000-1000-8000-00805f9b34fb`
- Characteristic: `0000fff4-0000-1000-8000-00805f9b34fb` (READ / WRITE REQUEST)
- Значения: ASCII `'0'` / `'1'` (как в BLE Scanner на iOS)

При старте запрашивает разрешения Bluetooth и location (нужно для сканирования iBeacon-рекламы на Samsung), находит устройство, сопрягается и подключается. Кнопка «Включить»/«Выключить» переключает светодиод.

## Сборка и запуск

```bash
./gradlew installDebug
adb shell am start -n com.thebutton.ble/.MainActivity
```

Логи — в Logcat по тегу `BleClient`.

## Структура

- `MainActivity.kt` — permissions и запуск UI
- `ble/BleClient.kt` — scan, bonding, GATT connect, чтение/запись characteristic
- `ui/` — Compose-экран, ViewModel, тема
