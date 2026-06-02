/**
 * uno_r4_otg.ino
 *
 * Sends IMU sensor data over USB serial in the format expected by the
 * Edge Impulse Android Data Collector's USB OTG tab.
 *
 * Supported boards — select ONE below:
 *
 *   BOARD_UNO_R4_WIFI   — Arduino UNO R4 WiFi  (built-in LSM6DSOX, native USB-C)
 *   BOARD_UNO_R4_MINIMA — Arduino UNO R4 Minima (no built-in IMU, MPU-6050 via I²C)
 *   BOARD_UNO_WIFI_REV2 — Arduino UNO WiFi Rev2 (built-in LSM6DS3, native USB-C)
 *   BOARD_UNO_CLASSIC   — Arduino UNO Rev3 / classic (MPU-6050 via I²C +
 *                          FTDI/CP210x USB-serial adapter for OTG)
 *
 * Wire protocol (115200 baud, newline-terminated ASCII):
 *
 *   !ax,ay,az,gx,gy,gz   <- column header, sent once on boot
 *   0.12,-0.34,9.81,...  <- CSV data row
 *   # comment            <- ignored by Android app
 *
 * USB-OTG connection:
 *
 *   UNO R4 WiFi / UNO R4 Minima / UNO WiFi Rev2:
 *     Phone USB-C → OTG adapter → USB-A → board USB cable
 *     (native CDC-ACM, no dongle needed)
 *
 *   UNO Classic / any 5V board:
 *     Phone USB-C → OTG adapter → USB-A → FTDI/CP210x/CH340 adapter → board TX/RX
 *     Android app will auto-detect the USB-serial chip.
 *
 * Required libraries (Arduino IDE → Library Manager):
 *
 *   BOARD_UNO_R4_WIFI   → Arduino_LSM6DSOX
 *   BOARD_UNO_R4_MINIMA → Adafruit MPU6050  +  Adafruit Unified Sensor
 *   BOARD_UNO_WIFI_REV2 → Arduino_LSM6DS3
 *   BOARD_UNO_CLASSIC   → Adafruit MPU6050  +  Adafruit Unified Sensor
 *
 * MPU-6050 wiring (Minima / classic):
 *   VCC → 3.3 V (Minima) or 5 V (classic)
 *   GND → GND
 *   SDA → A4
 *   SCL → A5
 *   AD0 → GND  (I²C address 0x68)
 */

// ─── Board selection ─────────────────────────────────────────────────────────

#define BOARD_UNO_R4_WIFI      // ← change this to match your board
// #define BOARD_UNO_R4_MINIMA
// #define BOARD_UNO_WIFI_REV2
// #define BOARD_UNO_CLASSIC

// ─── Sample rate ─────────────────────────────────────────────────────────────

#define SAMPLE_INTERVAL_MS  10   // 100 Hz

// ─── Library & sensor setup ──────────────────────────────────────────────────

#if defined(BOARD_UNO_R4_WIFI)
  // LSM6DSOX — built-in on UNO R4 WiFi
  #include <Arduino_LSM6DSOX.h>
  #define SENSOR_BEGIN()        IMU.begin()
  #define ACCEL_AVAIL()         IMU.accelerationAvailable()
  #define GYRO_AVAIL()          IMU.gyroscopeAvailable()
  #define READ_ACCEL(x,y,z)     IMU.readAcceleration(x, y, z)
  #define READ_GYRO(x,y,z)      IMU.readGyroscope(x, y, z)
  #define BOARD_NAME "UNO R4 WiFi (LSM6DSOX)"

#elif defined(BOARD_UNO_WIFI_REV2)
  // LSM6DS3 — built-in on UNO WiFi Rev2
  #include <Arduino_LSM6DS3.h>
  #define SENSOR_BEGIN()        IMU.begin()
  #define ACCEL_AVAIL()         IMU.accelerationAvailable()
  #define GYRO_AVAIL()          IMU.gyroscopeAvailable()
  #define READ_ACCEL(x,y,z)     IMU.readAcceleration(x, y, z)
  #define READ_GYRO(x,y,z)      IMU.readGyroscope(x, y, z)
  #define BOARD_NAME "UNO WiFi Rev2 (LSM6DS3)"

