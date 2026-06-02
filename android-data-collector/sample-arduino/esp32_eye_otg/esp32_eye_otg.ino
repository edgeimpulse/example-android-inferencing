/**
 * esp32_eye_otg.ino
 *
 * IMU + camera data source for the Edge Impulse Android Data Collector
 * USB OTG tab.
 *
 * Supported boards — select ONE below:
 *
 *   BOARD_ESP32S3_EYE   — Espressif ESP32-S3-EYE
 *                         (OV2640, built-in MPU-6050, native USB-C CDC)
 *   BOARD_ESP32_EYE     — Espressif ESP32-EYE (original)
 *                         (OV2640, no built-in IMU, CP2102 USB-serial)
 *   BOARD_ESP32_CAM     — AI-Thinker ESP32-CAM
 *                         (OV2640, no built-in IMU, needs FTDI adapter)
 *
 * ── IMPORTANT: Arduino IDE board/tool settings ──────────────────────────────
 *
 *   For BOARD_ESP32S3_EYE:
 *     Board    → "ESP32S3 Dev Module" (or "ESP32-S3-EYE" if shown)
 *     USB Mode → "USB-OTG (TinyUSB)"   ← required for native USB CDC
 *     USB CDC On Boot → "Enabled"
 *     Partition Scheme → "Huge APP (3MB No OTA...)"  ← camera needs heap
 *
 *   For BOARD_ESP32_EYE / BOARD_ESP32_CAM:
 *     Board → "ESP32 Dev Module" / "AI Thinker ESP32-CAM"
 *     Upload via FTDI/CP210x adapter (GPIO0 → GND to enter flash mode)
 *     OTG connection: phone → OTG adapter → CP2102/CH340 dongle → board TX/RX
 *
 * ── Wire protocol (115200 baud, newline-terminated ASCII) ───────────────────
 *
 *   !ax,ay,az,gx,gy,gz          ← column header (once on boot)
 *   0.12,-0.34,9.81,...         ← CSV row (IMU mode)
 *   # comment                   ← ignored by Android app
 *   IMG:320,240,GRAYSCALE,76800 ← camera frame header
 *   <76800 raw bytes>           ← frame payload (JPEG or raw)
 *
 * ── Serial commands ─────────────────────────────────────────────────────────
 *   s  – start / resume IMU streaming
 *   p  – pause  IMU streaming
 *   c  – capture one camera frame (JPEG)
 *   j  – toggle JPEG / raw GRAYSCALE output
 *   ?  – print status
 *
 * ── Required libraries (Arduino IDE → Library Manager) ──────────────────────
 *   • esp32 board support  (Espressif Systems)  — includes esp_camera
 *   • Adafruit MPU6050     — IMU (ESP32-S3-EYE built-in, or external)
 *   • Adafruit Unified Sensor
 */

// ─── Board selection ─────────────────────────────────────────────────────────

#define BOARD_ESP32S3_EYE     // ← change to match your board
// #define BOARD_ESP32_EYE
// #define BOARD_ESP32_CAM

// ─── Output format ───────────────────────────────────────────────────────────
// JPEG is smaller and faster to transfer; raw GRAYSCALE is easier to parse.

#define CAM_JPEG_DEFAULT true   // start in JPEG mode; toggle with 'j' command

// ─── Sample rate ─────────────────────────────────────────────────────────────

#define SAMPLE_INTERVAL_MS 10   // 100 Hz IMU

// ─── Camera pin maps ─────────────────────────────────────────────────────────

#if defined(BOARD_ESP32S3_EYE)
  // Espressif ESP32-S3-EYE
  #define CAM_PIN_PWDN   -1
  #define CAM_PIN_RESET  -1
  #define CAM_PIN_XCLK   15
  #define CAM_PIN_SDA     4
  #define CAM_PIN_SCL     5
  #define CAM_PIN_D7     16
  #define CAM_PIN_D6     17
  #define CAM_PIN_D5     18
  #define CAM_PIN_D4     12
  #define CAM_PIN_D3     10
  #define CAM_PIN_D2      8
  #define CAM_PIN_D1      9
  #define CAM_PIN_D0     11
  #define CAM_PIN_VSYNC   6
  #define CAM_PIN_HREF    7
  #define CAM_PIN_PCLK   13
  // MPU-6050 I²C (shares bus with camera on ESP32-S3-EYE)
  #define MPU_SDA         4
  #define MPU_SCL         5
  #define BOARD_NAME "ESP32-S3-EYE"
  #define HAS_IMU 1

