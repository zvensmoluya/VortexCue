# M1 播放器首轮真机 Smoke Test

> 状态：核心播放路径通过，M1 完整验收尚未完成
>
> 验证日期：2026-08-09
>
> 阶段：M1A–M1C 集中真机验收第一轮

## 结论

VortexCue 已经从单页演示器形成可用的 Home、Normal Player 与 Glow 三段式产品路径。首轮真机测试确认：来源可以按“先字幕、后媒体”的顺序建立；真实 FLAC 可以由后台 `MediaSessionService` 持续播放；Activity 离开和屏幕关闭不会中断播放；通知中心可以控制同一个媒体会话；会话、字幕、显示参数和播放进度可以跨冷启动恢复；Glow 的窗口亮度、文字亮度和临时控制亮度彼此独立。

本轮也发现并修复了三个桌面 JVM 测试没有暴露的真机问题：Android 16 ICU 不接受 ASS 清理逻辑中的正则表达式、OriginOS 在未声明并请求通知权限时不展示媒体通知，以及远程 `MediaController` 尚未确认时间轴时可能用 `0` 覆盖已保存进度。

这足以通过 M1 的核心路径 smoke test，但不等于 M1 完整验收。视频容器、其余三种字幕格式、URI 失效重新定位、实体耳机按键和锁屏可见控件仍需补测。

## 验证环境

- 真机：Vivo `V2458A`；
- 系统：Android 16，API 36；
- 物理分辨率：`1260 x 2800`；
- Debug application ID：`io.github.zvensmoluya.vortexcue.prototype`；
- 构建工具：仓库 Gradle Wrapper、JDK 17、Android SDK 37；
- 媒体样本：180 秒本地 FLAC；
- 字幕样本：包含 3 条 cue 和连续 ASS override tags 的本地 ASS；
- 安装方式：USB 调试覆盖安装，保留应用数据。

媒体样本使用无声 FLAC，适合自动检查时间轴、解码状态和生命周期，但不能代替有声内容的主观听感、音频焦点和音质验证。

## 已通过

### 产品路径与来源

- 冷启动进入 Home，来源配置与播放界面分离；
- 先选择 ASS 字幕，再选择 FLAC，字幕没有被后选媒体清空；
- Home 正确显示两个来源，只有媒体验证完成后才允许开始播放；
- Normal Player 显示媒体标题、当前 cue、前后 cue、3 分钟时长和基础控制；
- ASS 的换行和 override tags 清理后进入统一字幕时间轴。

### 后台与系统控制

- 返回桌面后，媒体会话位置持续推进；
- 关闭屏幕期间，播放位置继续推进；
- `PlaybackService` 作为 `mediaPlayback` 前台服务运行；
- 用户开始首次播放时才请求通知权限，不在冷启动首屏打断用户；
- 授权后，通知中心显示媒体标题、`VortexCue` 来源和播放控制；
- 从通知卡片暂停、继续后，平台 `MediaSession` 状态同步变为 `PAUSED`、`PLAYING`；
- 媒体会话提供返回 MainActivity 的 `PendingIntent`。

### 恢复与缓存

- 覆盖安装和冷启动后仍能恢复媒体、ASS 字幕、显示设置与播放倍速；
- 暂停后强制停止应用，再次启动可以恢复位置和 3 分钟时长；
- 点击继续后立即强制停止，旧位置和旧时长不会再被远程控制器的暂态 `0` 覆盖；
- 恢复播放时使用媒体项和起始位置的单次命令，避免来源切换与 seek 之间的竞态。

### Glow

- Normal Player 使用系统亮度，进入 Glow 后窗口亮度请求为 `0.08`；
- Glow 隐藏状态只保留黑底和字幕，没有常驻控制；
- 播放时临时控制在 4 秒后自动隐藏，点按画面可以恢复；
- Glow 中系统状态栏和导航栏隐藏；
- 退出 Glow 后系统栏恢复，窗口亮度覆盖被清除；
- 字幕灰阶与临时控制灰阶独立生效。

## 真机发现与修复

### Android 16 ASS 崩溃

最初选择 ASS 时发生 `ExceptionInInitializerError`。根因是 Android 16 ICU 对 `\{[^}]*}` 的解释与桌面 JVM 不一致并抛出 `PatternSyntaxException`。清理逻辑已改为线性扫描成对花括号，不再依赖该平台正则，并补充连续 override tags 的单元测试。修复后同一 ASS 在同一设备上读取 3 条字幕且不再崩溃。

### OriginOS 媒体通知

仅有标准 `MediaSessionService` 时，服务和平台媒体会话都在运行，但这台设备没有展示通知卡片。应用现已声明 `POST_NOTIFICATIONS`，并在用户主动开始播放时请求。播放会等待授权结果，再进入 `PLAYING`，避免通知在授权前被丢弃。授权后通知卡片及其暂停、继续控制通过实测。

Android 标准文档将媒体会话通知列为权限豁免项，因此这属于基于真实 OEM 行为增加的兼容路径，而不是假设所有 Android 设备都必须弹出权限。

### 恢复竞态

Activity 使用的是远程 `MediaController`。设置媒体、seek 和读取位置不是同一进程内的同步操作；在控制器尚未确认时间轴时立刻写入快照，可能读到 `0 / 0`。现在只有控制器提供有效进度后才替换旧值，否则保留 LastSession 中的有效位置和时长，并有单元测试覆盖。

## 尚未通过完整覆盖

- Vivo 控制中心的独立媒体方块仍显示“未在播放”，但通知中心媒体卡片、平台 `MediaSession` 和实际播放状态正确；需要在其他 OEM 上比较，确认是否为 OriginOS 面板问题；
- 熄屏播放已通过，锁屏上的可见媒体卡片未完成独立截图验收；
- `adb input keyevent 85` 没有被这台 Vivo 路由到媒体会话；通知卡片控制和平台媒体命令可用，实体耳机按键仍需测试；
- SRT、LRC、WebVTT 已通过 JVM parser 测试，本轮真机只选择了 ASS；
- 本轮媒体只覆盖 FLAC，没有覆盖有声音频、Ogg 或视频容器；
- 文件移动、Provider 授权失效和重新定位保留参数尚未在本轮复测；
- 耳机断开、音频焦点丢失、来电和闹钟行为尚未测试；
- 播放中每 100ms 更新 UI 会让 `uiautomator dump` 难以等到 idle；这不是本轮功能阻断项，但应在性能与无障碍检查中继续评估刷新粒度。

## 已执行的自动检查

```text
gradlew.bat testDebugUnitTest lint assembleDebug
```

以上检查全部通过。ASS 修复和恢复进度规则均有定向单元测试；后续补齐上述设备矩阵后继续更新本记录。