#elif defined(BOARD_UNO_R4_MINIMA) || defined(BOARD_UNO_CLASSIC)
  // External MPU-6050 via I²C
  #include <Adafruit_MPU6050.h>
  #include <Adafruit_Sensor.h>
  #include <Wire.h>

  static Adafruit_MPU6050 mpu;
  static sensors_event_t  accelEv, gyroEv, tempEv;

  #define SENSOR_BEGIN()        mpu.begin()
  // MPU-6050 has no hardware-ready flags — always available after init
  #define ACCEL_AVAIL()         true
  #define GYRO_AVAIL()          true
  // Read both axes in one shot; store into local floats via a helper
  static float _ax, _ay, _az, _gx, _gy, _gz;
  static inline void _readMpu() {
    mpu.getEvent(&accelEv, &gyroEv, &tempEv);
    _ax = accelEv.acceleration.x / 9.81f;  // m/s² → g
    _ay = accelEv.acceleration.y / 9.81f;
    _az = accelEv.acceleration.z / 9.81f;
    _gx = gyroEv.gyro.x * 57.2958f;        // rad/s → deg/s
    _gy = gyroEv.gyro.y * 57.2958f;
    _gz = gyroEv.gyro.z * 57.2958f;
  }
  #define READ_ACCEL(x,y,z)     do { _readMpu(); x=_ax; y=_ay; z=_az; } while(0)
  #define READ_GYRO(x,y,z)      do { x=_gx; y=_gy; z=_gz; } while(0)

  #if defined(BOARD_UNO_R4_MINIMA)
    #define BOARD_NAME "UNO R4 Minima + MPU-6050"
  #else
    #define BOARD_NAME "UNO Classic + MPU-6050"
  #endif

#else
  #error "Select a board at the top of the sketch."
#endif

// ─── State ───────────────────────────────────────────────────────────────────

static bool imuOk             = false;
static bool streaming         = true;
static unsigned long lastMs   = 0;

// ─── Setup ───────────────────────────────────────────────────────────────────

void setup() {
  Serial.begin(115200);
  // UNO R4 / WiFi Rev2 have native USB; wait up to 3 s to enumerate
  while (!Serial && millis() < 3000) {}

  Serial.print(F("# Board: "));
  Serial.println(F(BOARD_NAME));

  if (SENSOR_BEGIN()) {
    imuOk = true;
    Serial.println(F("# IMU OK"));
    Serial.println(F("# Commands: s=stream on  p=stream off  ?=status"));
    Serial.println(F("!ax,ay,az,gx,gy,gz"));   // column header for Android app
  } else {
    Serial.println(F("# ERROR: IMU init failed — check wiring / library"));
  }
}

// ─── Main loop ───────────────────────────────────────────────────────────────

void loop() {
  // ── Command handling ─────────────────────────────────────────────────────
  while (Serial.available()) {
    char cmd = (char)Serial.read();
    switch (cmd) {
      case 's':
        streaming = true;
        Serial.println(F("# Streaming ON"));
        break;
      case 'p':
        streaming = false;
        Serial.println(F("# Streaming OFF"));
        break;
      case '?':
        Serial.print(F("# IMU: "));      Serial.println(imuOk    ? F("OK") : F("FAIL"));
        Serial.print(F("# Stream: "));   Serial.println(streaming ? F("ON") : F("OFF"));
        Serial.print(F("# Board: "));    Serial.println(F(BOARD_NAME));
        break;
      default:
        break;
    }
  }

  // ── IMU streaming ────────────────────────────────────────────────────────
  if (streaming && imuOk) {
    unsigned long now = millis();
    if (now - lastMs >= SAMPLE_INTERVAL_MS) {
      lastMs = now;

      if (ACCEL_AVAIL() && GYRO_AVAIL()) {
        float ax, ay, az, gx, gy, gz;
        READ_ACCEL(ax, ay, az);
        READ_GYRO(gx, gy, gz);

        // ax,ay,az [g]  gx,gy,gz [deg/s]
        Serial.print(ax, 4); Serial.print(',');
        Serial.print(ay, 4); Serial.print(',');
        Serial.print(az, 4); Serial.print(',');
        Serial.print(gx, 4); Serial.print(',');
        Serial.print(gy, 4); Serial.print(',');
        Serial.println(gz, 4);
      }
    }
  }
}
