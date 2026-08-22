# VortexCue 产品与实施指南

> 本文是当前 UI、信息架构和实现边界的最高产品规格。
> `docs/reference/UI_LAYOUT_DESIGN.md` 记录布局与交互细节；`docs/reference/布局原型.html` 仅保留结构参考，不代表视觉风格。
> 长期领域背景见 [`DOMAIN_CONTEXT.md`](DOMAIN_CONTEXT.md)。

VortexCue 是 Android-first、OLED-first、sleep-first 的本地内容播放器。核心价值是“内容继续，屏幕退场；需要视觉时只显示当前同步文字”。字幕不是播放前置条件，但在 Normal Player 与 Glow 中是最小、可读、可退出的视觉内容。

## 1. 应用壳层

一级页面固定为四个：

```text
歌单 | 播放列表 | [骨白荧荧 Logo：Import Hub] | 配置 | 设置
```

Logo 是动作，不是第五个页面；点击只打开 Import Hub，不改变当前 Tab。歌单页承担默认进入位置，但 UI 不使用“首页”命名，也不再出现 Home、今晚、发现或来源一级入口。

存在 Current Queue 时，Mini Player 跨四个 Tab 显示。Mini Player 是底栏上方的内缩 Raised Surface 卡片，只显示标题、有效时间/进度和播放/暂停；点击正文进入 Normal Player。冷启动不恢复 Glow，Glow 只能由当前运行期的 PlayerSurface 明确进入。

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

黄色不进入应用内 UI；Launcher 与 Splash 保留既有品牌资源。当前歌单不使用封面、拼贴或文件夹图标，最多 8dp 圆角。普通浏览页允许使用 Raised Surface 与较高文字对比度；极低发光只约束 Glow 等退场阶段。交互控件最小 48dp 触控区，图标按钮必须有 TalkBack 描述；拖动、长按等动作同时提供可访问的替代操作。

## 2. 歌单与全部内容

歌单是用户长期整理和重复进入的内容集合，与物理文件夹和 Source 完全独立。一个 `contentId` 可以加入多个歌单，但同一歌单内唯一；用户歌单和歌单成员均使用手动顺序。删除歌单或移除成员不删除原文件、不移出收录，也不改变已经生成的 Current Queue。

歌单页不显示页面大标题和搜索。顶部“最近听过”最多横向展示 6 个最近真正从中开始播放的非空用户歌单；卡片只显示歌单名和内容数量，点击只进入详情，不续播。下面“全部歌单”固定以虚拟入口“全部内容”置顶，再按用户手动顺序展示歌单。当前版本仅用排版和 Raised Surface 区分歌单，不提供图标或图片封面。

“全部内容”包含全部已收录、未忽略的媒体，按最近加入优先排列，不写入 `playlist` 表，也不进入“最近听过”。从“全部内容”点击媒体只以单项替换 Current Queue。歌单详情提供“播放全部”；点击具体内容时用完整歌单顺序替换 Current Queue，并从点击项开始。只有替换成功后才更新歌单最近播放时间。

内容长按 Bottom Sheet 提供：

- 立即播放；
- 加入播放列表；
- 加入歌单；
- 从当前歌单移除（仅歌单详情）；
- 同步文字；
- 移出内容。

“移出内容”只写入内部忽略状态，不删除原始媒体、不打断当前播放；来源刷新不会再次推荐。来源详情的次级入口可以恢复忽略项。

## 3. Import Hub 与来源

Import Hub 提供：选择单文件、从文件夹查找、扫描设备媒体、已授权来源列表。

- Logo 选择单文件：加入“全部内容”并立即播放；
- 外部 `ACTION_VIEW`：只进入当前 Queue，不自动收录；Player 提供“加入内容”；
- 文件夹扫描默认勾选全部可播放音视频；
- 全设备扫描默认只勾选音频；
- 批量导入默认只加入“全部内容”，也可当场选择或新建一个初始歌单；导入后可再加入多个歌单；
- 来源重扫只展示本次新增项；已移出/忽略项在来源详情中恢复；
- 来源失效、重新授权、取消扫描和媒体重新定位就地反馈，不建立异常管理中心。

