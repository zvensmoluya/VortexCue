# VortexCue / 荧荧

荧荧是一款 Android-first 的 OLED 微光字幕阅读器。它的核心约束很简单：媒体继续提供声音和时间，纯黑屏幕只保留当前字幕。

M0A 首轮 OLED 物理可行性验证已经完成，当前进入 M0B 内置样片体验。代码仍以纯黑界面、沉浸模式和亮度校准原型为起点；播放器、文件导入、云端服务和专属项目格式尚未加入。

## 当前原型

- Kotlin + Jetpack Compose；
- 单一 `app` 模块；
- 纯黑全屏字幕表面；
- 窗口亮度与字幕灰阶分别调节；
- 点按屏幕隐藏或显示校准控制；
- 不包含 Media3、网络、数据库或依赖注入框架。

当前产品、工程边界和实施顺序统一见 [`docs/GUIDE.md`](docs/GUIDE.md)，文档层级见 [`docs/README.md`](docs/README.md)。

M0A 首轮 OLED 真机验证记录见 [`docs/records/M0A_VALIDATION.md`](docs/records/M0A_VALIDATION.md)。

## 构建

需要 JDK 17+ 与 Android SDK 37：

```text
./gradlew assembleDebug
```

当前 application ID 是内部原型标识，公开发布前仍需结合发布主体确认。

## 项目关系

VortexCue 由 [TransVortex](https://github.com/zvensmoluya/TransVortex) 的产品研究孵化，但两者独立运行。未来可能通过开放格式交换字幕项目，不共享运行时或凭据。
