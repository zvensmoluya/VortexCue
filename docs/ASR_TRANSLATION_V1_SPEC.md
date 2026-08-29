# VortexCue 同步文字、翻译与 ASR V1 规格

> 状态：内核实施与验收契约；当前产品行为仍以 `GUIDE.md` 为准
>
> 产品边界以 [`GUIDE.md`](GUIDE.md) 为准；本文细化同步文字领域的接口、持久化、网络、媒体和恢复行为。

## 1. 目标与边界

V1 建立独立的“同步文字”领域模块。已有可用文字时直接翻译；没有可用文字时，通过 OpenRouter ASR 生成原文，再按用户选择的方案翻译。任务可以排队、重排、暂停、恢复并跨进程续跑，成功结果直接进入 Player / Glow，不要求导入或导出 SRT。

```text
ExistingTrack / EmbeddedTrack ───────────────────────────────┐
                                                             ├─ published source ─ translation ─ paired result
AudioTrack ─ decode ─ ASR ─ Source Preparation ─ final source┘                         │
                                                                                       └─ Player / Glow
```

V1 不包含：

- 翻译记忆、字幕编辑器、导出和自定义 prompt；
- 在线模型目录、自动 provider fallback 或自动批量生成；
- 本地 ASR、ffmpeg、账号、应用代付额度或代理转发；
- 为厂商增加协议之外的业务分支；
- 完整任务历史中心。完成任务从任务抽屉退出，只保留结果与 provenance。

## 2. 领域契约

### 2.1 方案与请求

`SyncTextScheme` 是一个命名完整方案，保存：

- Whisper 或实验 Grok ASR profile；
- ASR 凭据引用；
- 翻译协议、HTTPS Endpoint、Model 和翻译凭据引用；
- 默认目标语言与展示模式。

应用支持多个方案及一个默认方案。方案被编辑后只影响新任务；任务持有不可变 snapshot 和 attempt。更新同一凭据 ID 可以修复引用该凭据的旧任务。凭据可以标记为 ASR、翻译或共用；仍被方案或任务引用时禁止删除。

`GenerationRequest` 的输入只有：

- `ExistingTrack(trackId)`：现有外挂、私有副本或已生成结果的原文；
- `EmbeddedTrack(trackId, embeddedTrackId)`：从容器物化的内嵌文字；
- `AudioTrack(audioTrackId)`：从媒体音轨生成原文，多音轨时必须显式选择。

输出只有：

- `SourceOnly`；
- `Translated(targetLanguage, presentationMode)`。

`TextPresentationMode` 只有 `TargetOnly` 与 `SourceTarget`。`SourceTarget` 中目标译文在上，原文在下且视觉更弱。

### 2.2 队列与结果

`GenerationTaskSummary` 对 UI 公开队列位置、阶段、已提交进度、网络等待、暂停、需处理、同方案阻塞，以及从持久化运行事实与当前 work unit 投影出的活动状态。`SyncTextRepository` 负责提交、去重、重排、暂停、恢复、删除、换方案与观察状态；Compose 只消费 `StateFlow<SyncTextUiState>` 并发送 `SyncTextAction`。`generation_runtime_status` 与提交进度相互独立：前者可以说明提取、等待模型、接收正文、校验或重试，后者仍只随已登记 Artifact 的完成单元单调推进。

`GeneratedTextResult` 保存同一时间轴上的 source / target 配对，不使用 primary / secondary 两条普通轨模拟翻译对照。`TimedTextLocator.GeneratedResult` 通过稳定 generated slot 接入内容目录；slot 以 `(contentId, targetLanguage)` 定位当前结果，因此多个目标语言可以共存，也不伪造 URI。

以下不变量必须由 Repository 与 Room transaction 保证：

- 同一内容和目标语言最多存在一个未终结任务；重复提交返回原任务；
- 重新生成期间旧结果继续可用；新结果及文字轨引用只在发布事务中原子替换；
- ASR 连续完成前缀只发布 Partial；完整 ASR 经过 Source Preparation 后才发布 Final source，随后再翻译；翻译失败不撤销正式原文；
- 换方案创建新 attempt，复用已完成的原文 / ASR 成果，清空并重做翻译；
- 一个结果不得混用不同模型、policy 或 attempt 的输出。

## 3. 持久化、Revision 与私有资产

Room v15 使用关系表表达 credential metadata、scheme、task、不可变 task snapshot、attempt、ASR window、source preparation checkpoint、translation batch、request attempt、generated slot / result、runner lease、queue control、diagnostic event、Artifact metadata、Timeline revision 和 revision-artifact manifest。任务表额外保存可变的计费网络许可和当前网络等待原因，不通过改写 immutable snapshot 表达运行期授权。不得重新引入聚合 JSON 快照或把完整 cue 正文存入 Room。