来源是扫描授权与刷新边界，不是歌单。扫描不得复制物理目录结构，也不得覆盖用户的忽略或歌单决定。

## 4. 播放列表

“播放列表”页面只有一个 Current Queue，数据与 Player 的队列对齐。支持选择播放、拖动重排、移除、清空和当前项标识。队列内同一 `contentId` 唯一，重复加入不追加，只提示“已在播放列表”。

队列连续播放、MediaSession、后台播放、耳机/蓝牙控制和 Sleep Timer 是固定行为，不在设置页暴露开关。

## 5. 配置

配置页只有一套完整的 `SyncTextConfig`，顶部切换“翻译模型配置”和“语音识别”。不向用户展示 Scheme、Attempt、Task Queue、Token、Hash 或技术结果详情。内部仍使用稳定配置 ID 关联后台审计与 attempt snapshot。

翻译配置提供协议选择、可编辑的 Base URL、自动发现的模型选择、API Key、目标语言和展示模式。官方示例只展示服务根地址或 `/v1` 地址；用户不需要填写 `/responses`、`/chat/completions` 或其他资源路径，应用根据协议在内部补全。Base URL 或协议变化后静默尝试拉取可用模型，不提供刷新按钮；服务不提供模型目录时仍允许直接填写模型。用户可以发送一个低额度测试请求，确认所选协议、地址、模型和 Key 真正可用。Key 由 Android Keystore 加密保存，Endpoint 必须经过 HTTPS 与主机校验。

ASR 配置先选择提供商，再选择该提供商的模型；OpenRouter 提供 Whisper large-v3 与 Grok STT，并保留可编辑的 Base URL。本项目对 OpenAI 官方提供商当前只开放 Whisper-1，固定使用官方 `/v1/audio/transcriptions` multipart 上传协议，不复用 OpenRouter 的 JSON 音频协议。GPT Transcribe 等只能返回纯文本、不能返回可用时间轴的模型不进入可选列表；未来只有在官方提供时间轴后才重新评估。每个提供商由独立适配器负责请求与响应；用户可以发送一个极短的测试音频确认连接，测试不会创建同步文字结果。

生成自动使用当前配置：已有文字优先翻译，没有文字才执行 ASR；只有多音轨等真实歧义才请求选择。单个 ASR 窗口允许没有语音并以空时间轴继续，整个媒体都没有可用 cue 时明确提示“未检测到可转写语音”，不归因于提供商或网络故障。任务默认只使用非计费网络；等待计费网络许可时就地说明原因，并允许用户仅为本次任务授权。临时网络错误使用共享退避与抖动；设备没有 validated network 时进入可自动解除的等待，而不是要求用户手动重试。同步文字页提供取消与重试，不出现全局任务抽屉。

## 6. 设置

设置只负责 Glow 窗口/文字/控制亮度、字号、垂直位置、预设与首次校准，以及权限状态和关于。系统栏隐藏、队列连续播放等固定行为不做可选项。

Glow 为纯黑、安全 Insets、独立亮度；双击分区、长按临时控制、5 秒退场保持现有行为。Glow 返回 Normal Player，再返回原一级页面。

## 7. Player 与同步文字

Normal Player 的主体固定为“上一句弱化 / 当前句主视觉 / 下一句弱化”，不显示假封面。顶栏只保留返回、标题和低频操作菜单；底部只常驻进度、上一项 / 后退 10 秒 / 播放 / 前进 10 秒 / 下一项、速度、Sleep Timer、播放列表和进入 Glow。随机与循环进入播放列表面板，校准不在播放器常驻，字幕时间偏移藏在同步文字面板。

播放器不常驻同步文字“管理”入口：没有文字时，字幕舞台只显示“生成同步文字”；已有可翻译原文时，只在舞台边缘显示翻译图标；动作启动后原位切换为不确定态环形进度，任务入队后切换为带百分比的确定态环形进度。同步文字面板只负责主文字、第二文字、手动文件、恢复自动匹配、生成结果展示和低频时间偏移。启动成功不再弹出确认对话框。手动导入会读取、解析并规范化 SRT / VTT / ASS / LRC，然后发布为应用自有的 immutable revision；成功后的文字轨不再依赖原 URI 或 SAF 权限。Folder 扫描到的外挂字幕只登记为候选，首次自动命中、用户点选或生成流程采用时才物化；媒体播放不等待候选导入。内嵌文字仍由 Media3 实时播放，仅在生成任务把它作为固定输入时物化 source revision。生成结果完成后自动成为可选文字轨。

