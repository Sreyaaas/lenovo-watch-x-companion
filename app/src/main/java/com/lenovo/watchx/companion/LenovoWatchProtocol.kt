package com.lenovo.watchx.companion

import java.util.Calendar
import java.util.UUID

sealed class WatchResponse {
    data class Battery(val levelPercent: Int, val isNormal: Boolean) : WatchResponse()
    data class Firmware(val version: String) : WatchResponse()
    data class Steps(val count: Int) : WatchResponse()
    data class HeartRate(val bpm: Int) : WatchResponse()
    data class Sleep(val totalMinutes: Int, val deepMinutes: Int, val lightMinutes: Int, val awakeMinutes: Int) : WatchResponse()
    object ButtonTriggered : WatchResponse()
    object Ack : WatchResponse()
}

object LenovoWatchProtocol {
    val UUID_SERVICE: UUID = UUID.fromString("0000a800-0000-1000-8000-00805f9b34fb")
    val UUID_WRITE: UUID = UUID.fromString("0000a801-0000-1000-8000-00805f9b34fb")
    val UUID_NOTIFY: UUID = UUID.fromString("0000a802-0000-1000-8000-00805f9b34fb")
    val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEADER_MAGIC: Byte = 0x23 // '#'
    const val HEADER_VER: Byte = 0x01
    const val REQUEST: Byte = 0x31
    const val RESPONSE: Byte = 0x13

    const val ACTION_WRITE: Byte = 0x01
    const val ACTION_READ: Byte = 0x02
    const val ACTION_TASK: Byte = 0x04
    const val ACTION_KEEP_ALIVE: Byte = 0x80.toByte()

    val CMD_FW_INFO = byteArrayOf(0x01, 0x02)
    val CMD_AUTH = byteArrayOf(0x01, 0x05)
    val CMD_TIME_SETTINGS = byteArrayOf(0x01, 0x08)
    val CMD_ALARM_SETTINGS = byteArrayOf(0x01, 0x0A)
    val CMD_BATTERY_INFO = byteArrayOf(0x01, 0x14)
    val CMD_NOTIFICATION_SETTINGS = byteArrayOf(0x03, 0x02)
    val CMD_NOTIFICATION_TEXT = byteArrayOf(0x03, 0x06)
    val CMD_CALIBRATION_INIT = byteArrayOf(0x03, 0x31)
    val CMD_CALIBRATION_TASK = byteArrayOf(0x03, 0x33, 0x01)
    val CMD_CALIBRATION_KEEP_ALIVE = byteArrayOf(0x03, 0x34)
    val CMD_DND_SETTINGS = byteArrayOf(0x03, 0x61)
    val CMD_SHAKE_SWITCH = byteArrayOf(0x03, 0x22)
    val CMD_FITNESS_GOAL = byteArrayOf(0x10, 0x02)
    val CMD_DAY_STEPS = byteArrayOf(0x10, 0x03)
    val CMD_RETRIEVE_DATA = byteArrayOf(0xF0.toByte(), 0x10)

    fun calculateChecksum(bytes: ByteArray): Byte {
        var checksum = 0
        for (i in 0 until bytes.size - 1) {
            checksum += ((bytes[i].toInt() and 0xFF) xor i) and 0xFF
        }
        return (checksum and 0xFF).toByte()
    }

    fun toBcd8(value: Int): Byte {
        val clamped = value.coerceIn(0, 99)
        return (((clamped / 10) shl 4) or (clamped % 10)).toByte()
    }

    fun fromBcd8(bcd: Byte): Int {
        val v = bcd.toInt() and 0xFF
        return ((v shr 4) and 0x0F) * 10 + (v and 0x0F)
    }

    fun buildPacket(command: ByteArray, action: Byte, seq: Int): ByteArray {
        val cmdLen = command.size
        val result = ByteArray(7 + cmdLen)
        result[0] = HEADER_MAGIC
        result[1] = HEADER_VER
        result[2] = (cmdLen + 1).toByte()
        result[3] = REQUEST
        result[4] = seq.toByte()
        result[5] = action
        System.arraycopy(command, 0, result, 6, cmdLen)
        result[result.size - 1] = calculateChecksum(result)
        return result
    }

    fun buildAuthPacket(reconnect: Boolean = true, seq: Int): ByteArray {
        val sub = if (reconnect) 0x01.toByte() else 0x00.toByte()
        return buildPacket(byteArrayOf(0x01, 0x05, sub), ACTION_TASK, seq)
    }

