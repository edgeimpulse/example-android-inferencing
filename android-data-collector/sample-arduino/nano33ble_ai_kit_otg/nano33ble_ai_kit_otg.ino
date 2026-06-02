/**
 * nano33ble_ai_kit_otg.ino
 *
 * USB-OTG data source for the Edge Impulse Android Data Collector.
 * Targets the Arduino Nano 33 BLE Sense (Rev1 or Rev2) fitted with
 * the Arduino Tiny ML Kit camera shield (OV7675).
 *
 * Two data modes (switchable via serial commands at runtime):
 *
 *  IMU mode  (default, starts automatically on boot)
 *  ──────────────────────────────────────────────────
 *  Streams ax,ay,az,gx,gy,gz as newline-terminated CSV at
 *  SAMPLE_INTERVAL_MS.  Compatible with the Android app's USB OTG
 *  tab out of the box.
 *
 *  Camera mode  (triggered by the 'c' command)
 *  ────────────────────────────────────────────
 *  Captures a single QQVGA (160×120) frame in RGB565 or GRAYSCALE
 *  and sends it over serial with a one-line ASCII header:
 *
 *    IMG:<width>,<height>,<format>,<byte_count>\n
 *    <raw bytes>
 *    \n
 *
 *  The Android app's USB OTG tab currently handles the IMU CSV stream.
 *  Camera frames are passed through as raw bytes for use with a custom
 *  PC-side capture tool or a future Android image ingestion update.
 *
 * Serial commands (send as single characters):
 *   s  – start / resume IMU streaming
 *   p  – pause  IMU streaming
 *   c  – capture one camera frame (pauses IMU during transfer)
 *   r  – resume IMU streaming after camera capture
 *   g  – toggle camera format between RGB565 and GRAYSCALE
 *   ?  – print status
 *
 * Wire protocol (115200 baud):
 *
 *   !ax,ay,az,gx,gy,gz          <- column header (sent once on boot)
 *   0.12,-0.34,9.81,...         <- CSV data row
 *   # comment                   <- ignored by Android app
 *   IMG:160,120,GRAYSCALE,19200 <- camera frame header
 *   <19200 raw bytes>           <- frame payload
 *
 * USB-OTG connection:
 *   Phone USB-C → OTG adapter → USB-A → Nano 33 BLE USB cable
 *   The Nano 33 BLE enumerates as CDC-ACM; no USB-serial dongle needed.
 *
 * Board variants — select ONE:
 */
#define BOARD_NANO33BLE        // Rev1: LSM9DS1, HTS221, APDS9960
// #define BOARD_NANO33BLE_REV2   // Rev2: BMI270 + BMM150, HS3003, APDS9960

/**
 * Camera pixel format — select ONE:
 */
#define CAM_FORMAT GRAYSCALE   // smaller, faster, preferred for ML
// #define CAM_FORMAT RGB565   // full colour

/**
 * IMU sample interval (ms).  100 Hz = 10 ms.
 */
#define SAMPLE_INTERVAL_MS 10

// ─── Library includes ────────────────────────────────────────────────────────

#include <Arduino_OV767X.h>    // Arduino_OV767X (Library Manager)

#if defined(BOARD_NANO33BLE)
  #include <Arduino_LSM9DS1.h>  // Arduino_LSM9DS1 (Library Manager)
  #define IMU_BEGIN()          IMU.begin()
  #define IMU_ACCEL_AVAIL()    IMU.accelerationAvailable()
  #define IMU_GYRO_AVAIL()     IMU.gyroscopeAvailable()
  #define IMU_READ_ACCEL(x,y,z) IMU.readAcceleration(x, y, z)
  #define IMU_READ_GYRO(x,y,z)  IMU.readGyroscope(x, y, z)
#elif defined(BOARD_NANO33BLE_REV2)
  #include <Arduino_BMI270_BMM150.h>  // Arduino_BMI270_BMM150 (Library Manager)
  #define IMU_BEGIN()          IMU.begin()
  #define IMU_ACCEL_AVAIL()    IMU.accelerationAvailable()
  #define IMU_GYRO_AVAIL()     IMU.gyroscopeAvailable()
  #define IMU_READ_ACCEL(x,y,z) IMU.readAcceleration(x, y, z)
  #define IMU_READ_GYRO(x,y,z)  IMU.readGyroscope(x, y, z)
#else
  #error "Select BOARD_NANO33BLE or BOARD_NANO33BLE_REV2 at the top of the sketch."
#endif

// ─── Camera frame buffer ─────────────────────────────────────────────────────
// QQVGA RGB565  = 160 × 120 × 2 = 38 400 bytes
// QQVGA GRAY    = 160 × 120 × 1 = 19 200 bytes
// Nano 33 BLE has 256 KB SRAM — either fits comfortably.

