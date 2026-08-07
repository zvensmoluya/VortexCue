# VortexCue / 荧荧项目种子

> 归档说明：本文是项目建立时的完整设计种子，不再作为当前实施规格。当前要求见 [`../GUIDE.md`](../GUIDE.md)，正文中的阶段、路径和长期范围保留其形成时的原貌。

> 状态：新仓库创立种子  
> 更新时间：2026-08-06  
> 项目仓库：[zvensmoluya/VortexCue](https://github.com/zvensmoluya/VortexCue)  
> 中文产品名：荧荧  
> 英文 / 开源项目名：`VortexCue`  
> 推荐实现：Android 原生 Kotlin + Jetpack Compose + Media3  
> 关联开源项目：[TransVortex](https://github.com/zvensmoluya/TransVortex)  
> 文档来源：由 TransVortex 产品研究孵化，现作为 VortexCue 的独立项目文档维护

## 0. 本文用途

本文将一次产品可行性讨论沉淀为足以启动独立仓库的项目种子，目标是让新仓库在创建后不必重新讨论以下基础问题：

- 产品解决什么问题；
- 为什么 OLED 纯黑显示值得成为核心体验；
- 为什么首版选择 Android 原生而不是 Flutter；
- Media3、Android 原生媒体栈和 FFmpeg 各自承担什么；
- 真正锁屏、AOD 与“微光常亮”有什么区别；
- 专属字幕项目格式应该保存什么；
- 云端 ASR / LLM 如何在不破坏隐私边界的前提下加入；
- 新仓库如何初始化、验证和分阶段交付。

本文已归档到 `docs/reference/PROJECT_SEED.md`。其中仍有效的当前结论已收敛到统一指南，种子本身不再继续扩展。

---

## 1. 执行摘要

### 1.1 一句话定义

荧荧是一款 Android-first 的 OLED 微光字幕播放器：播放音频或视频时关闭所有非必要视觉元素，让纯黑背景不发光，只用最低可读亮度显示当前字幕。

### 1.2 核心产品假设

多数深色应用只是把背景变暗，但仍持续点亮导航、按钮、头像、进度条、卡片和大量文字。OLED 能够以像素为单位发光，因此可以把“允许点亮多少像素、以多高亮度点亮”作为产品的一等约束，而不只是主题颜色。

荧荧的核心不是“黑色播放器”，而是：

> 以发光像素预算为交互约束的字幕播放体验。

### 1.3 首版产品形态

- 用户导入本地音频或视频；
- 用户加载外挂 SRT / LRC，后续支持 WebVTT / ASS；
- 播放器拥有媒体时间轴并精确驱动字幕；
- 用户进入纯黑全屏微光模式；
- 视频可以关闭画面、只播放音频；
- 音频可在后台和真正锁屏后继续播放；
- 系统锁屏只显示标准媒体控制；
- 再次点亮设备时恢复微光字幕页面。

### 1.4 长期能力

- Android 原生媒体探测和音轨提取；
- 云端 ASR；
- 云端翻译 / LLM 处理；
- 可恢复任务、取消、重试和 usage；
- 专属、版本化的字幕项目格式；
- FFmpeg 作为复杂格式和媒体处理兜底；
- 未来通过项目格式与 TransVortex 可选互通；
- 若出现真实商业机会，再探索手机厂商 AOD / SystemUI 合作。

### 1.5 当前推荐结论

| 事项 | 结论 |
| --- | --- |
| 仓库 | 新建独立仓库 `VortexCue`，不放入 TransVortex |
| 产品名 | 中文“荧荧” |
| 英文 / 开源项目名 | `VortexCue`，不再仅作为临时代号 |
| 平台 | Android 优先，首版不做 iOS |
| UI | Kotlin + Jetpack Compose，不使用传统 XML 布局 |
| 播放 | Jetpack Media3 / ExoPlayer |
| 后台 | `MediaSessionService` |
| 字幕真相 | 独立 `SubtitleTimeline`，不以 Media3 Cue 作为持久业务状态 |
| 媒体处理 | 先用 Media3 / Android 原生媒体栈，FFmpeg 后置 |
| 云请求 | Provider 抽象；HTTP/2 是传输实现，不是用户概念 |
| 项目格式 | 暂定 Vortex Subtitle Project，扩展名 `.vtxsub` |
| AOD | 普通 App 不承诺；首版做“微光常亮” |

---

## 2. 命名与产品语言

### 2.1 中文产品名：荧荧

“荧荧”表达微弱、持续、柔和的发光状态，天然对应 OLED 局部发光体验。它比“黑屏播放器”“OLED 字幕”或“微幕”更像可独立建立记忆的消费产品名，也不会把未来能力锁死在歌词、视频或某一种字幕来源上。

正式发布前仍需完成：

- 中国及目标市场商标检索；
- Google Play、主流 Android 商店和搜索引擎重名检查；
- 域名、社交账号及包名可用性检查；
- 中文读音、输入法搜索和海外转写测试。

### 2.2 英文 / 开源项目名：VortexCue

`Cue` 表示带时间点的字幕、台词或提示，`Vortex` 保留与 TransVortex 品牌家族的联系。`VortexCue` 作为稳定的英文项目标识，用于 GitHub 仓库、代码命名空间、技术文档和面向国际开发者的交流；中文界面及中文传播继续使用“荧荧”。

这意味着 `VortexCue` 不再只是可能随时替换的内部代号。未来是否将它作为海外应用商店的最终显示名仍可单独验证，但不应因此反复重命名仓库和代码结构。

### 2.3 仓库名

确定使用：

```text
VortexCue
```

仓库名不附加 `-android`：Android 是首发平台，而不是产品身份的一部分。如果未来确实拆分多个平台仓库，再按需要采用：

```text
VortexCue-Android
VortexCue-iOS
VortexCue-Server
```

当前单仓库阶段保持 `VortexCue` 最简洁，也与 [TransVortex](https://github.com/zvensmoluya/TransVortex) 的项目谱系一致。

### 2.4 与 TransVortex 的关系

[TransVortex](https://github.com/zvensmoluya/TransVortex) 是现有的开源字幕、ASR 与翻译工作流项目，也是本项目讨论和能力积累的来源之一。荧荧应建立为独立 Android 仓库，但在 README 和项目文档中保留这一关联，方便用户理解项目谱系、发现桌面端能力，并为未来通过 `.vtxsub` 互操作留下清晰入口。

二者的推荐关系是：

- 荧荧专注 Android 上的 OLED 微光字幕消费体验；
- TransVortex 专注桌面端字幕处理、ASR、翻译与审看工作流；
- 两个项目可以共享格式规范和兼容样例，但不共享运行时、发布节奏或凭据；
- 荧荧不是 TransVortex Android 客户端，也不应要求 TransVortex 服务在线才能播放本地媒体；
- 新仓库 README 应在“Related projects”或“项目关系”部分显式链接 TransVortex，而不是只在致谢中提及。

### 2.5 不应使用的用户文案

| 不推荐 | 原因 | 推荐替代 |
| --- | --- | --- |
| 熄屏字幕 | 屏幕、触控和应用仍在工作 | 微光字幕 / 微光常亮 |
| AOD 字幕 | 普通 App 并未进入系统 AOD | OLED 微光模式 |
| 锁屏模式 | 可能只是前台防误触，设备没有真正锁定 | 防误触 / 微光常亮 |
| 零耗电黑屏 | 显示控制器、CPU、音频和网络仍耗电 | 减少发光面积 |
| 护眼 | 涉及未经证明的健康效果 | 低照度 / 低干扰 |
| 本地处理 | 如果选择云 ASR，媒体会上传 | 明确写出实际处理位置 |

### 2.6 宣传语方向

中文：

> 只让文字发光。

英文占位：

> Only the words emit light.

宣传语描述的是体验目标，不应被解释为严格的物理零发光或功耗保证。

---

## 3. 用户问题与目标场景

### 3.1 用户问题

用户在卧室、宿舍、夜间交通或其他低光环境中希望继续听媒体并阅读字幕，但普通播放器即使启用深色主题，仍会产生大片背景光、控件光和高对比度白字，容易干扰自己或附近的人。

现有系统深色主题和 Discord 一类 OLED 主题验证了纯黑界面的需求，但它们仍以完整应用界面为中心，没有把“最小发光面积”作为播放器的核心设计目标。

### 3.2 优先场景

1. 睡前听外语视频、播客或有声内容，只显示当前字幕；
2. 与伴侣或室友同处暗室时，避免普通屏幕大面积发光；
3. 将视频切换为音频模式，减少画面刺激但保留字幕理解；
4. 使用本地字幕学习外语；
5. 导入已有 LRC，以极低亮度显示歌词；
6. 后续对没有字幕的媒体发起云端 ASR 和翻译。

### 3.3 次级场景

- 暗室演示或展览中的低干扰提示；
- 低光环境的提词或台词提示；
- 听障用户在特定低光场景下阅读已生成字幕；
- 平板作为床头或桌面微光字幕屏。

### 3.4 非目标场景

首版不解决：

- 在其他 App 上方全局悬浮字幕；
- 捕获任意第三方 App 的受保护音频；
- 真正息屏 AOD 中高频刷新字幕；
- 电话、会议或现场对话的实时无障碍字幕；
- 专业 NLE 字幕编辑；
- 在线音乐歌词版权库；
- 社交、聊天、语音频道或 Discord 类社区功能；
- iOS 功能对齐。

---

## 4. 核心产品原则

### 4.1 发光预算优先

任何常驻视觉元素都应回答：它是否值得点亮这些像素？

- 背景严格为 `#000000`；
- 默认只显示当前一至两行；
- 控件按需短暂出现；
- 不显示常驻 Logo、专辑封面、卡片、底板和装饰；
- 不使用发光阴影、渐变背景和大面积高亮；
- 旧字幕尽快消失，而不是持续堆积。

### 4.2 可读性优先于极端暗

产品不是亮度竞赛。文字必须可读，并允许用户根据设备、环境和视力校准。

- 提供设备级最低可读亮度校准；
- 字体颜色与窗口亮度分开控制；
- 支持深灰、暖灰、暗红和琥珀预设；
- 支持字号、字重、行距、对齐和显示区域；
- 不把某一种颜色描述为普遍“更健康”。

### 4.3 播放是真实时间源

- 播放位置来自 Media3 Player；
- 字幕时间轴不自行累加时钟；
- seek、速度变化、暂停、缓冲和播放跳转都必须重新求值当前字幕；
- 不以 UI 动画进度代替播放器时间。

### 4.4 本地优先、远端透明

- 本地播放和已有字幕无需账号；
- 用户选择云 ASR 前明确说明媒体会发送到哪个 Provider；
- 用户选择翻译前明确说明哪些文本会发送；
- 默认不保存云端原始响应中不必要的敏感内容；
- 凭据和 Provider 配置分离。

### 4.5 标准格式不被专属格式替代

- SRT / LRC / WebVTT / ASS 是用户交付和互操作入口；
- `.vtxsub` 保存标准格式无法表达的项目状态；
- 用户始终可以导出通用格式；
- 不以格式锁定制造产品依赖。

---

## 5. AOD、锁屏与微光常亮的边界

### 5.1 真正 AOD

手机 AOD 通常由 SystemUI、厂商显示栈和低功耗硬件路径控制。屏下指纹也不是普通应用画布；AOSP 中 UDFPS 由 SystemUI 控制器、FingerprintService 和特殊显示模式协作完成。

普通第三方 App 不应承诺：

- 在手机 AOD 中自由全屏绘制；
- 在息屏低功耗状态下逐句高频刷新字幕；
- 调用屏下指纹的局部高亮链路；
- 在所有品牌手机上获得相同 AOD 行为。

### 5.2 系统锁屏

用户按下电源键后：

- 屏幕真正熄灭；
- `MediaSessionService` 继续播放音频；
- 系统锁屏显示标准媒体卡；
- 应用不尝试把逐句字幕伪装成不断变化的歌曲标题；
- 用户再次点亮屏幕时，应用恢复或重新进入微光页面。

`Activity.setShowWhenLocked(true)` 可以作为后续实验，让已经由用户启动的 Activity 在锁屏上方保持可见，但它仍是正常点亮的 Activity，不是 AOD。必须在 Pixel、Samsung、小米、OPPO、vivo 等设备上单独验证，不作为首版无条件保证。

### 5.3 微光常亮

荧荧的主模式：

- 应用保持前台；
- 屏幕技术上保持工作；
- OLED 黑色区域不发光；
- 字幕区域以最低可读亮度发光；
- 提供防误触；
- 用户始终可以通过系统手势退出；
- 产品明确说明这不是安全锁屏。

---

## 6. 微光界面规格草案

### 6.1 页面状态

```text
Normal Player
  -> Enter Glow Mode
Glow Mode: Controls Hidden
  -> Tap / Gesture
Glow Mode: Controls Temporarily Visible
  -> Timeout
Glow Mode: Controls Hidden
  -> Power button
System Screen Off, Audio Continues
  -> Screen wake
Restore Glow Mode or System Lock Surface
```

### 6.2 默认视觉

- Root surface：`#000000`；
- 无 Scaffold 默认背景泄漏；
- 无专辑封面；
- 无进度条；
- 无常驻系统栏；
- 当前字幕垂直位置默认低于屏幕中心，但避开手势区域；
- 只在字幕切换时更新必要内容；
- 不运行持续 60 FPS 的装饰动画。

### 6.3 亮度控制

建议两级控制：

1. 当前窗口亮度：影响所有点亮像素；
2. 字幕颜色值：进一步控制字幕自身亮度。

初始字幕灰阶可以提供实验档位，而不是承诺所有设备效果相同：

```text
RGB(4, 4, 4)
RGB(8, 8, 8)
RGB(12, 12, 12)
RGB(20, 20, 20)
Custom
```

部分 OLED 存在黑位压缩、低灰不均匀、颜色偏移或 PWM 敏感问题，因此首次使用应提供“调到刚刚能看清”的校准流程，并保存设备级结果。

### 6.4 字幕行为

- 当前字幕可一行或两行；
- 原文与译文可以上下排列，但默认不同时展示过多文本；
- 字幕切换使用短暂、低亮度的透明度过渡；
- 禁止白色闪烁或整屏转场；
- 长停顿时可让字幕逐步淡出；
- 可选轻微位置偏移，降低长期固定像素风险；
- 暂停时默认保留当前字幕，但允许用户设置自动淡出。

### 6.5 交互草案

| 操作 | 默认行为 |
| --- | --- |
| 单击 | 播放 / 暂停，或短暂显示极简控制；需用户测试二选一 |
| 双击左侧 | 快退 |
| 双击右侧 | 快进 |
| 左右滑动 | seek |
| 上下滑动 | 调整字幕亮度或字号；需避免误触 |
| 长按 | 显示完整控制并解除防误触 |
| 音量键 | 系统媒体音量 |
| 耳机按键 | 通过 MediaSession 控制播放 |

不要一次塞入全部手势。首轮用户测试应先确定单击的主要含义和退出防误触的可靠方式。

### 6.6 系统干扰

- 音频焦点丢失时按 Android 规范暂停或 duck；
- 拔出耳机时默认暂停；
- 来电或闹钟后恢复策略必须保守；
- 音量条、权限弹窗和系统通知可能比字幕亮，需要在 onboarding 中说明勿扰模式建议；
- 应用不应静默改变全局勿扰设置。

---

## 7. 技术选型

### 7.1 Android 原生而非 Flutter

选择原生不是因为 Compose 比 Flutter 更能显示纯黑，而是因为主要难点属于 Android 平台生命周期：

- Media3 / ExoPlayer；
- MediaSession；
- 后台播放服务；
- 音频焦点；
- 蓝牙和耳机按键；
- Window 亮度和沉浸模式；
- `showWhenLocked`；
- Storage Access Framework；
- MediaExtractor / MediaCodec / MediaMuxer；
- 后续 NDK / FFmpeg；
- 厂商电源管理差异。

如果使用 Flutter，上述能力仍需 Kotlin 插件或平台通道，最终形成 Dart、插件、Kotlin 三层状态同步。当前不做 iOS，跨平台 UI 的收益不足以抵消复杂度。

### 7.2 Compose 而非 XML View

现代 Android 新界面使用 Jetpack Compose。首版不需要传统布局 XML，除 Manifest、网络安全、图标和少量系统资源配置外，UI 和主题均在 Kotlin 中声明。

### 7.3 Media3 / ExoPlayer

Media3 负责：

- 播放器状态；
- 音视频解封装和播放；
- seek、速度、播放列表；
- 音轨选择；
- 后台播放；
- 系统媒体控制；
- 锁屏、蓝牙和耳机接入。

Media3 不负责成为：

- 项目格式；
- 字幕业务真实来源；
- ASR 任务系统；
- 翻译业务状态；
- 任意格式媒体处理的永久保证。

### 7.4 建议技术栈

- Kotlin；
- Jetpack Compose；
- AndroidX Lifecycle / ViewModel；
- Kotlin Coroutines + Flow；
- Media3 ExoPlayer；
- Media3 Session；
- Kotlin Serialization；
- DataStore：轻量设置；
- Room：仅在媒体库、任务或历史记录真正需要查询时加入；
- OkHttp：常规 Provider 请求首选；
- Cronet：有明确 HTTP/3 或高吞吐收益时再评估；
- NDK / CMake：FFmpeg 阶段再加入。

不要在仓库创建时固定本文撰写时的依赖版本。初始化时使用当时稳定版本，统一通过 version catalog 管理，并锁定可复现构建。

---

## 8. 建议架构

### 8.1 总览

```text
Compose UI
  ├─ Library / Open Media
  ├─ Normal Player
  ├─ Glow Mode
  └─ Settings / Calibration
          │
          ▼
PlaybackController / MediaController
          │
          ▼
MediaSessionService
  └─ Media3 ExoPlayer
          │
          ├─ audio output
          ├─ playback position
          ├─ track selection
          └─ system media controls

ProjectRepository ──> SubtitleTimeline ──> CurrentCueResolver
        │                    ▲                    │
        └─ .vtxsub / SRT / LRC / VTT / ASS      └─ Glow UI

MediaPreparation
  ├─ probe
  ├─ native demux
  ├─ Media3 Transformer
  ├─ PCM pipeline
  └─ FFmpeg fallback (later)

ProviderLayer
  ├─ ASR Provider
  └─ Translation Provider
```

### 8.2 包边界草案

```text
<reverse-domain>.vortexcue
  app/
  player/
  subtitle/
  glow/
  media/
  provider/
  project/
  settings/
  diagnostics/
```

初期保留一个 Gradle `app` module，使用包边界而不是过早拆模块。只有出现独立构建、明确依赖隔离或可复用需求时再拆：

- `core-model`；
- `core-subtitle`；
- `provider-api`；
- `native-media`。

### 8.3 业务模型不依赖 Media3

建议核心模型：

```text
Project
MediaReference
SubtitleTrack
SubtitleSegment
WordTiming
DisplayPreset
ProcessingTask
ProviderBinding
```

UI 和项目层不应持久保存 Media3 `MediaItem`、`Tracks`、`Cue` 或实现类。可以有一层很薄的 mapper：

```text
MediaReference -> MediaItem
PlaybackSnapshot <- Player state
SubtitleSegment -> RenderedCue
```

不需要为了理论可替换性创建庞大的自制 Player 接口。Media3 已经提供 `Player` 接口；只要防止 Media3 类型向项目持久层扩散即可。

### 8.4 字幕同步

字幕时间轴以播放器位置为真相：

- 监听播放状态和 position discontinuity；
- seek、速度变化、media transition 后立即重算；
- 不通过每帧轮询渲染字幕；
- 可根据下一个 segment 边界调度一次更新；
- 缓冲期间保留或隐藏字幕由产品设置决定；
- 时间单位统一为整数毫秒，内部需要更高精度时使用微秒但不写浮点秒。

### 8.5 文件访问

- 使用 Storage Access Framework；
- 保存用户明确授予的持久 URI 权限；
- 不请求广泛存储权限；
- 不假设 `content://` 一定能转换为真实文件路径；
- Media3、MediaExtractor 和上传层优先接受 `Uri` / `FileDescriptor`；
- 临时文件放应用缓存目录并有清理策略；
- 项目成果与可重建缓存分离。

---

## 9. Media3 的使用边界

### 9.1 为什么适合

- Android 官方 Jetpack 媒体栈；
- 支持常见本地和流媒体格式；
- 与 MediaSession、锁屏、蓝牙、Android Auto 等系统表面自然连接；
- 可禁用视频、音频或字幕轨；
- 支持后台播放器与 UI 分离；
- 有 Transformer 处理裁剪、移除轨道和标准导出。

### 9.2 已知风险

- 最终解码能力仍受设备 MediaCodec 和厂商实现影响；
- 冷门编解码器和某些 Dolby / DTS 格式可能不可用；
- 部分高级 API 带 `UnstableApi`；
- 真机行为比模拟器更重要；
- DRM 内容不能被任意导出或上传；
- 不能把“Media3 能播放”误写成“任何文件都保证可播放”。

### 9.3 风险控制

- 固定并集中管理 Media3 版本；
- 避免高级不稳定类型进入业务模型；
- 对不支持轨道给出明确诊断；
- 保存 MIME、容器、轨道和解码失败信息，但不泄露文件内容；
- 建立真实设备与真实媒体样本矩阵；
- FFmpeg 只作为后来经过验证的 fallback。

---

## 10. Android 原生媒体处理优先级

### 10.1 决策树

```text
只是播放视频声音，不需要生成文件？
  -> Media3 禁用视频轨

云端 ASR 接受原始媒体或原音轨？
  -> 直接流式上传，避免本地转码

只需无损分离常见压缩音轨？
  -> MediaExtractor + MediaMuxer

需要标准裁剪、移除视频或输出 AAC？
  -> Media3 Transformer

需要 mono / 16 kHz / PCM？
  -> MediaExtractor + MediaCodec + AudioProcessor / 自有 WAV writer

格式冷门、滤镜复杂或系统解码失败？
  -> FFmpeg fallback
```

### 10.2 禁用视频轨

微光播放视频时，优先直接禁用 Media3 视频轨：

- 不生成中间音频；
- 不占额外存储；
- 避免不必要的视频解码；
- 保留原媒体 seek 和时长语义。

### 10.3 无损分离

`MediaExtractor` 读取已编码音频 sample，`MediaMuxer` 写入受支持容器。适用于来源音轨已经满足上传或播放要求的情况。

优点：

- 不解码；
- 不重新编码；
- 速度快；
- 无质量损失；
- 无 FFmpeg 分发成本。

限制：容器和编码组合覆盖范围小于 FFmpeg。

### 10.4 Media3 Transformer

用于：

- 移除视频；
- 裁剪；
- 标准音频导出；
- 可观察进度和取消；
- 输入输出匹配时尽量 transmux，避免重编码。

### 10.5 PCM 流水线

云 ASR 常见目标输入是 mono / 16 kHz / 16-bit PCM。可以：

```text
MediaExtractor
  -> MediaCodec decode
  -> channel mix
  -> resample
  -> PCM chunks
  -> streaming upload or WAV
```

优先考虑边解码边上传，避免产生巨大临时 WAV。必须正确处理：

- 时间戳；
- EOS；
- 声道布局；
- 采样率；
- 取消；
- 网络背压；
- 前后台生命周期。

### 10.6 FFmpeg 阶段

只有真实样本证明原生管线不足时才引入。引入前必须完成：

- 明确构建配置是 LGPL 还是 GPL；
- 记录完整 configure flags；
- 提供对应源码、许可和通知；
- 只打包需要的 ABI 和组件；
- 支持 Android 15+ 16 KB page size；
- 用兼容 NDK 构建全部 `.so`；
- 验证取消、进度、日志和崩溃边界；
- 评估 AAB 下载和安装体积。

---

## 11. 字幕格式与项目格式

### 11.1 标准格式支持顺序

1. SRT；
2. LRC；
3. WebVTT；
4. ASS / SSA；
5. 媒体内嵌字幕轨；
6. 其他格式按真实样本决定。

### 11.2 项目格式定位

专属格式不是为了替代标准字幕，而是保存完整可恢复项目状态。工作名：

```text
Vortex Subtitle Project
*.vtxsub
```

正式命名仍可修改；首个公开文件一旦发布，就必须维护 schema 迁移和兼容，因此 MVP 之前不要随意公开扩展名。

### 11.3 容器草案

首版建议 ZIP + UTF-8 JSON：

```text
example.vtxsub
  manifest.json
  media.json
  segments.json
  display.json
  diagnostics.json      # optional
  assets/               # optional, default empty
```

不要默认嵌入完整媒体。项目文件保存媒体引用和哈希；只有用户明确选择“打包媒体”时才评估可移植归档。

### 11.4 `manifest.json` 草案

```json
{
  "format": "vortex-subtitle-project",
  "schema_version": 1,
  "project_id": "018f0000-0000-7000-8000-000000000000",
  "title": "Example project",
  "created_at": "2026-08-06T12:00:00Z",
  "updated_at": "2026-08-06T12:00:00Z",
  "timebase": "milliseconds",
  "source_language": "ja",
  "target_languages": ["zh-Hans"]
}
```

### 11.5 `media.json` 草案

```json
{
  "media_id": "media-1",
  "display_name": "example.mp4",
  "uri_hint": null,
  "size_bytes": 123456789,
  "duration_ms": 3600000,
  "sha256": "optional-lowercase-hex",
  "container_mime": "video/mp4",
  "selected_audio_track": {
    "language": "ja",
    "channels": 2,
    "sample_rate_hz": 48000
  }
}
```

`uri_hint` 不应被视为可移植路径或授权。Android 的 `content://` 权限属于应用和设备，导入项目后必须允许用户重新定位媒体，并用大小、时长、哈希辅助确认。

### 11.6 `segments.json` 草案

```json
{
  "tracks": [
    {
      "track_id": "source",
      "kind": "subtitles",
      "language": "ja",
      "segments": [
        {
          "id": "seg-000001",
          "start_ms": 1200,
          "end_ms": 4380,
          "source_text": "こんばんは。",
          "translated_text": {
            "zh-Hans": "晚上好。"
          },
          "speaker": null,
          "confidence": 0.97,
          "words": [],
          "review_state": "unreviewed",
          "provenance": {
            "kind": "imported",
            "provider": null,
            "model": null
          },
          "flags": []
        }
      ]
    }
  ]
}
```

### 11.7 格式规则

- JSON 统一 UTF-8，无 BOM；
- 时间为非负整数毫秒；
- `end_ms >= start_ms`；
- segment ID 在项目内稳定；
- 语言使用 BCP 47；
- 未知字段默认保留或忽略，不应导致数据丢失；
- 破坏性 schema 变化提升 major schema version；
- 导入时严格限制 ZIP 解压路径、文件数和解压总大小；
- 禁止路径穿越；
- diagnostics 默认可删除且不影响项目结果；
- secret、token、完整 Provider key 永远不得写入项目文件。

### 11.8 TransVortex 的未来互通

[TransVortex](https://github.com/zvensmoluya/TransVortex) 与荧荧的互通应通过格式而不是共享运行时：

```text
TransVortex
  -> export .vtxsub
  -> 荧荧播放

荧荧
  -> ASR / 调整 / 保存 .vtxsub
  -> TransVortex 导入审看
```

在真实互通需求出现前：

- 不复制 TransVortex Python core；
- 不依赖 TransVortex Local Service；
- 不共享凭据文件；
- 不要求两个产品同步发布；
- 不为假设中的互通延迟首版。

---

## 12. 云端 ASR 与翻译设计种子

### 12.1 产品概念与技术字段分离

用户选择：

- 识别服务；
- 模型；
- 本地或远端；
- 是否上传媒体；
- 翻译服务和目标语言。

实现内部才关心：

- HTTP/1.1、HTTP/2 或 HTTP/3；
- multipart、SSE、WebSocket 或其他协议；
- chunk size；
- retry policy；
- request ID。

不要把“HTTP/2 ASR”作为用户可见 Provider 类型。HTTP 版本是协商后的传输能力，不是稳定业务语义。

### 12.2 Provider 接口草案

```text
AsrProvider
  capabilities()
  preflight()
  estimate()
  transcribe()
  cancel()
  usage()

TranslationProvider
  capabilities()
  preflight()
  estimate()
  translate()
  cancel()
  usage()
```

Provider 能力应明确：

- 接受的媒体 MIME；
- 最大文件或时长；
- 是否接受原视频；
- 是否需要 PCM；
- 支持的语言；
- 时间戳粒度；
- 流式或批处理；
- 使用量是否可查询；
- 是否支持客户端 request ID 和幂等。

### 12.3 凭据

- 凭据存入 Android Keystore 保护的本地存储；
- Provider 配置只保存 `credential_id`；
- 项目文件不保存 credential；
- 日志对 Authorization、API key、signed URL 和 token 统一脱敏；
- 示例使用 `YOUR_API_KEY`、`example-token`；
- 删除 Provider 时明确说明是否同时删除凭据。

### 12.4 任务生命周期

ASR / 翻译是可恢复任务，不应绑死在 Compose 页面：

```text
queued
preparing_media
uploading
processing
downloading_result
normalizing
completed / failed / cancelled
```

- 用户主动等待的长任务使用合规 foreground service 和可见通知；
- 可推迟工作使用 WorkManager；
- UI 退出不等于取消；
- 任务取消必须传递到网络、解码和临时文件；
- 结果先标准化为项目 segments，再进入显示；
- 原始 Provider 响应是诊断证据，不直接成为字幕业务真相。

### 12.5 上传策略

优先级：

1. Provider 直接接受原媒体时流式上传原媒体；
2. 接受原音轨时无损分离；
3. 需要标准音频时使用原生 PCM 流水线；
4. 原生不兼容时使用 FFmpeg。

避免在内存中一次性读取完整媒体。支持取消、超时、网络切换和背压。

---

## 13. 隐私、安全与内容边界

### 13.1 默认隐私承诺

- 本地播放不联网；
- 本地字幕解析不联网；
- 云 ASR 只在用户明确发起后上传；
- 翻译只发送完成任务所需文本和上下文；
- 默认不记录原始音频；
- 日志不包含字幕全文，除非用户明确导出诊断且得到提示；
- crash report 默认不附带媒体路径、字幕正文和凭据。

### 13.2 媒体和字幕内容

- 只处理用户明确选择的文件；
- 不扫描整个设备媒体库作为默认行为；
- 不绕过 DRM；
- 不承诺提取受保护媒体；
- 在线歌词库涉及授权，首版只支持用户导入 LRC 或其合法来源；
- 云端 Provider 的数据处理条款必须可查看。

### 13.3 文件安全

- 所有项目 ZIP 按不可信输入处理；
- 限制单文件和总解压大小；
- 防止 zip bomb 和路径穿越；
- 导入 JSON 有 schema 校验和字段长度限制；
- 不自动执行项目内任何脚本、URI 或外部命令；
- 外部链接必须由用户确认。

---

## 14. 新仓库初始化建议

### 14.1 顶层结构

```text
VortexCue/
  .github/
    workflows/
  app/
  docs/
    PRODUCT.md
    ARCHITECTURE.md
    PROJECT_FORMAT.md
    PRIVACY.md
    VALIDATION.md
  gradle/
    libs.versions.toml
  samples/
    subtitles/
  AGENTS.md
  README.md
  LICENSE
  SECURITY.md
  CHANGELOG.md
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
```

### 14.2 初始化原则

- 使用 Android Studio 当前稳定版创建 Empty Compose Activity；
- Kotlin DSL；
- version catalog；
- target / compile SDK 使用创建时满足 Play 要求的稳定版本；
- minSdk 在首轮设备覆盖讨论后决定，候选 26；
- application ID 在发布者身份和域名确定后冻结；
- application ID 一旦发布不可随意修改，不要使用临时个人域名草率上架；
- 签名密钥、服务账号和 Provider key 不进入仓库；
- 首版只打包必要 ABI；
- 加入 native code 后立即验证 16 KB page size。

### 14.3 初始依赖

只加入首个 milestone 必需依赖：

- Compose BOM；
- Activity Compose；
- Lifecycle ViewModel Compose；
- Media3 ExoPlayer；
- Media3 Session；
- Kotlin Coroutines；
- Kotlin Serialization；
- DataStore（当设置需要持久化时）。

暂不加入：

- FFmpeg；
- Room；
- 依赖注入框架；
- 多模块架构；
- 网络框架；
- 分析和广告 SDK；
- 账号 SDK；
- 云 Provider SDK。

只有真实功能需要时再引入，保持依赖和数据边界可审查。

### 14.4 工程质量

建议：

- Kotlin 官方格式或 ktlint 二选一并固定；
- Android Lint；
- 单元测试；
- Compose UI 测试；
- Media3 状态适配器测试；
- JSON Schema / fixture 测试；
- secret 扫描；
- dependency review；
- release build 可复现性记录。

初始 CI：

```text
./gradlew lint
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

真机媒体和 OLED 验证不能被模拟器 CI 替代，应保留人工验收表或后续设备农场任务。

### 14.5 建议 `AGENTS.md` 核心规则

新仓库至少包含：

- 默认中文沟通，代码和标识符使用英文；
- Compose 是唯一应用 UI，不新增 XML View 页面；
- Media3 是播放设施，项目模型不得依赖 Media3 持久类型；
- 不在配置、日志、提交和样例中保存 secret；
- 云上传必须有用户可理解的明确提示；
- 不宣称 AOD、熄屏显示、零功耗或医疗效果；
- 只在用户明确要求时 commit；
- FFmpeg 加入前完成许可和 16 KB page-size 设计；
- UI 或播放生命周期变更需要真机验证；
- 项目格式变更必须更新 schema、fixture 和迁移测试。

---

## 15. 分阶段路线图

### Milestone 0：OLED 真实性原型

目标：证明“只让字幕发光”的体验真实存在。

范围：

- 单一 Compose Activity；
- 背景 `#000000`；
- 模拟字幕切换；
- 窗口亮度与字幕灰阶；
- 沉浸模式；
- 防止自动熄屏；
- 无媒体、无文件、无网络。

验收：

- 至少 5 台真实 OLED 手机；
- 暗室中背景区域与屏幕边界接近不可见；
- 最低可读亮度可按设备校准；
- 无系统栏或默认 Surface 泄漏；
- 不持续进行无意义重绘；
- 记录 LCD 对照结果；
- 记录 PWM、黑位压缩和低灰不均匀观察。

### Milestone 1：本地音频播放器

范围：

- SAF 打开本地音频；
- MediaSessionService；
- 播放、暂停、seek、速度；
- 后台播放；
- 锁屏媒体卡；
- 耳机按键和拔出暂停；
- 普通播放器页和微光页切换。

验收：

- UI 进程重建后能重新连接播放服务；
- 屏幕关闭后音频继续；
- 通知和系统媒体卡状态准确；
- 用户明确停止后服务结束；
- 不泄漏 Player 或 Session。

### Milestone 2：字幕播放 MVP

范围：

- `SubtitleSegment` / `SubtitleTimeline`；
- SRT；
- LRC；
- 当前字幕 resolver；
- seek 和变速后准确重算；
- 字幕偏移；
- 原文 / 译文显示策略；
- 微光显示 preset。

验收：

- 真实一小时媒体无持续漂移；
- 暂停、seek、倍速、切换文件后字幕正确；
- UTF-8 中文、日文和 emoji 不乱码；
- 错误文件给出可恢复提示；
- 标准字幕解析有 fixture 测试。

### Milestone 3：视频音频化与项目格式

范围：

- 打开视频；
- 禁用视频轨；
- 选择音轨；
- `.vtxsub` 内部实验格式；
- 媒体重新定位；
- 项目保存与恢复；
- WebVTT / ASS 逐步加入。

验收：

- 禁用视频后不创建视频 Surface 或无意义解码；
- 多音轨选择可见且持久；
- `.vtxsub` round-trip 不丢 segments；
- 项目导入按不可信 ZIP 处理；
- 项目不保存 secret。

### Milestone 4：云 ASR

范围：

- 单一 Provider；
- 凭据安全存储；
- preflight；
- 直接上传 Provider 支持的原媒体；
- 任务状态、取消、失败恢复；
- 结果标准化为 segments；
- usage 基础记录。

验收：

- 上传前明确说明媒体离开设备；
- 大文件不整段读入内存；
- 取消能终止网络和清理临时文件；
- token 不进入日志或项目；
- UI 退出不抹掉任务状态；
- Provider 错误有明确恢复动作。

### Milestone 5：翻译与多轨字幕

范围：

- 单一 Translation Provider；
- 源语言和目标语言；
- 原文 / 译文轨；
- 分片和上下文；
- 可取消任务；
- `.vtxsub` 保存 provenance。

验收：

- 翻译不修改源段时间轴；
- 失败可重试；
- 不静默切换 Provider；
- 用户能只显示原文、只显示译文或双语。

### Milestone 6：媒体预处理

顺序：

1. MediaExtractor 探测；
2. 无损音轨分离；
3. Media3 Transformer；
4. 原生 PCM 流水线；
5. FFmpeg fallback。

每一步都以真实不兼容样本为进入条件，不因为“以后可能需要”提前加入复杂依赖。

### Milestone 7：可选生态互通

- TransVortex `.vtxsub` 导出；
- TransVortex `.vtxsub` 导入；
- 格式兼容 fixture；
- 局域网传输或扫码交接；
- 不共享凭据和运行时。

---

## 16. 首批建议 Issues

### P0：验证产品物理体验

1. Create Compose OLED black-surface prototype
2. Add per-window brightness and subtitle luminance controls
3. Implement immersive system bar handling
4. Build subtitle luminance calibration flow
5. Define real-device dark-room validation sheet

### P1：建立播放骨架

6. Create MediaSessionService with Media3 ExoPlayer
7. Connect Compose UI through MediaController
8. Open local media through Storage Access Framework
9. Implement playback lifecycle and media notification
10. Handle audio focus, headset unplug and media buttons

### P1：字幕时间轴

11. Define SubtitleSegment and SubtitleTimeline
12. Implement SRT parser with fixtures
13. Implement LRC parser with fixtures
14. Resolve current subtitle from Player position
15. Handle seek, speed and media transition discontinuities

### P1：微光播放器

16. Implement Glow Mode hidden-controls state
17. Add subtitle offset and display presets
18. Add safe anti-burn-in position shift experiment
19. Restore Glow Mode after Activity recreation
20. Validate screen-off playback and wake behavior across OEMs

### P2：项目格式实验

21. Draft `.vtxsub` JSON Schema
22. Add safe ZIP import and export
23. Add media relink by metadata and hash
24. Add project round-trip fixtures

首批 issue 不应同时全部开工。建议先完成 1–5，用最小原型证明体验，再投入播放架构。

---

## 17. 验证矩阵

### 17.1 设备

至少覆盖：

- Pixel OLED / LTPO OLED；
- Samsung AMOLED；
- 小米或 Redmi OLED；
- OPPO / OnePlus OLED；
- vivo / iQOO OLED；
- 一台 LCD Android 对照；
- 一台低端或旧款 Android 设备。

品牌不是固定承诺，选择应以真实可获得设备为准。

### 17.2 OLED 体验

记录：

- 黑背景是否视觉消失；
- RGB 4 / 8 / 12 / 20 的最低可读档位；
- 系统最低亮度与窗口亮度关系；
- 暗红、琥珀、暖灰可读性；
- 字幕切换时黑色拖影；
- 屏幕保护膜和视角影响；
- PWM 敏感反馈；
- 一小时后的温度和耗电；
- 旁人是否注意到屏幕发光。

### 17.3 播放生命周期

- Home；
- Recent task swipe；
- 电源键熄屏；
- 重新点亮；
- 系统锁屏；
- 来电；
- 闹钟；
- 蓝牙断开；
- 有线耳机拔出；
- 省电模式；
- 厂商后台限制；
- 进程被杀后恢复；
- 横竖屏和字体缩放。

### 17.4 媒体样本

- MP3；
- M4A / AAC；
- FLAC；
- Opus / Ogg；
- MP4 H.264 + AAC；
- MKV 常见音轨；
- 多音轨视频；
- 内嵌字幕；
- 长时媒体；
- VFR 视频；
- 损坏文件；
- `content://` 云盘或文档提供者 URI；
- 明确不支持的 DRM 样本。

### 17.5 字幕样本

- UTF-8 SRT；
- BOM SRT；
- CRLF / LF；
- 重叠段；
- 零时长段；
- 非法时间；
- 超长行；
- 中日韩；
- RTL；
- emoji；
- LRC 多时间标签；
- offset 标签；
- ASS 样式降级策略。

---

## 18. 主要风险与控制

| 风险 | 影响 | 控制 |
| --- | --- | --- |
| 市场过小 | 技术成立但没人持续使用 | 先做 OLED 原型和真实暗室访谈 |
| 纯黑只是主题 | 容易被复制 | 将亮度校准、时间轴、低发光交互和媒体处理做成系统体验 |
| 功耗仍高 | 用户误以为接近 AOD | 明确“低发光面积”而非“息屏低功耗”，做一小时功耗测试 |
| OLED 低灰差异 | 部分设备文字不可读 | 设备级校准和多颜色 preset |
| 烧屏 / 残影 | 长时间固定内容 | 无常驻 UI、字幕自然变化、轻微位置偏移、超时策略 |
| MediaCodec 差异 | 部分媒体失败 | 明确诊断、真实样本矩阵、FFmpeg 后置 fallback |
| 厂商后台限制 | 熄屏后播放中断 | MediaSessionService、合规通知、OEM 真机验证 |
| FFmpeg 体积和许可 | 发布复杂度上升 | 延后引入、最小构建、许可材料、16 KB 验证 |
| 云上传隐私 | 信任损失 | 发起前披露、Provider 边界、凭据隔离、日志脱敏 |
| 歌词版权 | 无法合法提供完整歌词库 | 首版只导入用户 LRC，授权服务另行评估 |
| 专属格式锁定 | 用户反感、兼容成本 | 始终支持标准导出，公开 schema 和 fixture |
| 功能膨胀 | 失去微光核心 | 每项功能必须说明如何改善播放或字幕消费 |
| 名称冲突 | 发布前返工 | 工作名与公开品牌分离，发布前做正式检索 |

---

## 19. 关键开放问题

### 产品

- 首个核心内容类型是外语视频、播客、有声书还是歌词？
- 单击应该播放/暂停，还是显示控制？
- 暂停时字幕应常驻还是淡出？
- 双语默认同时显示，还是只显示用户选择的一轨？
- 是否允许用户在微光模式中做轻量字幕修正？
- 收费模式是一口价、免费基础版还是云能力订阅？

### 技术

- minSdk 选择 23、26 还是更高？
- 首版是否需要视频容器，还是先只做音频？
- LRC 是否允许逐字时间，内部如何统一？
- Media3 原生字幕轨与自有字幕时间轴如何共存？
- 是否需要 Room，还是项目文件 + DataStore 足够？
- 首个云 ASR Provider 是标准 multipart API 还是自有服务？
- 大文件任务是否必须首版支持断点续传？

### 品牌与发布

- “荧荧”能否注册并被用户准确搜索？
- 英文公开名是否保留 Vortex 家族？
- 发布主体、包名和隐私政策域名是什么？
- 首发 Google Play 还是先内部 APK / GitHub Release？
- 是否开源客户端；若开源，Provider 和商业能力如何分层？

开放问题不阻止 Milestone 0；在进入公开发布前逐项决定。

---

## 20. Go / No-Go 判定

### 继续进入播放器开发的最低证据

- 多台 OLED 真机能够形成“背景消失、只有字幕发光”的可感知体验；
- 大多数测试者能校准到既可读又明显低于普通暗色播放器的亮度；
- 附近的人主观感知到的干扰显著降低；
- 没有无法接受的系统栏、弹窗或低灰显示缺陷；
- 一小时使用的功耗和温度处于可解释、可接受范围；
- 至少一个明确人群愿意重复使用，而不只是认为概念新奇。

### 暂停或转向的信号

- 多数 OLED 在最低可读文字亮度下仍明显照亮环境；
- 用户更倾向直接熄屏听音频，不愿阅读字幕；
- 系统 Live Caption 或普通播放器已足够满足目标场景；
- 用户无法理解“微光常亮”与系统锁屏的区别；
- 主要价值最终变成普通播放器、普通 ASR 或普通翻译功能。

---

## 21. 新仓库创建清单

- [x] 创建独立仓库 `VortexCue`；
- [x] 初始仓库设为私有；
- [ ] 选择许可证；
- [ ] 用当前稳定 Android Studio 创建 Kotlin Compose 项目；
- [ ] 加入 `AGENTS.md`；
- [x] 将本文归档到 `docs/reference/PROJECT_SEED.md`；
- [ ] 从本文拆出最小 `README.md`；
- [ ] 在 README 的项目关系部分链接 [TransVortex](https://github.com/zvensmoluya/TransVortex)，并说明二者独立运行；
- [ ] 建立 `PRODUCT.md`、`ARCHITECTURE.md`、`VALIDATION.md` 空骨架；
- [ ] 配置 CI：lint、unit test、assembleDebug；
- [ ] 配置 secret scanning；
- [ ] 创建 Milestone 0 的 5 个 issues；
- [ ] 不加入 Media3，先完成纯黑显示原型；
- [ ] 在真实 OLED 上验证后再进入播放器骨架；
- [ ] 原型证据归档到新仓库；
- [ ] 在确定发布主体前不要冻结 application ID；
- [ ] 在首个公开 `.vtxsub` 文件前冻结 schema v1 规则；
- [ ] 在引入 FFmpeg 前完成单独技术与许可决策。

---

## 22. 官方技术参考

- [TransVortex 开源仓库](https://github.com/zvensmoluya/TransVortex)
- [Android Compose-first](https://developer.android.com/develop/ui/compose/first)
- [Android 沉浸模式](https://developer.android.com/develop/ui/views/layout/immersive)
- [Media3 概览](https://developer.android.com/media/media3)
- [Media3 后台播放与 MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Media3 轨道选择与禁用视频轨](https://developer.android.com/media/media3/exoplayer/track-selection)
- [Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Media3 支持设备](https://developer.android.com/media/media3/exoplayer/supported-devices)
- [Media3 Transformer](https://developer.android.com/media/media3/transformer/transformations)
- [Android MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
- [Android MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
- [Activity.setShowWhenLocked](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean))
- [AOSP UDFPS SystemUI controller](https://android.googlesource.com/platform/frameworks/base/+/296c28979c5b212e9e32be397901f93e15b60bf3/packages/SystemUI/src/com/android/systemui/biometrics/UdfpsController.java)
- [Android 16 KB page-size 支持](https://developer.android.com/guide/practices/page-sizes)
- [FFmpeg 许可说明](https://ffmpeg.org/legal.html)
- [Android Cronet](https://developer.android.com/develop/connectivity/cronet)
- [Discord OLED 主题作为需求参照](https://discord.com/blog/refining-discords-mobile-experience-with-your-feedback)

---

## 23. 最终建议

荧荧值得以一个非常小的 Android 原生实验开始，而不是先以完整播放器、云字幕平台或 TransVortex 伴侣的规模开工。

正确的第一步只有一个：

> 在真实 OLED 手机上证明，纯黑背景与最低可读字幕能够形成用户愿意反复使用的低干扰体验。

如果这个体验成立，Media3、Android 原生媒体栈、云 ASR、翻译和项目格式都有清晰的增量路径；如果这个体验不成立，及早停止也不会留下沉重架构成本。
