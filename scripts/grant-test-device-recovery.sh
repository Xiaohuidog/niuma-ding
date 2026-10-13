#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
device_file="$repo_dir/.env.test-device"
android_sdk_root=${ANDROID_HOME:-"/Users/mac/Library/Android/sdk"}
adb_bin="$android_sdk_root/platform-tools/adb"

if [ ! -x "$adb_bin" ] || [ ! -f "$device_file" ]; then
  echo "固定测试机或 adb 配置不存在" >&2
  exit 1
fi

device_serial=$(sed -n 's/^ANDROID_SERIAL=//p' "$device_file" | head -n 1 | tr -d '\r')
if [ -z "$device_serial" ] || [ "$("$adb_bin" -s "$device_serial" get-state 2>/dev/null || true)" != "device" ]; then
  echo "固定测试机未连接或未授权" >&2
  exit 1
fi

"$adb_bin" -s "$device_serial" shell pm grant com.niuma.checkin android.permission.WRITE_SECURE_SETTINGS
"$adb_bin" -s "$device_serial" shell dumpsys package com.niuma.checkin |
  sed -n '/android.permission.WRITE_SECURE_SETTINGS: granted=true/p'
