#!/usr/bin/env bash
# Start Android Auto Desktop Head Unit (DHU) against a USB-connected phone/emulator.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" && -f local.properties ]]; then
  SDK=$(grep '^sdk.dir=' local.properties | cut -d= -f2- | tr -d '\r' | sed 's|\\|/|g')
fi
if [[ -z "$SDK" ]]; then
  echo "Set ANDROID_SDK_ROOT or sdk.dir in local.properties"
  exit 1
fi

DHU_DIR="${SDK}/extras/google/auto"
DHU="${DHU_DIR}/desktop-head-unit"
if [[ ! -x "$DHU" ]]; then
  echo "DHU not found at $DHU"
  echo "Install with: \"${SDK}/cmdline-tools/latest/bin/sdkmanager\" \"extras;google;auto\""
  exit 1
fi

# libc++ / libc++abi are not on the default linker path on this host.
LIB_PATH="${SDK}/emulator/lib64"
if [[ -d /usr/lib64/rocm/llvm/lib ]]; then
  LIB_PATH="${LIB_PATH}:/usr/lib64/rocm/llvm/lib"
fi
export LD_LIBRARY_PATH="${LIB_PATH}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Prefer ANDROID_SERIAL when multiple devices are attached.
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb -s "$ANDROID_SERIAL" forward tcp:5277 tcp:5277
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "No adb device. Connect a phone/emulator with USB debugging."
    exit 1
  elif [[ ${#DEVICES[@]} -gt 1 ]]; then
    echo "Multiple adb devices. Set ANDROID_SERIAL to one of:"
    printf '  %s\n' "${DEVICES[@]}"
    exit 1
  fi
  adb forward tcp:5277 tcp:5277
fi

echo "Prerequisites on the phone (Android Auto app):"
echo "  1) Developer mode (tap AA version/title ~10×)"
echo "  2) Menu → Start head unit server  (notification must appear; port 5277)"
echo "  3) Developer settings → Unknown sources ON"
echo "  4) Phone screen unlocked"
echo "DHU console tips: parking brake on | parking brake off | day | night | exit"
echo "Starting DHU…"

cd "$DHU_DIR"
# default_sensors.ini exposes parking brake for ParkGate testing
CONFIG="${1:-config/default_sensors.ini}"
if [[ $# -gt 0 ]]; then
  shift
fi
exec ./desktop-head-unit -c "$CONFIG" "$@"
