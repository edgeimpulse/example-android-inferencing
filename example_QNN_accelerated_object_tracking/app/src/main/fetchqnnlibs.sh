#!/usr/bin/env bash
set -euo pipefail

ZIP_URL="https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.39.0.250926/v2.39.0.250926.zip"
ZIP_FILE=""
SCRIPTPATH="$(cd "$(dirname "$0")" && pwd -P)"
JNI_ARM64_DIR="$SCRIPTPATH/arm64-v8a"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip-file) ZIP_FILE="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

mkdir -p "$JNI_ARM64_DIR"

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

mapfile -t SO_FILES < <(find "$EXTRACT_DIR" -type f -name "*.so" | grep -Ei "(aarch64-android|android|arm64|/lib/)" || true)
[[ ${#SO_FILES[@]} -gt 0 ]] || { echo "No .so files found"; exit 1; }

for f in "${SO_FILES[@]}"; do
  cp -f "$f" "$JNI_ARM64_DIR/"
done

echo "Done: $JNI_ARM64_DIR"

