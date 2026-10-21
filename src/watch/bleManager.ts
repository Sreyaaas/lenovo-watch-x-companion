import {
  LENOVO_WATCH_UUIDS,
  DeviceTelemetry,
  createAuthPacket,
  createFirmwareQueryPacket,
  createBatteryQueryPacket,
  createSetTimePacket,
  createCalibrationInitPacket,
  createCalibrationKeepAlivePacket,
  createCalibrationPositionPacket,
  createNotificationPacket,
  createSetAlarmPacket,
  createFitnessGoalPacket,
  createQueryStepsPacket,
  createQueryHeartRatePacket,
  createDndPacket,
  createLiftToWakePacket,
  createEnableNotificationsPacket,
  parseIncomingPacket,
} from './protocol';

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'simulated';

export interface PacketLogEntry {
  id: string;
  timestamp: string;
  direction: 'TX' | 'RX';
  hex: string;
  description: string;
}

export type BleEventListener = (data: any) => void;

class BleManager {
  private device: BluetoothDevice | null = null;
  private writeChar: BluetoothRemoteGATTCharacteristic | null = null;
  private notifyChar: BluetoothRemoteGATTCharacteristic | null = null;
  private seq = 1;

  public state: ConnectionState = 'disconnected';
  public telemetry: DeviceTelemetry = {
    firmwareVersion: '1.2.8',
    battery: { levelPercent: 88, isNormal: true },
    todaySteps: 4320,
    lastHeartRate: 72,
    findPhoneActive: false,
  };

  public logs: PacketLogEntry[] = [];
  private listeners: Map<string, Set<BleEventListener>> = new Map();
  private isSimulation = false;
  private simInterval: any = null;

  constructor() {
    // Check if web bluetooth is supported
    if (typeof navigator !== 'undefined' && !navigator.bluetooth) {
      console.warn('Web Bluetooth API is not available on this browser.');
    }
  }