正文统一保存到 `noBackupFilesDir/text_artifacts/<kind>/<prefix>/<artifactId>.bin`。Artifact ID 是完整确定性 envelope 的 SHA-256；envelope 包含 magic、schema / codec / canonicalizer version、kind 和长度前缀 payload。decoder 必须拒绝未知版本、损坏、截断和 metadata/hash 不一致。SRT / VTT / ASS / LRC 仅是导入边界格式，不再作为内部 checkpoint 或 result 协议。

- ASR window 发布 `AsrWorkUnitOutput`，Source Preparation 发布 `SourcePreparation`，translation batch 发布 `TranslationWorkUnitOutput`；Artifact 文件发布成功后，Room transaction 才能登记引用和完成状态；
- Partial source revision 通过 immutable manifest 引用连续完成前缀，不重复写累计 VTT；完整 ASR 的稀疏 patch 与冻结 `SourceContext` 写入 preparation checkpoint，应用 patch 后的 Final source 由 `TimelineAssembler` 发布单个 `FinalTimeline`；
- target 保存 `TranslationDelta`，并固定 source revision ID/hash；source 与 target 使用相同 cue ID 和时间范围，不得跨 source revision、attempt 或 policy 混合；
- `GeneratedTextResult` 保存 source / target revision，slot current result、generated track、结果和任务状态在 publication transaction 中原子切换；
- 播放只通过 `TimelineRevisionReader` 顺序解码 publication 并在内存中按位置解析。

原始响应、临时 WAV / PCM 和诊断正文使用独立私有目录，不进入 Timeline Artifact GC。原始响应按 request attempt 的现有保留期限清理；暂停与需处理任务通过 Room 引用保留 WorkUnit Artifact；最终结果保留上游真实 usage / cost 与 provenance。恢复只重新执行没有已提交 Artifact 的 WorkUnit，损坏引用进入 `Storage / NeedsAttention`，不静默重复 provider 请求。

Artifact GC 以 Room 可达图为准：当前 Track / slot publication、target 固定的 source revision，以及 active / paused / needs-attention 任务恢复资产为 root。不可达 metadata 先记录 `gcCandidateSinceMs`，持续不可达满 24 小时后才删除；未注册文件按文件年龄使用同一宽限。启动审计同时处理 missing、orphan、未知 codec/schema 和损坏引用。

API key 由 Android Keystore 中的 AES-GCM 主密钥加密，密文位于 `noBackupFilesDir`。备份规则排除 Room、WorkManager 数据库、生成文字、字幕缓存、任务工件和凭据，只允许少量显示偏好备份。

预发布阶段只支持当前 Room schema，不维护迁移链；不兼容 schema 可以重置应用拥有的关系与工件，但不得修改用户原始媒体。

## 4. 语言与翻译 policy

语言统一使用规范化 BCP-47。源语言默认自动检测并允许覆盖；目标语言允许搜索语义下的常用选择和自定义 tag。`zh` 显式归一为 `zh-CN`，`zh-Hant` 等 tag 不套用简中风格。

内置 policy 固定为：

| 资产 | 版本 | 来源 revision | SHA-256 |
| --- | --- | --- | --- |
| `source-preparation.system.v1` | 1 | `vortexcue-source-preparation-v1` | `dcf4ee6bd866c35ea5c261fd33ba7b9f1ac353b8d64bea190ebb97ad0ce702e0` |
| `system.v2` | 2 | `vortexcue-source-preparation-v1` | `0caef8f6f9f2a28e951196c67b8852c4307c9232596786549e7ea702e5352029` |
| `style.zh-CN.v2` | 2 | `vortexcue-source-preparation-v1` | `a52812c8a42e6411e9acdd57bce85a38b0a158c09724f01beed57ee418bd81ae` |

所有语言使用 `system.v2`；只有规范化后的 `zh-CN` 叠加 `style.zh-CN.v2`。无专用风格的语言自动使用通用 policy。ASR 来源 attempt 另行冻结 preparation policy version 与 prompt hash；打包资产不匹配时任务进入需处理状态。

Source Preparation 对完整 ASR 只允许稀疏 `DROP` / `REPLACE`，不增加、拆分、合并或改变时间；专属扫描固定关闭思考。没有配置文本模型或扫描失败时以确定性事件标签清理降级发布。扫描附带的目标语言无关 `SourceContext` 只保存紧凑风格、实体表面形式、别名与称呼功能，不维护跨任务翻译记忆。

