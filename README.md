# VortexCue / 荧荧

> **只让文字发光。**

荧荧是一款为睡前聆听而生的 Android 本地音视频播放器。它面向已经躺下、却还想继续听下去的时刻：声音不必停，屏幕可以退场；需要看清内容时，OLED 屏幕只留下与播放同步的当前文字。

已有字幕会自然随播放出现；没有字幕时，荧荧也能通过语音识别（ASR）生成同步文字，并借助大语言模型（LLM）完成翻译，让那些没有字幕或语言不同的本地内容，也能在微光中被听懂。

## 项目状态

项目尚未发布首个版本，目前仍处于产品与工程验证阶段。界面、数据结构和实现边界可能继续调整，仓库中的开发状态不构成已发布功能或兼容性承诺。

## 许可

VortexCue 源代码使用 [Apache License 2.0](LICENSE) 发布。项目名称、商标、Logo、艺术资源、示例媒体和第三方材料不随源代码许可证授权，具体以 [`NOTICE`](NOTICE) 及各自的权利声明为准。

## 文档

- [`docs/DOMAIN_CONTEXT.md`](docs/DOMAIN_CONTEXT.md)：长期领域背景与产品设计 DNA；
- [`docs/GUIDE.md`](docs/GUIDE.md)：当前产品行为和实施边界的正式规格；
- [`docs/README.md`](docs/README.md)：决策、验证记录、品牌探索和参考资料的索引；
- [`AGENTS.md`](AGENTS.md)：仓库协作、验证和提交约定。

涉及产品定位、用户流程或信息架构时，先理解 `DOMAIN_CONTEXT.md`，再以 `GUIDE.md` 确认当前要求。

## 开发

项目使用 Kotlin、Jetpack Compose 和仓库内的 Gradle Wrapper，当前需要 JDK 17 与 Android SDK 37。

在 Windows 上按改动范围选择检查：

```text
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat assembleDebug
```