#elif defined(BOARD_ESP32_EYE)
  // Espressif ESP32-EYE (original, no built-in IMU)
  #define CAM_PIN_PWDN   -1
  #define CAM_PIN_RESET  -1
  #define CAM_PIN_XCLK   4
  #define CAM_PIN_SDA    18
  #define CAM_PIN_SCL    23
  #define CAM_PIN_D7     36
  #define CAM_PIN_D6     37
  #define CAM_PIN_D5     38
  #define CAM_PIN_D4     39
  #define CAM_PIN_D3     35
  #define CAM_PIN_D2     14
  #define CAM_PIN_D1     13
  #define CAM_PIN_D0     34
  #define CAM_PIN_VSYNC   5
  #define CAM_PIN_HREF   27
  #define CAM_PIN_PCLK   25
  // Optional external MPU-6050 — wire SDA/SCL to free GPIO pins
  #define MPU_SDA        21
  #define MPU_SCL        22
  #define BOARD_NAME "ESP32-EYE"
  #define HAS_IMU 0    // set to 1 if you add an external MPU-6050

#elif defined(BOARD_ESP32_CAM)
  // AI-Thinker ESP32-CAM (OV2640)
  #define CAM_PIN_PWDN   32
  #define CAM_PIN_RESET  -1
  #define CAM_PIN_XCLK    0
  #define CAM_PIN_SDA    26
  #define CAM_PIN_SCL    27
  #define CAM_PIN_D7     35
  #define CAM_PIN_D6     34
  #define CAM_PIN_D5     39
  #define CAM_PIN_D4     36
  #define CAM_PIN_D3     21
  #define CAM_PIN_D2     19
  #define CAM_PIN_D1     18
  #define CAM_PIN_D0      5
  #define CAM_PIN_VSYNC  25
  #define CAM_PIN_HREF   23
  #define CAM_PIN_PCLK   22
  // Optional external MPU-6050 on spare I²C pins
  #define MPU_SDA        14
  #define MPU_SCL        15
  #define BOARD_NAME "ESP32-CAM"
  #define HAS_IMU 0    // set to 1 if you add an external MPU-6050

#else
  #error "Select a board at the top of the sketch."
#endif

// ─── Library includes ────────────────────────────────────────────────────────

#include "esp_camera.h"

#if HAS_IMU || defined(BOARD_ESP32_EYE) || defined(BOARD_ESP32_CAM)
  // Include unconditionally — user may wire external MPU-6050
  #include <Wire.h>
  #include <Adafruit_MPU6050.h>
  #include <Adafruit_Sensor.h>
  static Adafruit_MPU6050 mpu;
#endif

// ─── State ───────────────────────────────────────────────────────────────────

static bool imuOk      = false;
static bool cameraOk   = false;
static bool streaming  = true;
static bool jpegMode   = CAM_JPEG_DEFAULT;
static unsigned long lastMs = 0;

// ─── Camera init ─────────────────────────────────────────────────────────────

static bool initCamera() {
  camera_config_t cfg;
  cfg.ledc_channel = LEDC_CHANNEL_0;
  cfg.ledc_timer   = LEDC_TIMER_0;
  cfg.pin_d0       = CAM_PIN_D0;
  cfg.pin_d1       = CAM_PIN_D1;
  cfg.pin_d2       = CAM_PIN_D2;
  cfg.pin_d3       = CAM_PIN_D3;
  cfg.pin_d4       = CAM_PIN_D4;
  cfg.pin_d5       = CAM_PIN_D5;
  cfg.pin_d6       = CAM_PIN_D6;
  cfg.pin_d7       = CAM_PIN_D7;
  cfg.pin_xclk     = CAM_PIN_XCLK;
  cfg.pin_pclk     = CAM_PIN_PCLK;
  cfg.pin_vsync    = CAM_PIN_VSYNC;
  cfg.pin_href     = CAM_PIN_HREF;
  cfg.pin_sscb_sda = CAM_PIN_SDA;
  cfg.pin_sscb_scl = CAM_PIN_SCL;
  cfg.pin_pwdn     = CAM_PIN_PWDN;
  cfg.pin_reset    = CAM_PIN_RESET;
  cfg.xclk_freq_hz = 20000000;
  cfg.pixel_format = jpegMode ? PIXFORMAT_JPEG : PIXFORMAT_GRAYSCALE;
  cfg.frame_size   = FRAMESIZE_QVGA;   // 320×240
  cfg.jpeg_quality = 12;               // 0–63, lower = better quality
  cfg.fb_count     = 1;
  cfg.fb_location  = CAMERA_FB_IN_PSRAM;
  cfg.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;

  return (esp_camera_init(&cfg) == ESP_OK);
}

// ─── Camera capture and send ─────────────────────────────────────────────────

