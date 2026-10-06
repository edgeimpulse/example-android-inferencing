#!/usr/bin/env bash
set -euo pipefail

VERSION="${QNN_VERSION:-2.39.0.250926}"
ZIP_URL="https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/${QNN_VERSION}/v/${QNN_VERSION}.zip"
ZIP_FILE=""
SCRIPTPATH="$(cd "$(dirname "$0")" && pwd -P)"
JNI_ARM64_DIR="$SCRIPTPATH/jniLibs/arm64-v8a"
INCLUDE_DIR="$SCRIPTPATH/cpp/qnn/include/QNN"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip-file)
      [[ $# -ge 2 ]] || { echo "--zip-file requires a path"; exit 1; }
      ZIP_FILE="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

mkdir -p "$JNI_ARM64_DIR" "$INCLUDE_DIR"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
ZIP_PATH="$WORKDIR/qnn.zip"

if [[ -z "$ZIP_FILE" ]]; then
  if command -v curl >/dev/null 2>&1; then
    curl -fL -o "$ZIP_PATH" "$ZIP_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "$ZIP_URL"
  else
    echo "Need curl or wget"; exit 1
  fi
else
  [[ -f "$ZIP_FILE" ]] || { echo "Zip not found: $ZIP_FILE"; exit 1; }
  ZIP_PATH="$ZIP_FILE"
fi

EXTRACT_DIR="$WORKDIR/extract"
mkdir -p "$EXTRACT_DIR"
unzip -q "$ZIP_PATH" -d "$EXTRACT_DIR"

QNN_INCLUDE="$(find "$EXTRACT_DIR" -type d -path '*/include/QNN' -print -quit)"
[[ -n "$QNN_INCLUDE" ]] || { echo "QNN headers not found"; exit 1; }
cp -R "$QNN_INCLUDE/." "$INCLUDE_DIR/"

find "$EXTRACT_DIR" -type f \
  \( -path '*/lib/aarch64-android/libQnn*.so' \
     -o -path '*/lib/hexagon-v*/unsigned/libQnn*.so' \) \
  -exec cp -f {} "$JNI_ARM64_DIR/" \;

for required in "$JNI_ARM64_DIR/libQnnTFLiteDelegate.so" \
  "$JNI_ARM64_DIR/libQnnHtp.so" "$INCLUDE_DIR/TFLiteDelegate/QnnTFLiteDelegate.h"; do
  [[ -f "$required" ]] || { echo "Missing required QNN file: $required"; exit 1; }
done

echo "Done: $JNI_ARM64_DIR"

