# AirPlay Receiver for Android TV / Google TV

> ### 🍴 About this fork
> Fork of [alexqem/airplay-tv-receiver](https://github.com/alexqem/airplay-tv-receiver) (itself based on [GodMingFeng/airplay-android](https://github.com/GodMingFeng/airplay-android) and [serezhka/java-airplay-lib](https://github.com/serezhka/java-airplay-lib)), reworked to run as an always-on background receiver.
>
> **Changes**
> - **Always available.** The receiver runs as a foreground service from boot (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `START_STICKY`) and no longer stops when the app leaves the screen.
> - **Auto-open on connect.** When an iPhone/Mac starts mirroring, the player screen comes up by itself and closes again when the session ends.
> - **No grey start.** Frames from the last keyframe are buffered until the decoder is ready, so a session started in the background shows a picture immediately.
> - **Network resilience.** Start is retried until Wi-Fi is up after boot; the receiver restarts if the IPv4 address changes.
> - **Fix: invisible on MediaTek TVs.** mDNS now advertises on the default network's address instead of the first interface (Wi-Fi Direct `p2p0` was picked before).
> - **Privacy.** FairPlay / pairing keys are no longer written to logcat.
> - **Security.** Netty bumped 4.1.94 → 4.1.140.
>
> **Build** (JDK 17, Android SDK 33): `./gradlew assembleRelease`, or run the checked-in wrapper jar directly: `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleRelease`.
>
> **Install & allow background start**
> ```bash
> adb install -r app/build/outputs/apk/release/app-release.apk
> adb shell appops set com.airplay.android SYSTEM_ALERT_WINDOW allow   # lets the service open the player
> adb shell cmd deviceidle whitelist +com.airplay.android               # optional: exempt from power saving
> adb shell am start -n com.airplay.android/.MainActivity               # first launch once
> ```

> **100% Free, Open-Source & Ad-Free AirPlay Mirroring Receiver for Android TV**  
> *Forked and customized for clean, true borderless full-screen mirroring with pitch-black margins.*

---

## English

### Overview
A clean, lightweight, open-source Apple AirPlay screen mirroring receiver built specifically for Android TV, Google TV, and TV boxes. Unlike commercial alternatives (e.g. AirScreen, CastReceiver) that suffer from subscription paywalls, timeouts, and regional ad-blocking interruptions, this receiver runs **100% locally on your LAN without internet access, tracking, or ads**.

### Key Features
- **Clean Full-Screen Mirroring**: Zero decorative borders, moulding frames, or background wallpaper leakage. Letterboxing margins are rendered in pitch-black (`#000000`), perfectly matching OLED/LED TV bezels.
- **Hardware-Accelerated Decoding**: Video stream (H.264) decoded natively via Android `MediaCodec` direct to `SurfaceView`.
- **Synchronized Audio**: Hardware-accelerated AAC-ELD audio decryption and low-latency playback via `AudioTrack`.
- **Zero Configuration**: Broadcasts as an Apple TV endpoint via mDNS (Bonjour `_airplay._tcp:7000` / `_raop._tcp:5000`). Instantly discoverable in macOS Control Center and iOS Screen Mirroring.
- **TV Remote Friendly**: Supports standard Android TV remote D-Pad controls. Controls auto-hide during playback.
- **Companion to [OpenDisplay](https://github.com/alexqem/OpenDisplay)**: Pairs with OpenDisplay on Apple Silicon Mac for 1-click HiDPI / Ultra Sharp (1440p / 4K) stream scaling.

### Installation
Download the pre-built `airplay-tv-receiver.apk` from [Releases](https://github.com/alexqem/airplay-tv-receiver/releases) and install via ADB:
```bash
adb install -r airplay-tv-receiver.apk
```
Or transfer to your TV via flash drive / file manager and open it.

---

## Русский

### Описание
Быстрый, полностью бесплатный и открытый AirPlay-ресивер для Android TV и Google TV. В отличие от коммерческих приложений (AirScreen, CastReceiver и др.), которые прерывают просмотр рекламой, блокируются из-за сетевых ограничений или требуют платную подписку (VIP), этот ресивер работает **исключительно внутри домашней локальной сети без интернета, рекламы и ограничений по времени**.

### Основные возможности
- **Чистый полноэкранный режим без рамок**: Убраны все декоративные рамки, градиенты и фоновые обои. Поля при несовпадении пропорций (letterbox) — идеально черные (`#000000`), без засветов при просмотре фильмов.
- **Аппаратное ускорение (H.264)**: Поток видео декодируется нативным чипом телевизора через `MediaCodec` на `SurfaceView` с минимальным нагревом и нагрузкой на CPU.
- **Синхронный звук**: Декодирование аудиопотока AAC-ELD без рассинхрона через `AudioTrack`.
- **Мгновенное обнаружение**: Анонсирует устройство в сети по протоколам Bonjour mDNS (`_airplay._tcp:7000`, `_raop._tcp:5000`). Появляется в меню «Повтор экрана» на Mac, iPhone и iPad.
- **Управление пультом**: Полноценная поддержка пульта Android TV (D-Pad), интерфейс автоматически скрывается при начале трансляции.
- **Идеально работает с [OpenDisplay](https://github.com/alexqem/OpenDisplay)**: Поддерживает режим Ultra Sharp (1440p / 4K) и переключение HiDPI в 1 клик с Mac.

### Установка
Скачайте готовый `airplay-tv-receiver.apk` со страницы [Releases](https://github.com/alexqem/airplay-tv-receiver/releases) и установите через ADB:
```bash
adb install -r airplay-tv-receiver.apk
```
Либо установите APK с флешки через любой файловый менеджер на ТВ.

---

### Keywords / Ключевые слова
`airplay`, `android-tv`, `google-tv`, `screen-mirroring`, `airplay-receiver`, `apple-tv-emulator`, `media-codec`, `h264`, `opendisplay`, `ad-free`, `open-source`, `аирплей`, `трансляция-экрана`, `повтор-экрана`, `андроид-тв`.

### License
GPL-3.0 License. Based on [GodMingFeng/airplay-android](https://github.com/GodMingFeng/airplay-android) & [serezhka/java-airplay-lib](https://github.com/serezhka/java-airplay-lib).