  public on(event: string, fn: BleEventListener) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(fn);
  }

  public off(event: string, fn: BleEventListener) {
    this.listeners.get(event)?.delete(fn);
  }

  private emit(event: string, data?: any) {
    this.listeners.get(event)?.forEach((fn) => {
      try {
        fn(data);
      } catch (err) {
        console.error('Error in BLE event listener', err);
      }
    });
  }

  private log(direction: 'TX' | 'RX', bytes: Uint8Array, description: string) {
    const hex = Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
      .join(' ');
    const entry: PacketLogEntry = {
      id: Math.random().toString(36).substring(2, 9),
      timestamp: new Date().toLocaleTimeString(),
      direction,
      hex,
      description,
    };
    this.logs.unshift(entry);
    if (this.logs.length > 100) {
      this.logs.pop();
    }
    this.emit('log', entry);
  }

  /**
   * Connect to real physical Lenovo Watch X via Web Bluetooth
   */
  public async connect(): Promise<boolean> {
    if (typeof navigator === 'undefined' || !navigator.bluetooth) {
      throw new Error(
        'Web Bluetooth is not supported on this browser. Try Google Chrome or Microsoft Edge on Android/PC.'
      );
    }

    try {
      this.state = 'connecting';
      this.emit('state', this.state);

      this.device = await navigator.bluetooth.requestDevice({
        filters: [
          { namePrefix: 'Watch' },
          { namePrefix: 'WATCH' },
          { namePrefix: 'Lenovo' },
          { services: [LENOVO_WATCH_UUIDS.SERVICE] },
        ],
        optionalServices: [LENOVO_WATCH_UUIDS.SERVICE],
      });

      this.device.addEventListener('gattserverdisconnected', () => {
        this.handleDisconnect();
      });

      const server = await this.device.gatt?.connect();
      if (!server) throw new Error('Could not connect to GATT Server.');

      const service = await server.getPrimaryService(LENOVO_WATCH_UUIDS.SERVICE);
      this.writeChar = await service.getCharacteristic(LENOVO_WATCH_UUIDS.WRITE);
      this.notifyChar = await service.getCharacteristic(LENOVO_WATCH_UUIDS.NOTIFY);

      // Start notifications
      await this.notifyChar.startNotifications();
      this.notifyChar.addEventListener('characteristicvaluechanged', (e: any) => {
        const val = e.target.value as DataView;
        const buf = new Uint8Array(val.buffer);
        this.handleIncoming(buf);
      });

      this.state = 'connected';
      this.isSimulation = false;
      this.emit('state', this.state);

      // Initialization handshake pipeline
      await this.sendPacket(createAuthPacket(this.seq++, false), 'Handshake Auth Request');
      await this.sleep(150);
      await this.sendPacket(createEnableNotificationsPacket(this.seq++), 'Enable Notification Channels');
      await this.sleep(150);
      await this.sendPacket(createFirmwareQueryPacket(this.seq++), 'Query Firmware Version');
      await this.sleep(150);
      await this.sendPacket(createBatteryQueryPacket(this.seq++), 'Query Battery State');
      await this.sleep(150);
      await this.sendPacket(createQueryStepsPacket(this.seq++), 'Query Daily Steps');

      return true;
    } catch (err: any) {
      this.state = 'disconnected';
      this.emit('state', this.state);
      throw err;
    }
  }

  /**
   * Start local simulation mode for instant preview & testing without physical device
   */
  public startSimulation() {
    this.disconnect();
    this.isSimulation = true;
    this.state = 'simulated';
    this.emit('state', this.state);

    this.log(
      'RX',
      new Uint8Array([0x23, 0x01, 0x05, 0x13, 0x01, 0x08, 0x01, 0x02, 0x01, 0x02, 0x08, 0x3a]),
      'Simulated Watch Initialized (Firmware 1.2.8)'
    );

    // Periodic simulation of steps
    if (this.simInterval) clearInterval(this.simInterval);
    this.simInterval = setInterval(() => {
      if (this.state === 'simulated') {
        this.telemetry.todaySteps += Math.floor(Math.random() * 5);
        this.emit('telemetry', { ...this.telemetry });
      }
    }, 5000);
  }

  public disconnect() {
    if (this.simInterval) {
      clearInterval(this.simInterval);
      this.simInterval = null;
    }
    if (this.device && this.device.gatt?.connected) {
      this.device.gatt.disconnect();
    }
    this.handleDisconnect();
  }

  private handleDisconnect() {
    this.state = 'disconnected';
    this.device = null;
    this.writeChar = null;
    this.notifyChar = null;
    this.emit('state', this.state);
  }

  private async sendPacket(packet: Uint8Array, description: string): Promise<void> {
    this.log('TX', packet, description);

    if (this.state === 'simulated') {
      // Simulate response
      setTimeout(() => {
        this.mockResponse(packet, description);
      }, 100);
      return;
    }

    if (!this.writeChar) {
      throw new Error('Watch is not connected.');
    }

    await this.writeChar.writeValue(packet as unknown as BufferSource);
  }

  private handleIncoming(buf: Uint8Array) {
    const parsed = parseIncomingPacket(buf);
    this.log('RX', buf, parsed ? `Received: ${parsed.type}` : 'Received ACK / Unknown');

    if (!parsed) return;

    if (parsed.type === 'BATTERY') {
      this.telemetry.battery = parsed.payload;
      this.emit('telemetry', { ...this.telemetry });
    } else if (parsed.type === 'FIRMWARE') {
      this.telemetry.firmwareVersion = parsed.payload.version;
      this.emit('telemetry', { ...this.telemetry });
    } else if (parsed.type === 'STEPS') {
      this.telemetry.todaySteps = parsed.payload.steps;
      this.emit('telemetry', { ...this.telemetry });
    } else if (parsed.type === 'HEART_RATE') {
      this.telemetry.lastHeartRate = parsed.payload.bpm;
      this.emit('telemetry', { ...this.telemetry });
    } else if (parsed.type === 'FIND_PHONE_TRIGGER') {
      this.triggerFindPhoneAlarm();
    }
  }

  private mockResponse(packet: Uint8Array, description: string) {
    // Generate simulated responses based on what was sent
    if (description.includes('Battery')) {
      const resp = new Uint8Array([0x23, 0x01, 0x04, 0x13, 0x02, 0x08, 0x01, 0x14, 0x01, 0x58, 0x4f]);
      this.handleIncoming(resp);
    } else if (description.includes('Firmware')) {
      const resp = new Uint8Array([0x23, 0x01, 0x05, 0x13, 0x03, 0x08, 0x01, 0x02, 0x01, 0x02, 0x08, 0x3a]);
      this.handleIncoming(resp);
    } else if (description.includes('Heart Rate')) {
      const randomBpm = 68 + Math.floor(Math.random() * 15);
      const resp = new Uint8Array([0x23, 0x01, 0x03, 0x13, 0x04, 0x80, 0x15, 0x03, randomBpm, 0x12]);
      this.handleIncoming(resp);
    }
  }

  /**
   * Action: Sync current device atomic time to watch
   */
  public async syncTime(date: Date = new Date()): Promise<void> {
    const packet = createSetTimePacket(this.seq++, date);
    await this.sendPacket(packet, `Sync Clock -> ${date.toLocaleTimeString()}`);
  }

  /**
   * Action: Calibrate mechanical hands
   */
  public async calibrateHands(hour: number, minute: number, second = 0): Promise<void> {
    // 1. Enter calibration mode
    await this.sendPacket(createCalibrationInitPacket(this.seq++, true), 'Start Stepper Calibration Mode');
    await this.sleep(200);

    // 2. Keep-alive heartbeat
    await this.sendPacket(createCalibrationKeepAlivePacket(this.seq++), 'Calibration Keep-Alive Ping');
    await this.sleep(200);

    // 3. Send physical position of the hands
    await this.sendPacket(
      createCalibrationPositionPacket(this.seq++, hour, minute, second),
      `Set Stepper Current Position (${hour}:${minute.toString().padStart(2, '0')})`
    );
    await this.sleep(400);

    // 4. Send exact current real time so the watch calculates delta and rotates steppers
    await this.syncTime(new Date());
    await this.sleep(300);

    // 5. Exit calibration mode
    await this.sendPacket(createCalibrationInitPacket(this.seq++, false), 'Finish Stepper Calibration');
  }

  /**
   * Action: Send custom text notification to watch OLED screen
   */
  public async pushNotification(title: string, body: string, channel = 7): Promise<void> {
    const packet = createNotificationPacket(this.seq++, title, body, channel);
    await this.sendPacket(packet, `OLED Alert: "${title}" - "${body.slice(0, 20)}..."`);
  }

  /**
   * Action: Set Hardware Alarm
   */
  public async setAlarm(
    slot: number,
    hours: number,
    minutes: number,
    days: boolean[],
    enabled: boolean
  ): Promise<void> {
    const packet = createSetAlarmPacket(this.seq++, slot, hours, minutes, days, enabled);
    await this.sendPacket(
      packet,
      `Alarm ${slot}: ${hours}:${minutes.toString().padStart(2, '0')} (${enabled ? 'ON' : 'OFF'})`
    );
  }

  /**
   * Action: Set Step Goal
   */
  public async setDailyStepGoal(steps: number): Promise<void> {
    const packet = createFitnessGoalPacket(this.seq++, steps);
    await this.sendPacket(packet, `Set Daily Step Goal: ${steps}`);
  }

  /**
   * Action: Trigger Heart Rate Measurement
   */
  public async measureHeartRate(): Promise<void> {
    const packet = createQueryHeartRatePacket(this.seq++);
    await this.sendPacket(packet, 'Trigger Heart Rate Sensor');
  }

  /**
   * Action: Set DND
   */
  public async setDnd(enabled: boolean): Promise<void> {
    const packet = createDndPacket(this.seq++, enabled);
    await this.sendPacket(packet, `Do Not Disturb: ${enabled ? 'Enabled' : 'Disabled'}`);
  }

  /**
   * Action: Set Lift to Wake
   */
  public async setLiftToWake(enabled: boolean): Promise<void> {
    const packet = createLiftToWakePacket(this.seq++, enabled);
    await this.sendPacket(packet, `Lift Wrist to Wake: ${enabled ? 'Enabled' : 'Disabled'}`);
  }

  /**
   * Action: Trigger "Find My Watch" (Vibrates watch & lights OLED)
   */
  public async findMyWatch(): Promise<void> {
    await this.pushNotification('FIND WATCH', 'LOCATING...', 1024);
  }

  private triggerFindPhoneAlarm() {
    this.telemetry.findPhoneActive = true;
    this.emit('findPhone', true);

    // Play alert sound if audio context available
    try {
      const audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.type = 'sine';
      osc.frequency.setValueAtTime(880, audioCtx.currentTime); // A5
      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      osc.start();
      setTimeout(() => {
        osc.stop();
        audioCtx.close();
      }, 1500);
    } catch (e) {
      console.log('Audio alert fallback');
    }
  }

  private sleep(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}

export const bleManager = new BleManager();
