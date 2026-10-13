# 牛马钉钉

独立安装的钉钉 Android 打卡助手项目，包名为 `com.niuma.ding`。

## 当前状态

立即打卡和定时计划共用工作台 → “我的”考勤打卡 → 蓝色大圆按钮 → “查看打卡结果” → 今日统计记录的四步流程。只有今日统计页中的上班或最后一条有效下班时间能作为成功依据；无法核实时记录为待核查，避免重复提交。

定时计划需要总开关开启、无障碍服务和截图会话有效、手机解锁、Wi-Fi 可用。应用安装或进程结束后应在“运行设置”核对截图会话。计划页显示下一次执行时间；系统闹钟负责在该时间唤醒应用。

历史迁移资料仅保存在本地 `docs/`，不代表当前可执行功能，也不上传至 GitHub。

## 构建

需要 JDK 17 和 Android SDK platform 35。设置 `ANDROID_HOME` 或通过 Android Studio 生成本机 `local.properties`，并在本机 `docs/keep/QQmail.txt` 放入 16 位 QQ 邮箱授权码，然后运行：

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Debug APK 路径：`app/build/outputs/apk/debug/niuma-ding-debug-v0.0.3-3.apk`。

## 本地文件

`docs/` 及其全部内容仅保存在本机，已被 Git 忽略；提交和推送前仍须检查提交树，确保该目录中的文件未进入 GitHub。