翻译批次只消费 final Published Source 与同一份只读 `SourceContext`：

- 初始批次最多 120 cue，估算输入上限约 12k token；
- 每批带原文前 80 条、后 40 条只读上下文；
- 批次之间不依赖前一批译文；
- 最多并发 4；容量错误二分，直到单 cue；
- 网络失败最多 3 次；格式恢复最多 1 次；少量非法 ID repair 最多 1 次。

响应必须严格拒绝缺失、重复、未知、context ID、空译文、解释性正文和极端异常输出。成功结果按稳定 cue ID 合并，不按返回顺序猜测。

## 5. 翻译协议与网络

四种协议均为完整适配器，顺序为：

1. OpenAI Responses；
2. Anthropic Messages；
3. OpenAI Chat Completions；
4. Google `generateContent`。

Endpoint 可以使用界面模板或自定义 HTTPS 地址。Model 只允许手填或复用本地历史值，不拉取远端目录，不根据错误自动切换协议、模型或方案。

网络层使用单例 OkHttp：

- 默认发送 `VortexCue/<version> (Android)` 作为 `User-Agent`，不包含设备品牌、型号或 Android 小版本；方案可以为自定义翻译 Endpoint 保存可选覆盖值，值只允许最多 256 个可打印 ASCII 字符，并复制进 attempt snapshot；
- 仅当目标主机严格等于 `openrouter.ai` 时发送固定 `HTTP-Referer` 与 `X-OpenRouter-Title` 项目归属头，不把这些头发送给其他自定义 Endpoint；
- 禁止跨源重定向，连接失败不交给 OkHttp 隐式重试；
- 不记录请求 / 响应正文和认证头；错误信息必须脱敏；
- 请求与响应使用 kotlinx.serialization 的结构化 JSON API；响应体上限 8 MiB；
- LLM 翻译默认使用各协议的 SSE 流式响应；网络层只保留一个全局 `streamResponses` 开关以便兼容不支持流式的自定义 Endpoint。流事件必须区分 reasoning 与 output：前者只表示模型仍在处理，后者才允许按实际出现的预期 cue ID 更新 `N/M`。活动事实写入独立的 Room runtime projection，以便切屏或进程恢复后继续解释当前阶段；reasoning 与 output 的真实 delta 还会累计任务级、单调且持久化的近似 `≈ tokens` 活动量。该数值只用于暴露流仍有输出，不是完成比例或计费依据，也不替代上游最终 usage；重试不得归零。流式正文仍须累计完整后执行严格 cue ID 校验，不发布半成品结果；
- Chat Completions / Responses / Anthropic 分别解析各自 SSE 事件；Google 使用 `streamGenerateContent`。WebSocket 不作为 V1 普通批次翻译传输协议；
- 流式请求分别限制首次有效输出等待时间和相邻有效 delta 的空闲时间；收到有效 delta 后不得继续套用固定 120 秒总时长限制。非流式兼容路径仍使用有限的整体请求超时；
- OpenRouter ASR 的 base64 由流式 `RequestBody` 写出，不构造完整 base64 字符串；
- 只显示上游真实返回的 usage / cost，不根据模型名估造价格。

每个任务单独保存计费网络许可。任务默认只使用非计费网络；检测到计费网络时只让该项进入明确的许可等待状态，不影响其他可运行任务。用户允许本次任务后持久化许可、清除等待原因并立即重新调度。

## 6. ASR 与媒体

Whisper 是主线 profile，Grok 使用相同窗口策略并明确标记实验。固定 profile 参数为：

- 300 秒上传窗口；
- 相邻窗口 3 秒 overlap，上传区间与结果所有权区间分离；
- 单请求 base64 编码上限 24 MiB；
- 容量失败只允许把原始窗口二分一次为约 150 秒；子窗口不递归切分，再次容量失败按模型或供应商不兼容终止；
- 限流、超时、连接错误或 5xx 只重试同一窗口，不触发切分；
- ASR 最多并发 2，实际编码音频总在途不超过 32 MiB；
- 限流、超时、连接错误或 5xx 后，本次 runner 自动降为并发 1；
- PCM spool 最多保留两路在途和一路前视，暂停时删除未上传块。

解码使用平台 `MediaExtractor` / `MediaCodec`，再通过现有 Media3 1.10.1 音频处理器转为 16 kHz mono PCM16 WAV。音频和视频都走同一管线；立体声与多声道显式混为 mono。确认页只读取轻量音轨元数据，多个音轨时不设置默认项，必须由用户明确选择。

