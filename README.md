# Chronos X — Lenovo Watch X / Watch X Plus Companion Studio

> A 100% local, privacy-focused, zero-bloat companion app and Bluetooth Low Energy (BLE) protocol suite for the **Lenovo Watch X** and **Watch X Plus** hybrid smartwatches.

[![Build Android APK](https://github.com/Sreyaaas/lenovo-watch-x-companion/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Sreyaaas/lenovo-watch-x-companion/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Web Bluetooth](https://img.shields.io/badge/Web%20Bluetooth-Supported-brightgreen)](https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API)

---

## 🌟 Why Chronos X?

The official Lenovo Watch companion software was removed from app stores over 8 years ago. The legacy app was plagued by:
- Mandatory Chinese cloud server logins that no longer respond.
- Deprecated Android permissions crashing on Android 12/13/14+.
- Unthrottled background wake locks causing severe battery drain (20–30% daily).

**Chronos X** replaces the vendor app entirely through cleanroom reverse-engineered GATT communication:
- **100% Local & Cloudless:** Zero accounts, zero telemetry, zero background battery waste.
- **Interactive Mechanical Hand Alignment:** Visually calibrate physical watch hands using our interactive SVG clock dial.
- **Custom OLED Notification Studio:** Push custom messages, alerts, and call alerts with real-time 96×32 pixel simulation.
- **Hardware NVRAM Alarms:** Set up to 3 onboard vibration alarms with day-of-week repeat bitmasks.
- **Health Telemetry:** Query PPG optical heart rate and daily pedometer steps directly from onboard sensors.
- **Hardware Controls:** Toggle tilt-to-wake display, disconnect alerts, 12h/24h time format, and "Find My Watch" / "Find My Phone".

---

## 🚀 Usage & Deployment

### 1. Progressive Web App (PWA) / Web Bluetooth
No app installation required. You can run the app directly in **Google Chrome** or **Microsoft Edge** on Android, Windows, macOS, or Linux.

```bash
# Clone the repository
git clone https://github.com/Sreyaaas/lenovo-watch-x-companion.git
cd lenovo-watch-x-companion

# Install dependencies and start
npm install
npm run dev
```

1. Open `http://localhost:5173` in **Chrome on Android** (or PC).
2. Tap **"Connect Watch (BLE)"** and select your `Watch X`.
3. *(Optional)* Tap Chrome's menu (`⋮`) ➔ **"Add to Home Screen"** / **"Install App"** to use it as a standalone native app icon.

---

### 2. Ready-to-Run Android APK (Gadgetbridge)
The open-source community maintains support for the Lenovo Watch X series in **Gadgetbridge**:
- [Download Gadgetbridge on F-Droid](https://f-droid.org/packages/nodomain.freeyourgadget.gadgetbridge/)
- [Download APK from Codeberg Releases](https://codeberg.org/Freeyourgadget/Gadgetbridge/releases)

---

### 3. Native Android Kotlin Project
The repository includes a full Android native project under [`android-companion/`](./android-companion):
- **`LenovoWatchBleService.kt`**: Foreground persistent BLE service with automatic reconnection.
- **`LenovoWatchNotificationListener.kt`**: Captures Android system notifications (WhatsApp, Telegram, Phone calls) and forwards sender + message text to the watch OLED screen.
- **GitHub Actions Workflow**: Pushing to this repo triggers [`.github/workflows/build-apk.yml`](./.github/workflows/build-apk.yml) which automatically compiles and outputs a downloadable APK artifact.

---

## 🔬 Reverse-Engineered Protocol Breakdown

### GATT Hierarchy
- **Primary Service:** `0000a800-0000-1000-8000-00805f9b34fb`
- **Command TX (Write):** `0000a801-0000-1000-8000-00805f9b34fb`
- **Telemetry RX (Notify):** `0000a802-0000-1000-8000-00805f9b34fb`

### Packet Frame Structure
```
[Header: 0x23 0x01] [Length] [Direction: 0x31/0x13] [Seq] [Action] [Command + Payload...] [Checksum]
```
- **Checksum Algorithm:**
  $$\text{Checksum} = \sum_{i=0}^{N-2} (\text{byte}[i] \oplus i) \pmod{256}$$

### Command Matrix
| Command ID | Action | Description | Payload |
| :--- | :--- | :--- | :--- |
| `0x01 0x05` | `0x04` (TASK) | Handshake Authentication | `0x01` (Reconnect) / `0x00` (Pair) |
| `0x01 0x08` | `0x01` (WRITE) | Set Time (Atomic Sync) | 9 bytes BCD: `YY MM DD HH mm ss tzH tzM DOW` |
| `0x01 0x14` | `0x02` (READ) | Battery Status Query | Empty (Replies with `0x08 0x01 0x14 [status] [level%]`) |
| `0x03 0x06` | `0x80` (KEEP_ALIVE) | OLED Notification Push | `[channel] [0xFF] [Title\0Body (UTF-8)]` |
| `0x03 0x31` | `0x04` (TASK) | Calibration Mode Toggle | `0x01` (Enter) / `0x00` (Exit) |
| `0x03 0x33 0x01` | `0x04` (TASK) | Send Hands Position | 16-bit seconds: `((hour % 12) * 3600 + min * 60 + sec)` |
| `0x01 0x0A` | `0x01` (WRITE) | Set Hardware Alarm | `[slot 1-3] [hour BCD] [min BCD] [days mask] [enabled]` |
| `0x10 0x03` | `0x02` (READ) | Daily Step Count | Empty (Replies with 16-bit steps) |
| `0xF0 0x10` | `0x02` (READ) | Optical Heart Rate (PPG) | `0x00 0x02` (Replies with live BPM stream) |

---

## 📄 License
MIT License. Created for the hardware preservation of Lenovo Watch X hybrid wearables.
