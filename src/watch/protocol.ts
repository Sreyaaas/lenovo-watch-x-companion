/**
 * Lenovo Watch X / Watch X Plus BLE Protocol Implementation
 * Reverse engineered from manufacturer firmware & open-source GATT specs.
 *
 * Characteristics:
 * - Service: 0000a800-0000-1000-8000-00805f9b34fb
 * - TX (Write): 0000a801-0000-1000-8000-00805f9b34fb
 * - RX (Notify): 0000a802-0000-1000-8000-00805f9b34fb
 */

export const LENOVO_WATCH_UUIDS = {
  SERVICE: '0000a800-0000-1000-8000-00805f9b34fb',
  WRITE: '0000a801-0000-1000-8000-00805f9b34fb',
  NOTIFY: '0000a802-0000-1000-8000-00805f9b34fb',
  CCCD: '00002902-0000-1000-8000-00805f9b34fb',
} as const;

export const PROTOCOL = {
  HEADER_MAGIC: 0x23, // '#'
  HEADER_VER: 0x01,
  REQUEST: 0x31,
  RESPONSE: 0x13,

  // Action opcodes
  ACTION_WRITE: 0x01,
  ACTION_READ: 0x02,
  ACTION_TASK: 0x04,
  ACTION_KEEP_ALIVE: 0x80,

  // Command IDs
  CMD_FW_INFO: [0x01, 0x02],
  CMD_AUTH: [0x01, 0x05],
  CMD_TIME_SETTINGS: [0x01, 0x08],
  CMD_ALARM_SETTINGS: [0x01, 0x0a],
  CMD_BATTERY_INFO: [0x01, 0x14],
  CMD_NOTIFICATION_SETTINGS: [0x03, 0x02],
  CMD_NOTIFICATION_TEXT: [0x03, 0x06],
  CMD_NOTIFICATION_CANCEL: [0x03, 0x08],
  CMD_CALIBRATION_INIT: [0x03, 0x31],
  CMD_CALIBRATION_TASK: [0x03, 0x33, 0x01],
  CMD_CALIBRATION_KEEP_ALIVE: [0x03, 0x34],
  CMD_DND_SETTINGS: [0x03, 0x61],
  CMD_FITNESS_GOAL: [0x10, 0x02],
  CMD_DAY_STEPS: [0x10, 0x03],
  CMD_RETRIEVE_DATA: [0xf0, 0x10],
  CMD_RETRIEVE_DATA_DETAILS: [0xf0, 0x11],
  CMD_SHAKE_SWITCH: [0x03, 0x22],
  CMD_DISCONNECT_REMIND: [0x03, 0x21],

  // Response prefixes
  RESP_AUTH: [0x01, 0x01, 0x05],
  RESP_FW_INFO: [0x08, 0x01, 0x02],
  RESP_TIME: [0x08, 0x01, 0x08],
  RESP_BATTERY: [0x08, 0x01, 0x14],
  RESP_DAY_STEPS: [0x08, 0x10, 0x03],
  RESP_BUTTON_EVENT: [0x04, 0x03, 0x11],
  RESP_ALARM_RING: [0x80, 0x01, 0x0a],
  RESP_HEART_RATE_STREAM: [0x80, 0x15, 0x03],
} as const;

export interface BatteryStatus {
  levelPercent: number;
  isNormal: boolean;
}

export interface DeviceTelemetry {
  firmwareVersion: string;
  battery: BatteryStatus;
  todaySteps: number;
  lastHeartRate: number | null;
  findPhoneActive: boolean;
}

/**
 * Calculates Lenovo Watch X packet checksum:
 * checksum = sum((byte[i] ^ i) & 0xFF) & 0xFF
 */
export function calculateChecksum(bytes: Uint8Array): number {
  let checksum = 0;
  for (let i = 0; i < bytes.length - 1; i++) {
    checksum += (bytes[i] ^ i) & 0xff;
  }
  return checksum & 0xff;
}

/**
 * Encodes decimal into Binary Coded Decimal (BCD8)
 * e.g. 45 -> 0x45 (69 dec)
 */
export function toBcd8(val: number): number {
  const clamped = Math.max(0, Math.min(99, Math.floor(val)));
  return ((Math.floor(clamped / 10) << 4) | (clamped % 10)) & 0xff;
}

/**
 * Decodes BCD8 into integer
 */
export function fromBcd8(bcd: number): number {
  return ((bcd >> 4) & 0x0f) * 10 + (bcd & 0x0f);
}

/**
 * Builds a standardized framing packet
 */
export function buildPacket(
  commandBytes: number[],
  action: number,
  sequenceNumber: number
): Uint8Array {
  const cmdLen = commandBytes.length;
  // Total length = 5 (header) + 1 (action) + cmdLen + 1 (checksum) = 7 + cmdLen
  const buf = new Uint8Array(7 + cmdLen);
  buf[0] = PROTOCOL.HEADER_MAGIC;
  buf[1] = PROTOCOL.HEADER_VER;
  buf[2] = (cmdLen + 1) & 0xff; // Payload length (action + command)
  buf[3] = PROTOCOL.REQUEST; // 0x31
  buf[4] = sequenceNumber & 0xff;
  buf[5] = action & 0xff;

  for (let i = 0; i < cmdLen; i++) {
    buf[6 + i] = commandBytes[i] & 0xff;
  }

  buf[buf.length - 1] = calculateChecksum(buf);
  return buf;
}