内嵌文字使用 `media3-extractor` 物化为 cue。格式或样本不支持时任务给出可解释的媒体错误并保留 ASR 路径，不静默制造空结果。

代码对带明确括号或星号的声音事件标签执行确定性 `DROP`；版本化 Whisper 污染短语只生成 `SUSPICIOUS_PATTERN` flag，由全量扫描结合位置和上下文裁决。翻译阶段不得再次删除或修改 source cue。

## 7. 后台执行与阻塞

API 34+ 使用一个覆盖整条队列的 UIDT Job；API 26-33 使用唯一前台 `CoroutineWorker`。两条路径通过 Room lease 保证全进程只有一个 runner，不复用 `PlaybackService`。UIDT 提交必须检查 `JobScheduler` 返回值；后台条件不允许提交时保留进程内 deferred 状态，并在 Activity 真正进入前台后重试。

Debug 诊断以本地结构化事件记录调度、runner、任务、work-unit 和发布边界。Request attempt 必须关联具体 window / batch，分别保存操作序号、网络重试序号、真实起止时间和 monotonic duration。诊断默认不得写入 Key、认证头、请求 prompt、媒体路径或 cue 正文；原始响应仍按 7 天 TTL 独立保存，只有显式采集时才进入诊断包。

队列规则：

- 默认 FIFO；等待项可以重排；
- 单项暂停后 runner 继续下一项，另有持久化“暂停全部”；
- 媒体错误只阻塞该项；认证、scheme、model / protocol 或 policy 错误阻塞同方案任务；
- 其他方案继续运行；网络恢复后唤醒符合许可的等待任务；
- runner 与进程重建后从 window / batch / request attempt 检查点恢复。

UI 只通过可替换的 `AsrEngine`、`TranslationProtocolAdapter`、`CredentialStore`、`TextArtifactStore`、`RawResponseStore` 和 `SyncTextScheduler` 间接工作，不直接调用网络、Worker 或媒体解码器。

## 8. Player / Glow 行为

- 无现有文字选择时，成功结果自动成为 primary；已有手动选择时不覆盖用户决定；
- 生成结果可从现有文字轨入口选择；播放中发生结果替换时，到下一 cue 边界再生效；
- `TargetOnly` 只显示目标译文；`SourceTarget` 显示目标译文及下方较弱原文；
- 配对结果激活时占用完整文字表面并暂时隐藏独立 secondary；切走后恢复原 secondary 选择；
- 结果详情显示语言、模型、policy、hash、真实 usage / cost 与 provenance；不建立历史中心。

V1 UI 只增加方案编辑器、生成确认页、Home / Player 共用任务抽屉、结果详情和展示切换。继续复用现有文字轨入口，不在本轮重做整体视觉。

## 9. 固定依赖

- WorkManager `2.11.2`；
- OkHttp `5.3.0`；
- kotlinx-serialization-json `1.11.0`；
- Media3 保持 `1.10.1`，只补 `media3-extractor`。

## 10. 验收

自动化验证至少覆盖：

- 窗口完整所有权、overlap 去重、原始窗口单次二分、子窗口容量失败终止、并发字节预算；
- 队列 lease、暂停 / 恢复、全局暂停、计费网络授权、同方案阻塞与原子结果替换；
- preparation prompt hash、稀疏 patch、事件标签与污染 flag、SourceContext、80 / 40 只读上下文、严格 ID、格式恢复、repair、通用语言与简中 policy；
- 四种协议的 URL、认证头、请求结构、响应解析、usage、错误分类、重试、跨源重定向和脱敏；
- Room 的不可变方案 snapshot、任务恢复、slot 替换、凭据引用约束、TTL prune；
- canonical cue 稳定 ID、binary codec 确定性、版本拒绝、损坏与截断检测；
- WorkUnit Artifact 恢复、preparation checkpoint、partial/final assembler、translation delta 对齐、source pin、publication 原子替换与两阶段 GC；
- 音频 / 视频、声道转换、多音轨选择、内嵌文字物化和失败回退。

合并前执行：

```text
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat assembleDebug
```

真机只做一次短主线：短媒体、Whisper、Responses、自动源语言、`zh-CN`、原文先发布、目标结果启用、纯译文 / 对照切换及重启恢复。

Grok、长媒体、重启中断、计费网络，以及 Anthropic / Chat / Google 的真实 Endpoint 进入后续人工矩阵，不阻塞首条 Whisper + Responses 主线。
