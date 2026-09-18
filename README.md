# 耳机闹钟 · HeadphoneAlarm

只在你戴着耳机时响的 Android 闹钟。

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-26-orange.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

---

## 这个 App 解决什么问题

早上在宿舍、合租屋、通勤路上设闹钟，最怕两件事：

1. **闹钟响了却把整个房间吵醒** —— 你只想自己被叫醒；
2. **戴了耳机却没声音** —— 系统把闹钟音频丢到了机身扬声器，或者耳机还没连上就错过了叫醒时刻。

耳机闹钟的做法是：**闹钟只在耳机里响**。它会在响铃前先确认耳机是否在线，并在整个响铃过程中持续校验音频真正的输出设备——一旦发现声音要走扬声器，立刻静音等待，绝不外放。

## 核心特性

### 绝不外放：四层保障

Android 的 `setPreferredDevice` 只是「偏好」而非「强制」，系统音频策略（尤其蓝牙）有最终决定权，所以单一的偏好声明并不可靠。本项目层层兜底：

| 层级 | 手段 | 作用 |
| --- | --- | --- |
| 1 | 播放前必须存在耳机设备 | 没有耳机就完全不创建音频流，从源头杜绝外放 |
| 2 | `setPreferredDevice` 多次声明 | 在 `prepare` 前后、`start` 前以及每次路由变化后重复声明偏好 |
| 3 | 静音启动 + 路由确认后出声 | 播放开始时增益为 0，只有巡检确认实际输出确实是耳机后才开始出声 |
| 4 | 600ms 路由巡检 + 插拔回调 | 每 600ms 校验 `getRoutedDevice()`，配合 `AudioDeviceCallback` 与 `ACTION_AUDIO_BECOMING_NOISY` 广播，一旦输出落到非耳机设备立即静音 |

巡检中对「蓝牙建链延迟」「唤醒瞬时抖动」做了宽限期与连续确认处理，避免误杀导致提示闪烁。

### 耳机识别

通过 `AudioManager.getDevices()` 枚举真实输出设备并判断类型，不使用在部分机型上恒为 `false` 的废弃属性 `isWiredHeadsetOn`。覆盖类型：

- 有线耳机 / 带麦耳麦
- Type-C / USB 耳机，以及不带麦克风（会被系统报成 `TYPE_USB_DEVICE`）的 Type-C 耳机
- 蓝牙 A2DP / SCO
- Android 12+ 的 BLE 耳机，Android 9+ 的助听器设备

目标为蓝牙时会自动把音频 `usage` 从 `USAGE_ALARM` 切换为 `USAGE_MEDIA`——`USAGE_ALARM` 会被系统强制留在机身扬声器，导致「耳机无声、还误判成外放」的连锁问题。

### 无耳机时的策略

每条闹钟可独立选择没有耳机时的行为：

- **静默等待**：保持静音并监听，耳机一插入立刻开始播放（默认，绝不出声）
- **仅震动**：只震动提醒，不产生任何声音
- **允许外放**：回退到扬声器播放

### 护耳的音量渐强

音量不是线性拉升，而是按指数插值 `v(t) = start · (target/start)^(t/T)` 爬升。人耳感知响度近似与振幅的对数成正比，线性增长会导致「前几秒突然很响、后面反而听不出变化」，指数插值才能让分贝值匀速上升，听感上才是真正平缓的「由小到大」。

渐强起点（1%~30%）、渐强时长（0~120 秒）、音量上限（20%~100%，默认 85%）均可逐条配置。

## 功能一览

- 时间滚轮选择，上午/下午 12 小时制
- 重复规则：仅一次 / 每天 / 工作日 / 周末 / 任意星期组合
- 标签备注（如「起床」「吃药」）
- 4 组内置铃声（晨曦、脉冲、风铃、回响），由 `tools/generate_tones.py` 合成、首尾淡入淡出可无缝循环
- 自定义铃声导入：通过 SAF 选择音频，立即复制到应用私有目录，规避 `content://` 授权失效与云盘文件不可随机读取两类播放失败
- 贪睡时长、响铃自动超时关闭（超时自动停止，避免无人处理时长时间响铃）
- 开关状态一键切换，响铃前可试听
- 6 套主题：极光、赛博朋克、安卓原生、动漫、海洋、森林，每套含独立配色与背景画布（渐变 / 光斑 / 装饰动画）
- 全屏响铃界面：锁屏直接展示，显示音量渐强进度条，提供贪睡与关闭按钮
- 开机 / 应用更新后自动恢复全部闹钟
- 「使用最大音量」按钮：响铃时用户点按后重新抬升媒体音量（Android 的媒体音量安全保护会监听耳机实际输出分贝并自动压低，App 无法用 API 绕过，只能在用户真实点击后重新抬升）

## 技术栈

