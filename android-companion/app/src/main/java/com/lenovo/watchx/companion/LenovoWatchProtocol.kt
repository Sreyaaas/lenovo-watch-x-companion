package com.lenovo.watchx.companion

import java.util.Calendar
import java.util.UUID

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
    val CMD_FITNESS_GOAL = byteArrayOf(0x10, 0x02)

    fun calculateChecksum(bytes: ByteArray): Byte {
        var checksum = 0
        for (i in 0 until bytes.size - 1) {
            checksum += (bytes[i].toInt() xor i) and 0xFF
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

    fun buildAuthPacket(seq: Int): ByteArray {
        return buildPacket(byteArrayOf(0x01, 0x05, 0x01), ACTION_TASK, seq)
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

    fun buildCalibrationPositionPacket(hour: Int, minute: Int, seq: Int): ByteArray {
        val pos = ((hour % 12) * 60 + minute) * 60
        val high = ((pos shr 8) and 0xFF).toByte()
        val low = (pos and 0xFF).toByte()
        return buildPacket(byteArrayOf(0x03, 0x33, 0x01, high, low), ACTION_TASK, seq)
    }
}
