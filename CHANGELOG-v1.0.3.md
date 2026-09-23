# 耳机闹钟 v1.0.3 更新内容

> 相对上一版本 **v1.0.2** 的改动。本次更新以「**后台响铃可靠性**」为核心，重点修复了用户反馈的「设定了耳机闹钟，到点却不响、把 App 切回前台才响」的问题，并一并解决了锁屏显示、自定义铃声丢失、铃声文件泄漏等一系列缺陷。

- **versionName**：1.0.2 → **1.0.3**
- **versionCode**：3 → **4**

---

## 一、核心修复：后台到点不响、切回前台才响

**现象**：闹钟时间在后台到达时毫无声音，用户手动把 App 切回前台（点亮屏幕）后声音才突然响起；开发者自测（屏幕常亮）无法复现。

**根因**：前台服务（`mediaPlayback`）只保证**进程不被杀、不被冻结**，但**不持有 CPU 唤醒锁、无法阻止息屏后 SoC 进入休眠（suspend）**。原代码把 `acquireWakeLock()` 放在协程内部、且在 DataStore **异步读盘之后**才调用，`AlarmReceiver.onReceive()` 全程也未持锁。于是在「闹钟触发 → 异步读数据 → 启动播放器」这段**没有唤醒锁**的窗口里，息屏设备的 CPU 直接挂起，整条播放管线卡死在读盘之前，直到用户切回前台点亮屏幕唤醒 CPU 才继续 —— 这正是「切回前台才响」的由来。

**修复**：
- `AlarmReceiver.onReceive()` **第一步就同步持有桥接唤醒锁**（`PARTIAL_WAKE_LOCK`，60 秒超时自动释放、`setReferenceCounted(false)`），桥接「闹钟触发 → 服务接管播放」。
- `AlarmRingService.onStartCommand()` 的 `ACTION_START` 分支**在进入任何协程/异步读盘之前就同步 `acquireWakeLock()`**。
- 两处配合，从闹钟触发那一刻起 CPU 即保持唤醒，播放不再依赖「屏幕是否点亮」。

---

## 二、锁屏看不到闹钟界面

**现象**：设置了 PIN / 图案 / 指纹锁的设备，闹钟到点后锁屏上弹出的是「解锁验证」界面，把闹钟 UI 盖住，必须先解锁才能看到并操作闹钟。

**根因**：`AlarmRingActivity` 在 `setShowWhenLocked(true)` 之外还调用了 `requestDismissKeyguard()`；在有安全锁的设备上它会拉起解锁验证界面覆盖闹钟。

**修复**：移除 `requestDismissKeyguard()` 调用。`setShowWhenLocked(true) + setTurnScreenOn(true)` 本身就能让闹钟界面显示在锁屏之上**并可直接交互**（贪睡 / 关闭无需先解锁），这是闹钟类应用的标准做法。

---

## 三、换机 / 备份恢复后自定义铃声丢失，导致闹钟静默失败

**现象**：使用过「从本地导入自定义铃声」的用户，在换机或备份恢复后，闹钟到点不响，仅弹出一条失败通知。

**根因**（两处叠加）：
1. 自定义铃声被复制到应用私有目录 `filesDir/ringtones/`，但备份规则 `backup_rules.xml`（Android 11 及以下）与 `data_extraction_rules.xml`（Android 12 及以上）**只备份了 `datastore`，漏了 `ringtones` 目录**。恢复后闹钟数据里的 `ringtoneUri` 指向一个**已不存在的文件**。
2. 播放器在音频文件加载失败时**直接判定失败、不回退**默认铃声。

**修复**：
- 两套备份规则均加入 `<include domain="file" path="ringtones" />`，换机 / 恢复会保留自定义铃声文件。
- 播放器改为**加载失败时自动回退系统默认铃声重试**，确保闹钟「一定能响」而非静默失败；同时修复了失败时半初始化的 `MediaPlayer` 未 `release()` 造成的原生资源泄漏。

---

## 四、其它修复与改进

