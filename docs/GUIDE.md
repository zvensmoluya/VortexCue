# VortexCue 产品与实施指南

> 本文是当前 UI、信息架构和实现边界的最高产品规格。
> `docs/reference/UI_LAYOUT_DESIGN.md` 记录布局与交互细节；`docs/reference/布局原型.html` 仅保留结构参考，不代表视觉风格。
> 长期领域背景见 [`DOMAIN_CONTEXT.md`](DOMAIN_CONTEXT.md)。

VortexCue 是 Android-first、OLED-first、sleep-first 的本地内容播放器。核心价值是“内容继续，屏幕退场；需要视觉时只显示当前同步文字”。字幕不是播放前置条件，但在 Normal Player 与 Glow 中是最小、可读、可退出的视觉内容。

## 1. 应用壳层

一级页面固定为四个：

```text
内容 | 播放列表 | [骨白荧荧 Logo：Import Hub] | 配置 | 设置
```

Logo 是动作，不是第五个页面；点击只打开 Import Hub，不改变当前 Tab。页面不再出现 Home、今晚、发现、来源一级入口或命名 Playlist。

存在 Current Queue 时，Mini Player 跨四个 Tab 显示。点击 Mini Player 进入 Normal Player；播放键直接播放/暂停。冷启动不恢复 Glow，Glow 只能由当前运行期的 PlayerSurface 明确进入。

应用内视觉令牌：

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| Background | `#000000` | 页面与 Glow 背景 |
| Primary text | `#EEF0F4` | 月白主文字 |
| Secondary text | `#A39EAF` | 冷紫灰次要文字 |
| Raised surface | `#17151D` | Bottom Sheet、对话框、控件层 |
| Hairline | `#2A2732` | 细分隔线 |
| Selection | `#282432` | 当前项与选中态 |
| Mascot | `#E8E0D0` | 中央骨白 Logo |

黄色不进入应用内 UI；Launcher 与 Splash 保留既有品牌资源。列表不使用封面图，最多 8dp 圆角，分隔线细而克制。交互控件最小 48dp 触控区，图标按钮必须有 TalkBack 描述；拖动、长按等动作同时提供可访问的替代操作。

## 2. 内容

内容页直接表示用户收录的媒体。根目录是默认位置，虚拟文件夹只有一层；一个 `contentId` 最多属于一个文件夹，没有关系即根目录。删除文件夹只清除关系，成员回到根目录，不删除原文件。

顶部固定显示文件夹，下面是全局搜索与排序。排序选项为“最近加入 / 最近播放 / 标题”，选择写入 Settings DataStore；文件夹永远排在媒体之前。

点击媒体立即以单项替换 Current Queue 并播放。长按 Bottom Sheet 提供：

- 立即播放；
- 加入播放列表；
- 移动到文件夹；
- 同步文字；
- 移出内容。

“移出内容”只写入内部忽略状态，不删除原始媒体、不打断当前播放；来源刷新不会再次推荐。来源详情的次级入口可以恢复忽略项。

空内容页直接提供“选择文件”和“从文件夹查找”，都复用 Import Hub。没有首次样片，也没有“未分类”虚拟文件夹。

## 3. Import Hub 与来源

Import Hub 提供：选择单文件、从文件夹查找、扫描设备媒体、已授权来源列表。

- Logo 选择单文件：加入内容根目录并立即播放；
- 外部 `ACTION_VIEW`：只进入当前 Queue，不自动收录；Player 提供“加入内容”；
- 文件夹扫描默认勾选全部可播放音视频；
- 全设备扫描默认只勾选音频；
- 批量导入默认进入根目录，也可当场选择或新建虚拟文件夹；
- 来源重扫只展示本次新增项；已移出/忽略项在来源详情中恢复；
- 来源失效、重新授权、取消扫描和媒体重新定位就地反馈，不建立异常管理中心。

来源是扫描授权与刷新边界，不是内容页的虚拟文件夹。扫描不得覆盖用户的忽略决定。

## 4. 播放列表

“播放列表”页面只有一个 Current Queue，数据与 Player 的队列对齐。支持选择播放、拖动重排、移除、清空和当前项标识。队列内同一 `contentId` 唯一，重复加入不追加，只提示“已在播放列表”。

队列连续播放、MediaSession、后台播放、耳机/蓝牙控制和 Sleep Timer 是固定行为，不在设置页暴露开关。

## 5. 配置

配置页只有一套完整的 `SyncTextConfig`，顶部切换“翻译模型配置”和“语音识别”。不向用户展示 Scheme、Attempt、Task Queue、Token、Hash 或技术结果详情。内部仍使用稳定配置 ID 关联后台审计与 attempt snapshot。

翻译配置提供协议选择、可编辑的 Base URL、自动发现的模型选择、API Key、目标语言和展示模式。官方示例只展示服务根地址或 `/v1` 地址；用户不需要填写 `/responses`、`/chat/completions` 或其他资源路径，应用根据协议在内部补全。Base URL 或协议变化后静默尝试拉取可用模型，不提供刷新按钮；服务不提供模型目录时仍允许直接填写模型。用户可以发送一个低额度测试请求，确认所选协议、地址、模型和 Key 真正可用。Key 由 Android Keystore 加密保存，Endpoint 必须经过 HTTPS 与主机校验。

