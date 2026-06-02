/**
 * nesso_usb_serial_imu.ino
 *
 * Sends IMU sensor data over USB serial in the format expected by the
 * Edge Impulse Android Data Collector's USB OTG tab.
 *
 * Tested on:
 *   • Arduino Nano 33 BLE / Nano 33 BLE Sense  (LSM9DS1 — built-in)
 *   • Arduino Nano 33 IoT                       (LSM6DS3 — built-in)
 *   • Any Arduino + MPU-6050 breakout over I²C  (see MPU-6050 section)
 *
 * Wire protocol (115200 baud, newline-terminated ASCII):
 *
 *   !ax,ay,az,gx,gy,gz   <- column header, sent once on boot
 *   0.12,-0.34,9.81,...  <- one data row per sample
 *   # comment            <- ignored by the Android app
 *
 * USB-OTG connection:
 *   Phone USB-C/micro → OTG adapter → USB-A → Arduino USB cable
 *   (No extra USB-serial dongle needed — the Arduino Nano 33 BLE
 *    enumerates directly as a CDC-ACM serial device.)
 *
 * Required libraries (install via Arduino IDE → Library Manager):
 *   • Arduino_LSM9DS1  (for Nano 33 BLE / Nano 33 BLE Sense)
 *   OR
 *   • Arduino_LSM6DS3  (for Nano 33 IoT)
 *   OR
 *   • Adafruit MPU6050 + Adafruit Unified Sensor  (for external MPU-6050)
 */

// ─────────────────────────────────────────────────────────────────────────────
// Board selection  — uncomment exactly ONE of the three blocks below
// ─────────────────────────────────────────────────────────────────────────────

// Option A: Arduino Nano 33 BLE / Nano 33 BLE Sense  ← DEFAULT
#define BOARD_NANO33BLE
#include <Arduino_LSM9DS1.h>

// Option B: Arduino Nano 33 IoT
// #define BOARD_NANO33IOT
// #include <Arduino_LSM6DS3.h>

// Option C: Any board + MPU-6050 breakout (SDA/SCL pins)
// #define BOARD_MPU6050
// #include <Adafruit_MPU6050.h>
// #include <Adafruit_Sensor.h>
// #include <Wire.h>

// ─────────────────────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────────────────────

// How often to send a sample (milliseconds).  10 ms = 100 Hz.
// Must match the "Interval (ms)" field in the Android app.
static const uint32_t SAMPLE_INTERVAL_MS = 10;

// How many samples to send before reprinting the header comment.
// Set to 0 to disable periodic header reprints.
static const uint32_t HEADER_REPEAT_EVERY = 500;

// ─────────────────────────────────────────────────────────────────────────────
// MPU-6050 instance (only used for Option C)
// ─────────────────────────────────────────────────────────────────────────────
#ifdef BOARD_MPU6050
Adafruit_MPU6050 mpu;
#endif

// ─────────────────────────────────────────────────────────────────────────────
// setup()
// ─────────────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  // Wait up to 3 s for a serial monitor / Android app to attach.
  // On boards without native USB (Uno, Nano classic) remove this delay.
  uint32_t t0 = millis();
  while (!Serial && (millis() - t0 < 3000)) { /* spin */ }

  // ----- Initialise IMU -----
#if defined(BOARD_NANO33BLE) || defined(BOARD_NANO33IOT)
  if (!IMU.begin()) {
    Serial.println("# ERROR: IMU init failed. Check board selection.");
    while (true) { /* halt */ }
  }
  Serial.print("# IMU sample rate: ");
  Serial.print(IMU.accelerationSampleRate());
  Serial.println(" Hz");
#endif

#ifdef BOARD_MPU6050
  if (!mpu.begin()) {
    Serial.println("# ERROR: MPU-6050 not found. Check SDA/SCL wiring.");
    while (true) { /* halt */ }
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  Serial.println("# MPU-6050 ready");
#endif

  // ----- Send column header -----
  // The Android app uses this to label the axes in the dataset.
  Serial.println("!ax,ay,az,gx,gy,gz");
}

// ─────────────────────────────────────────────────────────────────────────────
// loop()
// ─────────────────────────────────────────────────────────────────────────────
void loop() {
  static uint32_t lastSampleMs = 0;
  static uint32_t sampleIndex  = 0;

  uint32_t now = millis();
  if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
  lastSampleMs = now;

  float ax = 0, ay = 0, az = 0;
  float gx = 0, gy = 0, gz = 0;
  bool  ok = false;

#if defined(BOARD_NANO33BLE) || defined(BOARD_NANO33IOT)
  if (IMU.accelerationAvailable() && IMU.gyroscopeAvailable()) {
    IMU.readAcceleration(ax, ay, az);   // units: g
    IMU.readGyroscope(gx, gy, gz);      // units: deg/s
    ok = true;
  }
#endif

#ifdef BOARD_MPU6050
  sensors_event_t accelEv, gyroEv, tempEv;
  mpu.getEvent(&accelEv, &gyroEv, &tempEv);
  ax = accelEv.acceleration.x / 9.81f;  // convert m/s² → g
  ay = accelEv.acceleration.y / 9.81f;
  az = accelEv.acceleration.z / 9.81f;
  gx = gyroEv.gyro.x * 57.2958f;        // convert rad/s → deg/s
  gy = gyroEv.gyro.y * 57.2958f;
  gz = gyroEv.gyro.z * 57.2958f;
  ok = true;
#endif

  if (!ok) return;

  // Optionally reprint the header every N samples so the Android app can
  // (re-)detect column names even if it was connected mid-stream.
  if (HEADER_REPEAT_EVERY > 0 && sampleIndex % HEADER_REPEAT_EVERY == 0 && sampleIndex > 0) {
    Serial.println("!ax,ay,az,gx,gy,gz");
  }
  sampleIndex++;

  // ---- Emit CSV data row ----
  // Format: ax,ay,az,gx,gy,gz
  Serial.print(ax, 4); Serial.print(',');
  Serial.print(ay, 4); Serial.print(',');
  Serial.print(az, 4); Serial.print(',');
  Serial.print(gx, 4); Serial.print(',');
  Serial.print(gy, 4); Serial.print(',');
  Serial.print(gz, 4); Serial.println();
}