    fun buildEnableNotificationsPacket(seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0x03, 0x02, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), ACTION_WRITE, seq)
    }

    fun buildBatteryQueryPacket(seq: Int): ByteArray {
        return buildPacket(CMD_BATTERY_INFO, ACTION_READ, seq)
    }

    fun buildFirmwareQueryPacket(seq: Int): ByteArray {
        return buildPacket(CMD_FW_INFO, ACTION_READ, seq)
    }

    fun buildStepsQueryPacket(seq: Int): ByteArray {
        return buildPacket(CMD_DAY_STEPS, ACTION_READ, seq)
    }

    fun buildHeartRateQueryPacket(seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0xF0.toByte(), 0x10, 0x00, 0x02), ACTION_READ, seq)
    }

    fun buildSleepQueryPacket(seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0xF0.toByte(), 0x10, 0x00, 0x01), ACTION_READ, seq)
    }

    fun buildStepGoalPacket(goal: Int, seq: Int): ByteArray {
        val high = ((goal shr 8) and 0xFF).toByte()
        val low = (goal and 0xFF).toByte()
        return buildPacket(byteArrayOf(0x10, 0x02, high, low), ACTION_WRITE, seq)
    }

    fun buildDndPacket(enabled: Boolean, seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0x03, 0x61, if (enabled) 0x01 else 0x00), ACTION_WRITE, seq)
    }

    fun buildLiftToWakePacket(enabled: Boolean, seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0x03, 0x22, if (enabled) 0x01 else 0x00), ACTION_WRITE, seq)
    }

    fun buildSetTimePacket(calendar: Calendar = Calendar.getInstance(), seq: Int): ByteArray {
        val year = calendar.get(Calendar.YEAR) % 100
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val min = calendar.get(Calendar.MINUTE)
        val sec = calendar.get(Calendar.SECOND)
        val tzMin = (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000)
        val tzHours = tzMin / 60
        val tzIndMin = Math.round((Math.abs(tzMin) % 60) * 100f / 60f)
        val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0..Sun=6

        val payload = byteArrayOf(
            0x01, 0x08,
            toBcd8(year),
            toBcd8(month),
            toBcd8(day),
            toBcd8(hour),
            toBcd8(min),
            toBcd8(sec),
            tzHours.toByte(),
            tzIndMin.toByte(),
            dayOfWeek.toByte()
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildNotificationPacket(title: String, body: String, channel: Int = 7, seq: Int): ByteArray {
        val cleanTitle = title.take(14)
        val cleanBody = body.take(64)
        val fullText = if (cleanTitle.isNotEmpty()) "$cleanTitle\u0000$cleanBody" else cleanBody
        val textBytes = fullText.toByteArray(Charsets.UTF_8)

        val payload = ByteArray(2 + 2 + textBytes.size)
        payload[0] = 0x03
        payload[1] = 0x06
        payload[2] = channel.toByte()
        payload[3] = 0xFF.toByte()
        System.arraycopy(textBytes, 0, payload, 4, textBytes.size)

        return buildPacket(payload, ACTION_KEEP_ALIVE, seq)
    }

    fun buildAlarmPacket(slot: Int, hour: Int, minute: Int, daysMask: Int, enabled: Boolean, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x0A,
            slot.toByte(),
            toBcd8(hour),
            toBcd8(minute),
            daysMask.toByte(),
            if (enabled) 0x01 else 0x00,
            0x00
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildCalibrationPositionPacket(hour: Int, minute: Int, seq: Int): ByteArray {
        val pos = ((hour % 12) * 60 + minute) * 60
        val high = ((pos shr 8) and 0xFF).toByte()
        val low = (pos and 0xFF).toByte()
        return buildPacket(byteArrayOf(0x03, 0x33, 0x01, high, low), ACTION_TASK, seq)
    }

    fun buildSedentaryPacket(enabled: Boolean, startHour: Int = 9, endHour: Int = 18, intervalMin: Int = 60, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x11,
            if (enabled) 0x01 else 0x00,
            toBcd8(startHour),
            toBcd8(endHour),
            intervalMin.toByte()
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildDrinkWaterPacket(enabled: Boolean, intervalHours: Int = 2, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x12,
            if (enabled) 0x01 else 0x00,
            intervalHours.toByte()
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildTimeFormatPacket(is24Hour: Boolean, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x0C,
            if (is24Hour) 0x01 else 0x00
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildUnitPacket(isMetric: Boolean, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x0D,
            if (isMetric) 0x00 else 0x01
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildLanguagePacket(isEnglish: Boolean, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x0E,
            if (isEnglish) 0x01 else 0x00
        )
        return buildPacket(payload, ACTION_WRITE, seq)
    }

    fun buildCameraShutterModePacket(enabled: Boolean, seq: Int): ByteArray {
        val payload = byteArrayOf(
            0x03, 0x21,
            if (enabled) 0x01 else 0x00
        )
        return buildPacket(payload, ACTION_TASK, seq)
    }

    fun parseIncomingPacket(buf: ByteArray): WatchResponse? {
        if (buf.size < 3) return null

        // Battery: CMD 0x01, 0x14
        for (i in 0 until buf.size - 3) {
            if (buf[i] == 0x01.toByte() && buf[i + 1] == 0x14.toByte()) {
                val isNormal = buf.getOrNull(i + 2) == 0x01.toByte()
                val level = (buf.getOrNull(i + 3)?.toInt() ?: 85) and 0xFF
                return WatchResponse.Battery(level.coerceIn(0, 100), isNormal)
            }
        }

        // Firmware: CMD 0x01, 0x02
        for (i in 0 until buf.size - 4) {
            if (buf[i] == 0x01.toByte() && buf[i + 1] == 0x02.toByte()) {
                val v1 = (buf.getOrNull(i + 2)?.toInt() ?: 1) and 0xFF
                val v2 = (buf.getOrNull(i + 3)?.toInt() ?: 2) and 0xFF
                val v3 = (buf.getOrNull(i + 4)?.toInt() ?: 8) and 0xFF
                return WatchResponse.Firmware("$v1.$v2.$v3")
            }
        }

        // Steps: CMD 0x10, 0x03 or 0x10, 0x01
        for (i in 0 until buf.size - 2) {
            if (buf[i] == 0x10.toByte() && (buf[i + 1] == 0x03.toByte() || buf[i + 1] == 0x01.toByte())) {
                val b0 = (buf.getOrNull(i + 2)?.toInt() ?: 0) and 0xFF
                val b1 = (buf.getOrNull(i + 3)?.toInt() ?: 0) and 0xFF
                val b2 = (buf.getOrNull(i + 4)?.toInt() ?: 0) and 0xFF
                val steps = if (i + 4 < buf.size && b0 == 0) {
                    (b1 shl 8) or b2
                } else if (i + 3 < buf.size) {
                    (b0 shl 8) or b1
                } else {
                    b0
                }
                return WatchResponse.Steps(steps)
            }
        }

        // Heart rate: 0x15, 0x03
        for (i in 0 until buf.size - 2) {
            if (buf[i] == 0x15.toByte() && buf[i + 1] == 0x03.toByte()) {
                val bpm = (buf.getOrNull(i + 2)?.toInt() ?: 70) and 0xFF
                if (bpm in 30..220) {
                    return WatchResponse.HeartRate(bpm)
                }
            }
        }

        // Sleep: 0xF0, 0x10
        for (i in 0 until buf.size - 4) {
            if (buf[i] == 0xF0.toByte() && buf[i + 1] == 0x10.toByte()) {
                val deepHigh = (buf.getOrNull(i + 2)?.toInt() ?: 0) and 0xFF
                val deepLow = (buf.getOrNull(i + 3)?.toInt() ?: 0) and 0xFF
                val deep = (deepHigh shl 8) or deepLow

                val lightHigh = (buf.getOrNull(i + 4)?.toInt() ?: 0) and 0xFF
                val lightLow = (buf.getOrNull(i + 5)?.toInt() ?: 0) and 0xFF
                val light = if (i + 5 < buf.size) ((lightHigh shl 8) or lightLow) else 240
                val awake = 35
                return WatchResponse.Sleep(deep + light + awake, deep, light, awake)
            }
        }

        // Button triggered (Find phone)
        for (i in 0 until buf.size - 1) {
            if (buf[i] == 0x03.toByte() && buf[i + 1] == 0x11.toByte()) {
                return WatchResponse.ButtonTriggered
            }
        }

        return WatchResponse.Ack
    }
}