ASR 配置先选择提供商，再选择该提供商的模型；OpenRouter 提供 Whisper large-v3 与 Grok STT，并保留可编辑的 Base URL。本项目对 OpenAI 官方提供商当前只开放 Whisper-1，固定使用官方 `/v1/audio/transcriptions` multipart 上传协议，不复用 OpenRouter 的 JSON 音频协议。GPT Transcribe 等只能返回纯文本、不能返回可用时间轴的模型不进入可选列表；未来只有在官方提供时间轴后才重新评估。每个提供商由独立适配器负责请求与响应；用户可以发送一个极短的测试音频确认连接，测试不会创建同步文字结果。

生成自动使用当前配置：已有文字优先翻译，没有文字才执行 ASR；只有多音轨等真实歧义才请求选择。单个 ASR 窗口允许没有语音并以空时间轴继续，整个媒体都没有可用 cue 时明确提示“未检测到可转写语音”，不归因于提供商或网络故障。状态只在当前内容页、Player 和同步文字页就地显示“准备中 / 完成 / 失败”。同步文字页提供取消与重试，不出现全局任务抽屉。

## 6. 设置

设置只负责 Glow 窗口/文字/控制亮度、字号、垂直位置、预设与首次校准，以及权限状态和关于。系统栏隐藏、队列连续播放等固定行为不做可选项。

Glow 为纯黑、安全 Insets、独立亮度；双击分区、长按临时控制、5 秒退场保持现有行为。Glow 返回 Normal Player，再返回原一级页面。

## 7. Player 与同步文字

Normal Player 的主体固定为“上一句弱化 / 当前句主视觉 / 下一句弱化”，不显示假封面。播放控制、进度、速度、同步偏移、Sleep Timer、播放列表和进入 Glow 按本指南视觉重排。

同步文字页面只负责主文字、第二文字、手动文件、恢复自动匹配和生成状态。手动导入会读取、解析并规范化 SRT / VTT / ASS / LRC，然后发布为应用自有的 immutable revision；成功后的文字轨不再依赖原 URI 或 SAF 权限。Folder 扫描到的外挂字幕只登记为候选，首次自动命中、用户点选或生成流程采用时才物化；媒体播放不等待候选导入。内嵌文字仍由 Media3 实时播放，仅在生成任务把它作为固定输入时物化 source revision。生成结果完成后自动成为可选文字轨。

## 8. 数据与迁移

Room 当前 schema 为 v6，并允许从 v5 及其他不兼容版本破坏性重建。重建清理应用私有旧字幕缓存、旧 result/checkpoint VTT、孤立凭据文件和旧的命名 Playlist/Home shortcut 数据；保留 Settings、用户原始媒体和 SAF 外部字幕文件。数据库不再把 `lastSurface` 当作启动入口；`PlayerSurface` 只管理运行期 Normal/Calibration/Glow 层级。

导入与生成文字统一使用 `CanonicalTimeline`、`TimelineRevision` 和 content-addressed `TextArtifactStore`。Artifact 位于 `noBackupFilesDir/text_artifacts/<kind>/<prefix>/<id>.bin`，ID 是包含 kind、schema、codec 与 canonicalizer version 的完整二进制 envelope SHA-256。Room 只保存 Artifact metadata、不可变 revision、manifest 和 publication pointer，不保存逐 cue 正文。`TimelineRevisionReader` 一次解码当前 publication 为内存 `SubtitleTimeline`；播放进度解析不访问 Room、Artifact 文件或 SAF。

ASR window 与 translation batch 先发布 typed Artifact，再在同一 Room transaction 中登记引用和完成状态。Partial revision 引用连续完成前缀的 WorkUnit Artifact，Final source 使用单个 `FinalTimeline` Artifact；target 保存固定 source revision/hash 的 `TranslationDelta`。启动恢复只重跑没有已提交 Artifact 的单元。启动审计将缺失、损坏或未知版本引用标记为 `MissingArtifact` 或 `Storage / NeedsAttention`，不回读旧 URI，也不静默重发付费请求。不可达 Artifact 与未注册文件使用 24 小时宽限的两阶段 GC；当前 publication、target source pin 和可恢复任务资产是 GC root。

当前没有导出功能；内部 codec 与 revision reader 保留明确的 exporter 边界，但本轮不增加导出 UI。

核心新接口包括 `MainTab`、`ContentSort`、`ContentFolderSummary`、`SyncTextConfig` 及对应 Repository 查询/写入。Home projection、HomeSection、PlaylistSummary、命名 Playlist CRUD、Home shortcut 和 UI-facing Scheme/Task actions 不得成为新 UI 依赖。

## 9. 验收

自动化覆盖：虚拟文件夹单归属、删除回根目录、搜索排序、移出/恢复、Queue 唯一与重排、外部打开不收录、单套配置校验、生成状态归并；四种字幕格式 canonicalization、稳定 cue ID、版本化 binary codec、partial/final assembly、translation source pin、Artifact 审计与 24 小时 GC；Room 首次创建与 v5→v6 破坏性重建；Settings DataStore 与用户媒体不受影响。

Compose UI 覆盖四 Tab、中央 Import Hub、Mini Player、空态、文件夹导航、长按菜单、Queue、配置校验和 Back Stack。默认验证命令：

```text
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat assembleDebug
```

有设备后补充 `connectedDebugAndroidTest`，并在 360x800、412x915、较大字体、长标题/双语字幕下检查遮挡、溢出、触控区、Glow 纯黑和亮度。真机还需覆盖单文件、SAF Folder、设备扫描、外部打开、后台播放、进程恢复、同步文字生成、Glow 沉浸与 Sleep Timer。