/**
 * Command: Initial authorization / handshake
 */
export function createAuthPacket(seq: number, firstConnect = false): Uint8Array {
  return buildPacket(
    [...PROTOCOL.CMD_AUTH, firstConnect ? 0x00 : 0x01],
    PROTOCOL.ACTION_TASK,
    seq
  );
}

/**
 * Command: Request firmware version
 */
export function createFirmwareQueryPacket(seq: number): Uint8Array {
  return buildPacket([...PROTOCOL.CMD_FW_INFO], PROTOCOL.ACTION_READ, seq);
}

/**
 * Command: Request battery status
 */
export function createBatteryQueryPacket(seq: number): Uint8Array {
  return buildPacket([...PROTOCOL.CMD_BATTERY_INFO], PROTOCOL.ACTION_READ, seq);
}

/**
 * Command: Synchronize clock and date
 */
export function createSetTimePacket(seq: number, date: Date = new Date()): Uint8Array {
  const year = date.getFullYear() % 100;
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const hours = date.getHours();
  const minutes = date.getMinutes();
  const seconds = date.getSeconds();
  const dayOfWeek = (date.getDay() + 6) % 7; // Monday=0..Sunday=6

  const tzMinutes = -date.getTimezoneOffset();
  const tzHours = Math.floor(tzMinutes / 60);
  const tzIndustrialMinutes = Math.round((Math.abs(tzMinutes) % 60) * 100 / 60);

  const payload = [
    ...PROTOCOL.CMD_TIME_SETTINGS,
    toBcd8(year),
    toBcd8(month),
    toBcd8(day),
    toBcd8(hours),
    toBcd8(minutes),
    toBcd8(seconds),
    tzHours & 0xff,
    tzIndustrialMinutes & 0xff,
    dayOfWeek & 0xff,
  ];

  return buildPacket(payload, PROTOCOL.ACTION_WRITE, seq);
}

/**
 * Mechanical hand calibration:
 * 1. enableCalibration(true)
 * 2. sendCalibrationPosition(hour, min, sec) -> ((hour%12)*3600 + min*60 + sec)
 * 3. setTime(realDate)
 * 4. enableCalibration(false)
 */
export function createCalibrationInitPacket(seq: number, enable: boolean): Uint8Array {
  return buildPacket(
    [...PROTOCOL.CMD_CALIBRATION_INIT, enable ? 0x01 : 0x00],
    PROTOCOL.ACTION_TASK,
    seq
  );
}

export function createCalibrationKeepAlivePacket(seq: number): Uint8Array {
  return buildPacket([...PROTOCOL.CMD_CALIBRATION_KEEP_ALIVE], PROTOCOL.ACTION_KEEP_ALIVE, seq);
}

export function createCalibrationPositionPacket(
  seq: number,
  hour: number,
  minute: number,
  second = 0
): Uint8Array {
  const handsSeconds = ((hour % 12) * 60 + minute) * 60 + second;
  const high = (handsSeconds >> 8) & 0xff;
  const low = handsSeconds & 0xff;
  return buildPacket([...PROTOCOL.CMD_CALIBRATION_TASK, high, low], PROTOCOL.ACTION_TASK, seq);
}

/**
 * Notification Push to OLED Display
 * Channel:
 * 0/7 = Generic message / app alert
 * 10/1024 = Phone call alert
 */
export function createNotificationPacket(
  seq: number,
  title: string,
  body: string,
  channel = 7
): Uint8Array {
  const cleanTitle = title.slice(0, 14);
  const cleanBody = body.slice(0, 64);
  const fullText = cleanTitle ? `${cleanTitle}\0${cleanBody}` : cleanBody;
  const encoder = new TextEncoder();
  const textBytes = Array.from(encoder.encode(fullText));

  const payload = [
    ...PROTOCOL.CMD_NOTIFICATION_TEXT,
    channel & 0xff,
    0xff, // End of multi-part marker
    ...textBytes,
  ];

  return buildPacket(payload, PROTOCOL.ACTION_KEEP_ALIVE, seq);
}

/**
 * Hardware Alarm (Up to 3 slots)
 * repetitionMask: bit 7 = isRepetitive, bit 1..6 = Mon..Sat, bit 0 = Sun
 */