static void captureAndSend() {
  if (!cameraOk) {
    Serial.println(F("# ERROR: camera not available"));
    return;
  }

  bool wasStreaming = streaming;
  streaming = false;

  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println(F("# ERROR: frame capture failed"));
    streaming = wasStreaming;
    return;
  }

  const char* fmt = (fb->format == PIXFORMAT_JPEG) ? "JPEG" : "GRAYSCALE";

  // ASCII header
  Serial.print(F("IMG:"));
  Serial.print(fb->width);
  Serial.print(',');
  Serial.print(fb->height);
  Serial.print(',');
  Serial.print(fmt);
  Serial.print(',');
  Serial.println(fb->len);

  // Raw bytes in chunks
  const size_t CHUNK = 512;
  for (size_t offset = 0; offset < fb->len; offset += CHUNK) {
    size_t toWrite = min(CHUNK, fb->len - offset);
    Serial.write(fb->buf + offset, toWrite);
  }
  Serial.println();
  Serial.println(F("# Frame sent"));

  esp_camera_fb_return(fb);
  streaming = wasStreaming;
}

// ─── Setup ───────────────────────────────────────────────────────────────────

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 3000) {}

  Serial.print(F("# Board: "));
  Serial.println(F(BOARD_NAME));

  // ── IMU ──────────────────────────────────────────────────────────────────
#if HAS_IMU
  Wire.begin(MPU_SDA, MPU_SCL);
  if (mpu.begin()) {
    imuOk = true;
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_500_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);
    Serial.println(F("# IMU OK (MPU-6050)"));
    Serial.println(F("!ax,ay,az,gx,gy,gz"));
  } else {
    Serial.println(F("# WARNING: IMU not found"));
  }
#else
  // Try external MPU-6050 anyway — useful if user wired one in
  Wire.begin(MPU_SDA, MPU_SCL);
  if (mpu.begin()) {
    imuOk = true;
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_500_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);
    Serial.println(F("# IMU OK (external MPU-6050)"));
    Serial.println(F("!ax,ay,az,gx,gy,gz"));
  } else {
    Serial.println(F("# No IMU found (camera-only mode)"));
    streaming = false;
  }
#endif

  // ── Camera ───────────────────────────────────────────────────────────────
  if (initCamera()) {
    cameraOk = true;
    Serial.print(F("# Camera OK — 320x240 "));
    Serial.println(jpegMode ? F("JPEG") : F("GRAYSCALE"));
  } else {
    Serial.println(F("# WARNING: Camera init failed"));
  }

  Serial.println(F("# Commands: s=stream  p=pause  c=capture  j=toggle jpeg  ?=status"));
}

// ─── Main loop ───────────────────────────────────────────────────────────────

void loop() {
  // ── Command handling ─────────────────────────────────────────────────────
  while (Serial.available()) {
    char cmd = (char)Serial.read();
    switch (cmd) {
      case 's':
        if (imuOk) { streaming = true;  Serial.println(F("# Streaming ON")); }
        else        Serial.println(F("# No IMU available"));
        break;
      case 'p':
        streaming = false;
        Serial.println(F("# Streaming OFF"));
        break;
      case 'c':
        captureAndSend();
        break;
      case 'j':
        jpegMode = !jpegMode;
        // Reinit camera with new pixel format
        esp_camera_deinit();
        cameraOk = initCamera();
        Serial.print(F("# Camera format: "));
        Serial.println(jpegMode ? F("JPEG") : F("GRAYSCALE"));
        break;
      case '?':
        Serial.print(F("# IMU: "));    Serial.println(imuOk    ? F("OK") : F("none"));
        Serial.print(F("# Camera: ")); Serial.println(cameraOk ? F("OK") : F("FAIL"));
        Serial.print(F("# Stream: ")); Serial.println(streaming ? F("ON") : F("OFF"));
        Serial.print(F("# Format: ")); Serial.println(jpegMode ? F("JPEG") : F("GRAYSCALE"));
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

      sensors_event_t accelEv, gyroEv, tempEv;
      mpu.getEvent(&accelEv, &gyroEv, &tempEv);

      // Convert m/s² → g,  rad/s → deg/s
      float ax = accelEv.acceleration.x / 9.81f;
      float ay = accelEv.acceleration.y / 9.81f;
      float az = accelEv.acceleration.z / 9.81f;
      float gx = gyroEv.gyro.x * 57.2958f;
      float gy = gyroEv.gyro.y * 57.2958f;
      float gz = gyroEv.gyro.z * 57.2958f;

      Serial.print(ax, 4); Serial.print(',');
      Serial.print(ay, 4); Serial.print(',');
      Serial.print(az, 4); Serial.print(',');
      Serial.print(gx, 4); Serial.print(',');
      Serial.print(gy, 4); Serial.print(',');
      Serial.println(gz, 4);
    }
  }
}