## 8. 数据与迁移

Room 当前 schema 为 v9；v7→v8 通过自动迁移保留任务、结果和手测诊断数据，v8→v9 手动把旧虚拟文件夹和单归属成员关系迁移为同名多对多歌单，不从单内容历史推断歌单最近播放。v6 及其他不兼容版本仍允许破坏性重建。重建清理应用私有旧字幕缓存、旧 result/checkpoint VTT、孤立凭据文件和旧的 Home shortcut 数据；保留 Settings、用户原始媒体和 SAF 外部字幕文件。数据库不再把 `lastSurface` 当作启动入口；`PlayerSurface` 只管理运行期 Normal/Calibration/Glow 层级。

导入与生成文字统一使用 `CanonicalTimeline`、`TimelineRevision` 和 content-addressed `TextArtifactStore`。Artifact 位于 `noBackupFilesDir/text_artifacts/<kind>/<prefix>/<id>.bin`，ID 是包含 kind、schema、codec 与 canonicalizer version 的完整二进制 envelope SHA-256。Room 只保存 Artifact metadata、不可变 revision、manifest 和 publication pointer，不保存逐 cue 正文。`TimelineRevisionReader` 一次解码当前 publication 为内存 `SubtitleTimeline`；播放进度解析不访问 Room、Artifact 文件或 SAF。

ASR window 与 translation batch 由固定数量的 worker 按序领取，先发布 typed Artifact，再在同一 Room transaction 中登记引用和完成状态；任务进度只按真正提交的完成单元推进，并保持单调。Partial revision 引用连续完成前缀的 WorkUnit Artifact，Final source 使用单个 `FinalTimeline` Artifact；target 保存固定 source revision/hash 的 `TranslationDelta`。翻译输出继续严格校验 cue ID，只在缺失字面量 `cue-` 前缀且补全后唯一命中预期 ID 时本地正规化，不发起额外模型请求。启动恢复只重跑没有已提交 Artifact 的单元。启动审计将缺失、损坏或未知版本引用标记为 `MissingArtifact` 或 `Storage / NeedsAttention`，不回读旧 URI，也不静默重发付费请求。不可达 Artifact 与未注册文件使用 24 小时宽限的两阶段 GC；当前 publication、target source pin 和可恢复任务资产是 GC root。

当前没有导出功能；内部 codec 与 revision reader 保留明确的 exporter 边界，但本轮不增加导出 UI。

核心 UI 接口包括 `MainTab.Playlists`、`PlaylistSummary`、多对多 `PlaylistItem`、`SyncTextConfig` 及对应 Repository 查询/写入。Home projection、HomeSection、物理 Folder projection、Home shortcut 和 UI-facing Scheme/Task actions 不得成为新 UI 依赖。

## 9. 验收

自动化覆盖：歌单多归属与去重、歌单及成员重排、删除歌单保留内容、最近歌单上限、移出/恢复、Queue 唯一与重排、外部打开不收录、单套配置校验、生成状态归并；四种字幕格式 canonicalization、稳定 cue ID、版本化 binary codec、partial/final assembly、translation source pin、Artifact 审计与 24 小时 GC；Room 首次创建、v7→v8、v8→v9 保留迁移与 v6→v9 破坏性重建；Settings DataStore 与用户媒体不受影响。

Compose UI 覆盖四 Tab、歌单总览与详情、最近歌单、全部内容、多选成员、重排模式、中央 Import Hub、Mini Player、长按菜单、Queue、配置校验和 Back Stack。默认验证命令：

```text
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat assembleDebug
```

有设备后补充 `connectedDebugAndroidTest`，并在 360x800、412x915、较大字体、长标题/双语字幕下检查遮挡、溢出、触控区、Glow 纯黑和亮度。真机还需覆盖单文件、SAF Folder、设备扫描、外部打开、后台播放、进程恢复、同步文字生成、Glow 沉浸与 Sleep Timer。