export function createSetAlarmPacket(
  seq: number,
  slotIndex: number, // 1, 2, or 3
  hours: number,
  minutes: number,
  days: boolean[], // [Sun, Mon, Tue, Wed, Thu, Fri, Sat]
  enabled: boolean
): Uint8Array {
  let mask = 0;
  if (days.some((d) => d)) {
    mask |= 0x80; // repetitive
  }
  // Days Mon(1)..Sat(6) mapped to bits 1..6
  for (let i = 1; i <= 6; i++) {
    if (days[i]) mask |= 1 << i;
  }
  // Sun mapped to bit 0
  if (days[0]) mask |= 0x01;

  const payload = [
    ...PROTOCOL.CMD_ALARM_SETTINGS,
    slotIndex & 0xff,
    toBcd8(hours),
    toBcd8(minutes),
    mask & 0xff,
    enabled ? 0x01 : 0x00,
    0x00,
  ];

  return buildPacket(payload, PROTOCOL.ACTION_WRITE, seq);
}

/**
 * Configure Daily Steps Goal
 */
export function createFitnessGoalPacket(seq: number, stepGoal: number): Uint8Array {
  const high = (stepGoal >> 8) & 0xff;
  const low = stepGoal & 0xff;
  return buildPacket([...PROTOCOL.CMD_FITNESS_GOAL, high, low], PROTOCOL.ACTION_WRITE, seq);
}

/**
 * Query Today Steps
 */
export function createQueryStepsPacket(seq: number): Uint8Array {
  return buildPacket([...PROTOCOL.CMD_DAY_STEPS], PROTOCOL.ACTION_READ, seq);
}

/**
 * Query Real-Time / Stored Heart Rate
 */
export function createQueryHeartRatePacket(seq: number): Uint8Array {
  return buildPacket([...PROTOCOL.CMD_RETRIEVE_DATA, 0x00, 0x02], PROTOCOL.ACTION_READ, seq);
}

/**
 * Toggle Do Not Disturb (DND)
 */
export function createDndPacket(seq: number, enabled: boolean): Uint8Array {
  return buildPacket(
    [...PROTOCOL.CMD_DND_SETTINGS, enabled ? 0x01 : 0x00],
    PROTOCOL.ACTION_WRITE,
    seq
  );
}

/**
 * Toggle Lift Wrist to Display
 */
export function createLiftToWakePacket(seq: number, enabled: boolean): Uint8Array {
  return buildPacket(
    [...PROTOCOL.CMD_SHAKE_SWITCH, enabled ? 0x01 : 0x00],
    PROTOCOL.ACTION_WRITE,
    seq
  );
}

/**
 * Enable All Notification Channels
 */
export function createEnableNotificationsPacket(seq: number): Uint8Array {
  return buildPacket(
    [...PROTOCOL.CMD_NOTIFICATION_SETTINGS, 0xff, 0xff, 0xff, 0xff],
    PROTOCOL.ACTION_WRITE,
    seq
  );
}

/**
 * Packet Parser: Extracts data from incoming notifications
 */
export function parseIncomingPacket(buf: Uint8Array): {
  type: string;
  payload?: any;
} | null {
  if (buf.length < 6 || buf[0] !== PROTOCOL.HEADER_MAGIC) {
    return null;
  }

  // Battery response: e.g. [..., 0x08, 0x01, 0x14, isNormal, percent, ...]
  for (let i = 0; i < buf.length - 4; i++) {
    if (buf[i] === 0x08 && buf[i + 1] === 0x01 && buf[i + 2] === 0x14) {
      const isNormal = buf[i + 3] === 0x01;
      const levelPercent = buf[i + 4];
      return {
        type: 'BATTERY',
        payload: { isNormal, levelPercent },
      };
    }
  }

  // Firmware info: [..., 0x08, 0x01, 0x02, vMajor, vMinor, vPatch, ...]
  for (let i = 0; i < buf.length - 5; i++) {
    if (buf[i] === 0x08 && buf[i + 1] === 0x01 && buf[i + 2] === 0x02) {
      const version = `${buf[i + 3]}.${buf[i + 4]}.${buf[i + 5]}`;
      return {
        type: 'FIRMWARE',
        payload: { version },
      };
    }
  }

  // Steps indicator: [..., 0x08, 0x10, 0x03, stepsHigh, stepsLow, ...]
  for (let i = 0; i < buf.length - 4; i++) {
    if (buf[i] === 0x08 && buf[i + 1] === 0x10 && buf[i + 2] === 0x03) {
      const steps = (buf[i + 3] << 8) | buf[i + 4];
      return {
        type: 'STEPS',
        payload: { steps },
      };
    }
  }

  // Heart rate: [..., 0x80, 0x15, 0x03, bpm, ...]
  for (let i = 0; i < buf.length - 3; i++) {
    if (buf[i] === 0x80 && buf[i + 1] === 0x15 && buf[i + 2] === 0x03) {
      const bpm = buf[i + 3];
      return {
        type: 'HEART_RATE',
        payload: { bpm },
      };
    }
  }

  // Button pressed indicator (Find phone triggered from watch): [0x04, 0x03, 0x11]
  for (let i = 0; i < buf.length - 2; i++) {
    if (buf[i] === 0x04 && buf[i + 1] === 0x03 && buf[i + 2] === 0x11) {
      return {
        type: 'FIND_PHONE_TRIGGER',
      };
    }
  }

  return { type: 'GENERIC_ACK' };
}