### 4.1 唤醒锁兜底超时边界
`MAX_WAKELOCK_MS` 由 **30 分钟提高到 60 分钟**。原来它与「自动停止」的最大时长（30 分钟）相等且无余量：唤醒锁从 `onStartCommand` 起计时，而自动停止的 `delay` 从稍晚的读盘完成后起计，二者相等会导致**在收尾前提前释放唤醒锁**，最后关头 CPU 可能休眠、`dismiss()` 延迟。正常路径仍由 `dismiss()` 主动释放，此处仅为防泄漏兜底，无耗电影响。

### 4.2 贪睡状态可见反馈
一次性闹钟「触发即被消费」，列表开关会显示为关闭，但贪睡后确实会再响一次 —— 容易让用户误以为闹钟已彻底关闭。现在点击贪睡（响铃界面按钮或通知栏按钮）后会 Toast 提示「**已贪睡，将于 HH:MM 再响**」（时间格式跟随系统 12/24 小时制）。

> 说明：此处**刻意保留**了「触发即消费」的调度语义。若改为贪睡时保持闹钟启用，会与列表页 `ON_RESUME → rescheduleAll()` 复用同一个 `PendingIntent` 相冲突，把贪睡注册的「几分钟后」覆盖成「明天同一时刻」，导致**贪睡失效**（更严重的问题）。因此采用与系统闹钟一致的「可见反馈」方案。

### 4.3 自定义铃声文件泄漏清理
原来删除闹钟、更换铃声、连续多次导入、导入后未保存就退出等情况，都会在私有 `ringtones/` 目录留下无人引用的文件，只增不减。现在在**删除闹钟 / 更换铃声 / 连续导入替换 / 未保存退出**四处均会安全清理旧文件。

清理采用双重防护，杜绝误删：
- **只删** `filesDir/ringtones/` 的**直接子文件**（用 `canonicalFile.parentFile` 校验，防止 `../` 路径穿越误删其它数据）；
- **引用检查**：删除前扫描全部闹钟，仍被任一条引用的文件自动跳过。

### 4.4 代码质量
清理了既有的编译弃用警告：`TrendingUp`、`VolumeMute` 图标改用 `Icons.AutoMirrored.Outlined.*` 版本，使其在从右到左（RTL）语言下方向正确。

---

## 五、涉及改动的文件

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle.kts` | 版本号 1.0.2 → 1.0.3（versionCode 3 → 4） |
| `receiver/AlarmReceiver.kt` | 触发时同步持有桥接唤醒锁 |
| `service/AlarmRingService.kt` | `onStartCommand` 同步持锁；唤醒锁兜底超时 30 → 60 分钟；贪睡 Toast 提示 |
| `ui/ring/AlarmRingActivity.kt` | 移除 `requestDismissKeyguard`，避免解锁界面盖住闹钟 |
| `audio/HeadphoneAlarmPlayer.kt` | 铃声加载失败回退系统默认铃声；修复失败时 MediaPlayer 资源泄漏 |
| `util/RingtoneImporter.kt` | 新增安全删除 `deleteOwnedRingtone`（目录校验 + 引用检查） |
| `ui/edit/AlarmEditScreen.kt` | 删除 / 换铃声 / 连续导入 / 未保存退出四处清理铃声文件；图标 AutoMirrored |
| `res/xml/backup_rules.xml` | 备份纳入 `ringtones` 目录 |
| `res/xml/data_extraction_rules.xml` | 备份 / 迁移纳入 `ringtones` 目录 |

---

## 六、升级与验证建议

1. 覆盖安装本版本 APK 后，建议先用列表页底部的「**测试锁屏响铃（1 分钟后）**」，**立刻锁屏并把手机放着别动**（复现息屏后台场景），确认到点能正常出声、无需再切回前台。
2. 若此前用系统备份 / 换机迁移过数据且使用自定义铃声，建议重新导入一次铃声（旧版本备份未包含铃声文件）。
3. 小米 / HyperOS 等定制系统用户，仍需在应用详情页放开「自启动、省电策略无限制、后台弹出界面、锁屏显示」四项权限，以获得最佳可靠性。
