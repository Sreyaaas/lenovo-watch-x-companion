# Lenovo Watch X Companion (Watch X)

> A 100% local, privacy-first, zero-bloat native Android companion app for the **Lenovo Watch X** and **Watch X Plus** hybrid smartwatches.

[![Build Android APK](https://github.com/Sreyaaas/lenovo-watch-x-companion/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Sreyaaas/lenovo-watch-x-companion/actions)
[![Latest Release](https://img.shields.io/github/v/release/Sreyaaas/lenovo-watch-x-companion?color=00F0FF&label=APK%20Release)](https://github.com/Sreyaaas/lenovo-watch-x-companion/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)

---

## ⌚ Overview

The official Lenovo Watch app was removed from the Google Play Store over 8 years ago. It relied on obsolete Chinese cloud servers that no longer respond and suffered from battery-draining wake locks.

**Watch X Companion** is a clean, modern native Android app designed to completely restore your Lenovo Watch X hardware with **zero cloud dependencies, zero tracking, and zero bloat**.

---

## ✨ Features & Architecture

### 🚶 Pedometer & Activity Tracking
- Real-time step counter synchronized directly from watch NVRAM.
- Distance (km) and active calorie burn calculations.
- Configurable daily step goals with live radial progress ring.

### 🌙 Sleep Quality Analysis
- Reads recorded sleep sessions from watch EEPROM.
- Visual breakdown of **Deep Sleep**, **Light Sleep**, and **Awake** duration.
- Automated sleep quality score calculation.

### ❤️ Optical PPG Heart Rate
- Live real-time optical PPG measurement trigger.
- Visual pulse heart rate indicator with current BPM reading.
- Session summary tracking Minimum, Average, and Peak heart rates.

### ⏱️ Mechanical Hand Calibration
- Visual mechanical stepper alignment with interactive digital-analog clock interface.
- Granular stepper controls: **±1 hour**, **±5 minutes**, **±1 minute**.
- One-tap atomic time alignment to synchronize physical hands with your phone's system time.

### 🔔 OLED Notification Forwarding
- Real-time forwarding of incoming WhatsApp messages, SMS, and Phone calls to the watch's 96×32 OLED screen.
- Interactive Notification Studio for sending custom test messages with live screen preview.
- Native Android Notification Listener Service running in the background.

### ⚙️ Hardware NVRAM Alarms & Controls
- Configure up to **3 hardware vibration alarms** directly stored in the watch's onboard memory (alarms ring even when disconnected from phone).
- Customizable day-of-week recurrence bitmasks.
- Toggle **Tilt-to-Wake (Raise-to-light)** display and **Do Not Disturb (DND)**.
- **Find My Watch** (triggers strong vibration) & **Find My Phone** (watch physical button triggers phone siren).

---

## 📲 Download & Installation

1. Go to the [**Latest Releases**](https://github.com/Sreyaaas/lenovo-watch-x-companion/releases/latest) page.
2. Download `WatchX-v1.2.0.apk`.
3. Open the APK on your Android device and tap **Install**.
4. Grant Bluetooth and Notification permissions when prompted.
5. Tap **Connect Watch** to scan for your `Watch X` and pair!

---

## 🔬 Reverse-Engineered GATT Protocol

### Communication Characteristics
- **Service UUID:** `0000a800-0000-1000-8000-00805f9b34fb`
- **TX Characteristic (Write):** `0000a801-0000-1000-8000-00805f9b34fb`
- **RX Characteristic (Notify):** `0000a802-0000-1000-8000-00805f9b34fb`

### Packet Framing
```
[Header: 0x23 0x01] [Length] [Direction: 0x31/0x13] [Seq] [Action] [Command + Payload...] [Checksum]
```
- **Checksum Formula:**
  $$\text{Checksum} = \sum_{i=0}^{N-2} (\text{byte}[i] \oplus i) \pmod{256}$$

### Command Mapping
| Command ID | Action | Description | Payload |
| :--- | :--- | :--- | :--- |
| `0x01 0x05` | `0x04` (TASK) | Handshake Authentication | `0x01` (Reconnect) / `0x00` (Pair) |
| `0x01 0x08` | `0x01` (WRITE) | Set Time (Atomic Sync) | 9 bytes BCD: `YY MM DD HH mm ss tzH tzM DOW` |
| `0x01 0x14` | `0x02` (READ) | Battery Status Query | Empty (Replies with `0x08 0x01 0x14 [status] [level%]`) |
| `0x03 0x06` | `0x80` (NOTIFY) | OLED Notification Push | `[channel] [0xFF] [Title\0Body (UTF-8)]` |
| `0x03 0x31` | `0x04` (TASK) | Calibration Mode Toggle | `0x01` (Enter) / `0x00` (Exit) |
| `0x03 0x33 0x01` | `0x04` (TASK) | Send Hands Position | 16-bit seconds: `((hour % 12) * 3600 + min * 60 + sec)` |
| `0x01 0x0A` | `0x01` (WRITE) | Set Hardware Alarm | `[slot 1-3] [hour BCD] [min BCD] [days mask] [enabled]` |
| `0x10 0x03` | `0x02` (READ) | Daily Step Count | Empty (Replies with 16-bit steps) |
| `0xF0 0x10` | `0x02` (READ) | Retrieve Activity Data | `0x00 0x01` (Sleep Records), `0x00 0x02` (Heart Rate BPM) |

---

## 🛠️ Building From Source

```bash
git clone https://github.com/Sreyaaas/lenovo-watch-x-companion.git
cd lenovo-watch-x-companion

# Build debug APK with Gradle
./gradlew assembleDebug
```
The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
MIT License. Created for the hardware preservation of Lenovo Watch X hybrid wearables.
