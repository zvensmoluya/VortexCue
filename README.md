# VortexCue / 荧荧

荧荧是一款 Android-first 的 OLED 微光同步文字播放器。它的核心约束很简单：音频继续提供声音和时间，纯黑屏幕只保留当前歌词或字幕。

M0A 首轮 OLED 物理可行性验证已经完成。当前代码已经进入 M1：来源配置、普通播放与微光播放分离，播放器运行在后台媒体会话中，并具备一个可恢复的本地会话。M1A/M1B 的代码路径已形成，后台生命周期与来源恢复仍等待集中真机验收。

## 当前实现基线

- Kotlin + Jetpack Compose；
- 单一 `app` 模块；
- Home、Normal Player 与 Glow 三个职责明确的产品表面；
- Media3 `MediaSessionService` + ExoPlayer 后台播放，Activity 通过 `MediaController` 控制；
- Storage Access Framework 选择本地音频或视频容器，不申请广泛存储权限；
- 播放源和字幕源相互独立的配置草稿，验证成功后原子应用；
- SRT、LRC、WebVTT、ASS/SSA 统一字幕时间轴，覆盖 TransVortex 输出；
- Preferences DataStore 保存草稿、最后会话、位置、倍速、偏移和显示预设；
- 已解析字幕写入应用私有的规范化 WebVTT 会话副本，媒体文件不复制；
- 失效媒体 URI 的可恢复状态与“重新定位”入口；
- 播放、暂停、前后 10 秒、倍速、字幕偏移及相邻字幕语境；
- 普通界面遵循系统亮度，Glow 独立控制窗口、字幕、临时控制、字号与垂直位置。

当前不包含网络媒体库、Room、依赖注入、转写或翻译任务。下一阶段重点是完成 M1C 的播放/微光细节，并集中进行真实设备验收。

当前产品、工程边界和实施顺序统一见 [`docs/GUIDE.md`](docs/GUIDE.md)，文档层级见 [`docs/README.md`](docs/README.md)。

M0A 首轮 OLED 真机验证记录见 [`docs/records/M0A_VALIDATION.md`](docs/records/M0A_VALIDATION.md)。

## 构建

需要 JDK 17+ 与 Android SDK 37：

```text
./gradlew assembleDebug
```

当前 application ID 是内部原型标识，公开发布前仍需结合发布主体确认。

## 真机播放器验证

1. 分别验证先选字幕、先选媒体以及取消文件选择；
2. 使用本地音频和视频容器，以及 `.srt`、`.lrc`、`.vtt`、`.ass` 字幕；
3. 检查暂停、恢复、前后 10 秒、倍速与字幕偏移；
4. 切到其他应用、锁屏并使用通知、耳机和系统媒体按键；
5. 重启应用并检查最后位置、字幕、偏移、倍速与显示预设；
6. 让原媒体 URI 失效，检查重新定位后是否保留会话参数；
7. 进入和退出 Glow，检查窗口亮度恢复、字幕 gap、播放结束与系统浮层。

## 项目关系

VortexCue 由 [TransVortex](https://github.com/zvensmoluya/TransVortex) 的产品研究孵化，但两者独立运行。未来可能通过开放格式交换字幕项目，不共享运行时或凭据。