- **Kotlin 1.9.24** + **Jetpack Compose**（Material 3，BOM 2024.06.00）
- **Navigation Compose** 单 Activity 架构
- **DataStore Preferences** + **kotlinx.serialization** 做轻量持久化（整表 JSON 存储，读多写少，避免引入数据库层）
- **AlarmManager.setAlarmClock** 精确闹钟，无权限时退化为 `set(RTC_WAKEUP)`
- **前台服务**（`mediaPlayback` 类型）承载播放与通知，响铃期间持有 `PARTIAL_WAKE_LOCK`
- 无第三方 UI 库、无 DI 框架，`Application` 作为轻量依赖容器

## 项目结构

```
app/src/main/java/com/headphonealarm/
├── AlarmApp.kt                  应用入口 + 轻量依赖容器 + 通知渠道
├── audio/
│   ├── HeadphoneAlarmPlayer.kt  播放引擎：路由保障、音量渐强、震动
│   ├── HeadphoneDetector.kt     耳机识别与插拔监听
│   └── BuiltInTones.kt          内置铃声定义
├── data/
│   ├── AlarmItem.kt             闹钟数据模型与下一次触发时间计算
│   ├── AlarmRepository.kt       闹钟持久化
│   ├── AlarmScheduler.kt        AlarmManager 注册 / 取消 / 贪睡
│   └── SettingsRepository.kt    应用设置（主题）
├── receiver/
│   ├── AlarmReceiver.kt         闹钟触发广播
│   └── BootReceiver.kt          开机 / 更新后恢复闹钟
├── service/
│   └── AlarmRingService.kt      响铃前台服务：生命周期与状态编排
├── ui/
│   ├── MainActivity.kt          导航宿主
│   ├── list/                    闹钟列表
│   ├── edit/                    闹钟编辑
│   ├── ring/                    全屏响铃界面
│   ├── components/              滚轮选择器、玻璃卡片等自绘组件
│   └── theme/                   6 套主题与背景画布
└── util/                        权限引导、铃声导入、时间工具
```

## 构建

环境要求：JDK 17、Android SDK（compileSdk 34）。

```bash
git clone https://github.com/2169818893/HeadphoneAlarm.git
cd HeadphoneAlarm

# 指定 SDK 路径（该文件不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 构建 Debug 包
./gradlew :app:assembleDebug

# 构建 Release 包
./gradlew :app:assembleRelease
```

如需重新生成内置铃声：

```bash
python tools/generate_tones.py
```

## 使用须知

首次启动建议在应用内依次完成以下授权，否则闹钟可能不响或界面不弹出：

- **精确闹钟**（Android 12+）：`SCHEDULE_EXACT_ALARM`，未授权时只能使用非精确闹钟
- **通知权限**（Android 13+）：`POST_NOTIFICATIONS`
- **全屏通知**（Android 14+）：`USE_FULL_SCREEN_INTENT`，用于锁屏弹出响铃界面
- **关闭电池优化**：国内定制 ROM（MIUI / HyperOS / EMUI / ColorOS 等）在电池优化开启时会限制后台唤醒，可能延迟甚至拦截闹钟。小米机型还需在应用详情页额外放开自启动与省电策略

**关于音量**：Android 的「媒体音量安全 / 降低过大音量」会监听耳机实际输出分贝并自动压低，这是系统级行为，App 无法用 API 绕过。若响铃音量被系统压低，需在系统「声音」设置中关闭该保护。

## 已知限制

- 仅在 Android 8.0（API 26）及以上可用
- 「绝不外放」依赖系统如实上报音频路由，少数深度定制 ROM 可能上报不准；此时会保守地选择静音等待而非冒险外放
- 应用使用测试签名构建，仅供自行编译安装，不提供应用商店分发

## AI 生成声明

**本项目的全部内容——包括应用源代码、资源文件、UI 设计、内置铃声合成脚本以及本文档——完全由 AI 生成**，在人类提出的需求、反馈与真机测试结果驱动下迭代完成，项目作者未直接编写代码。

因此请务必注意：

- 代码未经系统性的专业人工代码审查，可能存在设计缺陷、边界情况处理不周或潜在安全问题
- 虽已在真机验证过主要功能，但**不保证在所有 Android 机型与系统 ROM 上行为一致**
- 涉及起床、吃药、通勤等真实作息场景时，请自行评估风险，并为重要日程设置备用闹钟
- 本项目按「原样（AS IS）」提供，不附带任何明示或暗示的担保，详见 [LICENSE](LICENSE) 第 7、8 条（免责声明与责任限制）

## 贡献

欢迎提交 Issue 与 Pull Request。提交前请确保 `./gradlew :app:assembleDebug` 可以通过。

## 许可证

基于 [Apache License 2.0](LICENSE) 开源，详见 [NOTICE](NOTICE)。