#if (CAM_FORMAT == RGB565)
  static uint8_t camBuf[160 * 120 * 2];
  static const char* CAM_FORMAT_STR = "RGB565";
  static const int   CAM_BPP        = 2;
#else
  static uint8_t camBuf[160 * 120 * 1];
  static const char* CAM_FORMAT_STR = "GRAYSCALE";
  static const int   CAM_BPP        = 1;
#endif

// ─── State ───────────────────────────────────────────────────────────────────

static bool imuOk      = false;
static bool cameraOk   = false;
static bool streaming  = true;   // IMU streaming active by default
static unsigned long lastSampleMs = 0;

// ─── Helpers ─────────────────────────────────────────────────────────────────

static void printStatus() {
  Serial.print(F("# IMU: "));     Serial.println(imuOk    ? F("OK") : F("FAILED"));
  Serial.print(F("# Camera: "));  Serial.println(cameraOk ? F("OK") : F("FAILED"));
  Serial.print(F("# IMU stream: ")); Serial.println(streaming ? F("ON") : F("OFF"));
  Serial.print(F("# Cam format: ")); Serial.println(CAM_FORMAT_STR);
  Serial.println(F("# Commands: s=stream on  p=stream off  c=capture  ?=status"));
}

static void captureAndSend() {
  if (!cameraOk) {
    Serial.println(F("# ERROR: camera not available"));
    return;
  }

  bool wasStreaming = streaming;
  streaming = false;   // pause IMU output during transfer

  Camera.readFrame(camBuf);

  int w     = Camera.width();
  int h     = Camera.height();
  int bytes = w * h * CAM_BPP;

  // ASCII header — parseable by host software
  Serial.print(F("IMG:"));
  Serial.print(w);
  Serial.print(',');
  Serial.print(h);
  Serial.print(',');
  Serial.print(CAM_FORMAT_STR);
  Serial.print(',');
  Serial.println(bytes);

  // Raw pixel bytes — send in chunks to avoid blocking the USB FIFO too long
  const int CHUNK = 512;
  for (int offset = 0; offset < bytes; offset += CHUNK) {
    int toWrite = min(CHUNK, bytes - offset);
    Serial.write(camBuf + offset, toWrite);
  }
  Serial.println();   // trailing newline marks end of frame
  Serial.println(F("# Frame sent — send 'r' to resume IMU stream"));

  streaming = wasStreaming;
}

// ─── Setup ───────────────────────────────────────────────────────────────────

void setup() {
  Serial.begin(115200);
  // Wait up to 3 s for the USB serial port to enumerate on the host
  while (!Serial && millis() < 3000) {}

  // ── IMU ──────────────────────────────────────────────────────────────────
  if (IMU_BEGIN()) {
    imuOk = true;
    Serial.println(F("# IMU OK"));
    Serial.println(F("!ax,ay,az,gx,gy,gz"));   // column header for Android app
  } else {
    Serial.println(F("# ERROR: IMU init failed"));
  }

  // ── Camera ───────────────────────────────────────────────────────────────
  if (Camera.begin(QQVGA, CAM_FORMAT, 1)) {
    cameraOk = true;
    Serial.print(F("# Camera OK — QQVGA "));
    Serial.print(Camera.width()); Serial.print('×'); Serial.print(Camera.height());
    Serial.print(F(" ")); Serial.println(CAM_FORMAT_STR);
  } else {
    Serial.println(F("# WARNING: Camera init failed (IMU-only mode)"));
  }

  printStatus();
}

// ─── Main loop ───────────────────────────────────────────────────────────────

void loop() {
  // ── Command handling ─────────────────────────────────────────────────────
  while (Serial.available()) {
    char cmd = (char)Serial.read();
    switch (cmd) {
      case 's':
        streaming = true;
        Serial.println(F("# IMU streaming ON"));
        break;
      case 'p':
        streaming = false;
        Serial.println(F("# IMU streaming OFF"));
        break;
      case 'r':
        streaming = true;
        Serial.println(F("# Resumed IMU streaming"));
        break;
      case 'c':
        captureAndSend();
        break;
      case '?':
        printStatus();
        break;
      default:
        break;
    }
  }

  // ── IMU streaming ────────────────────────────────────────────────────────
  if (streaming && imuOk) {
    unsigned long now = millis();
    if (now - lastSampleMs >= SAMPLE_INTERVAL_MS) {
      lastSampleMs = now;

      // Read both axes together — wait for both to be ready
      if (IMU_ACCEL_AVAIL() && IMU_GYRO_AVAIL()) {
        float ax, ay, az;
        float gx, gy, gz;
        IMU_READ_ACCEL(ax, ay, az);
        IMU_READ_GYRO(gx, gy, gz);

        // CSV row: ax,ay,az [g],  gx,gy,gz [deg/s]
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
