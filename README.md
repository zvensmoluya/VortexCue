# VortexCue / 荧荧

荧荧是一款 Android-first 的 OLED 微光同步文字播放器。它的核心约束很简单：音频继续提供声音和时间，纯黑屏幕只保留当前歌词或字幕。

M0A 首轮 OLED 物理可行性验证已经完成，当前进入 M0B 本地音频播放基础。应用可以直接选择设备上的音频与 LRC/SRT，用播放器时间轴驱动微光文字；后台服务、会话恢复和媒体库尚未加入。

## 当前原型

- Kotlin + Jetpack Compose；
- 单一 `app` 模块；
- Media3 ExoPlayer 本地音频播放；
- Storage Access Framework 选择音频与 LRC/SRT，无广泛存储权限；
- LRC/SRT 解析为统一字幕时间轴；
- 播放、暂停、前后 10 秒与字幕偏移；
- 纯黑全屏微光文字表面；
- 窗口亮度与字幕灰阶分别调节；
- 点按屏幕隐藏或显示当前原型控制；
- 不包含网络、数据库、依赖注入或后台媒体服务。

当前产品、工程边界和实施顺序统一见 [`docs/GUIDE.md`](docs/GUIDE.md)，文档层级见 [`docs/README.md`](docs/README.md)。

M0A 首轮 OLED 真机验证记录见 [`docs/records/M0A_VALIDATION.md`](docs/records/M0A_VALIDATION.md)。

## 构建

需要 JDK 17+ 与 Android SDK 37：

```text
./gradlew assembleDebug
```

当前 application ID 是内部原型标识，公开发布前仍需结合发布主体确认。

## 真机播放器验证

1. 选择一个本地音频文件；
2. 选择对应的 `.lrc` 或 `.srt`；
3. 播放并检查暂停、恢复、前后 10 秒和字幕偏移；
4. 点按隐藏控制，观察纯黑背景只保留当前文字；
5. 字幕 gap、播放结束或播放失败时确认文字被清空。

## 项目关系

VortexCue 由 [TransVortex](https://github.com/zvensmoluya/TransVortex) 的产品研究孵化，但两者独立运行。未来可能通过开放格式交换字幕项目，不共享运行时或凭据。
