#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
device_file="$repo_dir/.env.test-device"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

if [ ! -x "$adb_bin" ]; then
  echo "未找到 adb：$adb_bin" >&2
  exit 1
fi

if [ ! -f "$device_file" ]; then
  echo "缺少固定测试机配置：$device_file" >&2
  exit 1
fi

device_serial=$(sed -n 's/^ANDROID_SERIAL=//p' "$device_file" | head -n 1 | tr -d '\r')
if [ -z "$device_serial" ]; then
  echo "固定测试机配置缺少 ANDROID_SERIAL" >&2
  exit 1
fi

device_state=$("$adb_bin" -s "$device_serial" get-state 2>/dev/null || true)
if [ "$device_state" != "device" ]; then
  echo "固定测试机未连接或未授权：$device_serial" >&2
  exit 1
fi

apk_dir="$repo_dir/app/build/outputs/apk/debug"
apk="$(ls -t "$apk_dir"/niuma-ding-debug-v*.apk 2>/dev/null | head -n 1 || true)"
if [ ! -f "$apk" ]; then
  echo "缺少带版本号的 Debug APK，请先运行 ./gradlew :app:assembleDebug" >&2
  exit 1
fi

echo "使用 Debug APK：$apk"

"$adb_bin" -s "$device_serial" install -r "$apk"
"$adb_bin" -s "$device_serial" shell am start -W -n com.niuma.ding/com.niuma.checkin.MainActivity
