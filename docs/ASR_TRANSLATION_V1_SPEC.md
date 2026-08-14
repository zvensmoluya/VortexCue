# VortexCue 同步文字、翻译与 ASR V1 规格

> 状态：M4 当前实施与验收契约
>
> 产品边界以 [`GUIDE.md`](GUIDE.md) 为准；本文细化同步文字领域的接口、持久化、网络、媒体和恢复行为。

## 1. 目标与边界

V1 建立独立的“同步文字”领域模块。已有可用文字时直接翻译；没有可用文字时，通过 OpenRouter ASR 生成原文，再按用户选择的方案翻译。任务可以排队、重排、暂停、恢复并跨进程续跑，成功结果直接进入 Player / Glow，不要求导入或导出 SRT。

```text
ExistingTrack / EmbeddedTrack ───────────────┐
                                             ├─ source cues ─ translation ─ paired result
AudioTrack ─ decode ─ OpenRouter ASR ────────┘                    │
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

`GenerationTaskSummary` 对 UI 公开队列位置、阶段、进度、网络等待、暂停、需处理和同方案阻塞状态。`SyncTextRepository` 负责提交、去重、重排、暂停、恢复、删除、换方案与观察状态；Compose 只消费 `StateFlow<SyncTextUiState>` 并发送 `SyncTextAction`。

`GeneratedTextResult` 保存同一时间轴上的 source / target 配对，不使用 primary / secondary 两条普通轨模拟翻译对照。`TimedTextLocator.GeneratedResult` 通过稳定 generated slot 接入内容目录；slot 以 `(contentId, targetLanguage)` 定位当前结果，因此多个目标语言可以共存，也不伪造 URI。

以下不变量必须由 Repository 与 Room transaction 保证：

- 同一内容和目标语言最多存在一个未终结任务；重复提交返回原任务；
- 重新生成期间旧结果继续可用；新结果及文字轨引用只在发布事务中原子替换；
- 完整 ASR 成功后立即发布原文，随后再翻译；翻译失败不撤销原文；
- 换方案创建新 attempt，复用已完成的原文 / ASR 成果，清空并重做翻译；
- 一个结果不得混用不同模型、policy 或 attempt 的输出。

## 3. 持久化与私有资产

Room schema 使用关系表表达 credential metadata、scheme、task、不可变 task snapshot、attempt、ASR window、translation batch、request attempt、generated slot / result、runner lease 和 queue control。不得重新引入聚合 JSON 快照或把完整 cue 正文存入 Room。

最终 source / target 分别保存为规范化 WebVTT：

- 每条 cue 都有稳定 ID；
- source 与 target 使用相同 ID 和时间范围；
- Room 保存关系、hash、状态、用量、provenance 和文件引用。

原始响应、检查点、临时 WAV 和诊断正文保存在应用私有文件。原始响应在任务终结或最后尝试后保留 7 天；暂停与需处理任务保留恢复检查点；最终结果长期保留上游真实 usage / cost 与 provenance。

API key 由 Android Keystore 中的 AES-GCM 主密钥加密，密文位于 `noBackupFilesDir`。备份规则排除 Room、WorkManager 数据库、生成文字、字幕缓存、任务工件和凭据，只允许少量显示偏好备份。

预发布阶段只支持当前 Room schema，不维护迁移链；不兼容 schema 可以重置应用拥有的关系与工件，但不得修改用户原始媒体。

## 4. 语言与翻译 policy

语言统一使用规范化 BCP-47。源语言默认自动检测并允许覆盖；目标语言允许搜索语义下的常用选择和自定义 tag。`zh` 显式归一为 `zh-CN`，`zh-Hant` 等 tag 不套用简中风格。

内置 policy 固定为：

| 资产 | 版本 | 来源 revision | SHA-256 |
| --- | --- | --- | --- |
| `system.v1` | 1 | `414fe08405ab4cfbb2759cbbfdb8744bde20acfc` | `bbf6c688ce3df8953fe876ade60a69c28e837234cb3061694bab1467f642891f` |
| `style.zh-CN.v1` | 1 | `414fe08405ab4cfbb2759cbbfdb8744bde20acfc` | `09de51ae2e3709c8e506a4d9a82c9ce3bc9b16a13d0f683a928aad3a2d91e07b` |

所有语言使用 `system.v1`；只有规范化后的 `zh-CN` 叠加 `style.zh-CN.v1`。无专用风格的语言自动使用通用 policy。任务 snapshot 保存 policy ID、版本、revision 和 hash；打包资产不匹配时整方案进入需处理状态。

翻译批次遵循 TransVortex 的只读上下文语义：

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

- 禁止跨源重定向，连接失败不交给 OkHttp 隐式重试；
- 不记录请求 / 响应正文和认证头；错误信息必须脱敏；
- 请求与响应使用 kotlinx.serialization 的结构化 JSON API；响应体上限 8 MiB；
- OpenRouter ASR 的 base64 由流式 `RequestBody` 写出，不构造完整 base64 字符串；
- 只显示上游真实返回的 usage / cost，不根据模型名估造价格。

每个任务单独保存计费网络许可。提交前显示预计线上字节数、实际 Endpoint、模型和费用提示；无许可时计费网络只让该项等待，不影响其他可运行任务。

## 6. ASR 与媒体

Whisper 是主线 profile，Grok 使用相同窗口策略并明确标记实验。固定 profile 参数为：

- 300 秒上传窗口；
- 相邻窗口 3 秒 overlap，上传区间与结果所有权区间分离；
- 单请求 base64 编码上限 24 MiB；
- 失败后按约 150 / 75 秒继续二分，最小所有权窗口 8 秒；
- ASR 最多并发 2，实际编码音频总在途不超过 32 MiB；
- 限流、超时、连接错误或 5xx 后，本次 runner 自动降为并发 1；
- PCM spool 最多保留两路在途和一路前视，暂停时删除未上传块。

解码使用平台 `MediaExtractor` / `MediaCodec`，再通过现有 Media3 1.10.1 音频处理器转为 16 kHz mono PCM16 WAV。音频和视频都走同一管线；立体声与多声道显式混为 mono。确认页只读取轻量音轨元数据，多个音轨时不设置默认项，必须由用户明确选择。

内嵌文字使用 `media3-extractor` 物化为 cue。格式或样本不支持时任务给出可解释的媒体错误并保留 ASR 路径，不静默制造空结果。

## 7. 后台执行与阻塞

API 34+ 使用一个覆盖整条队列的 UIDT Job；API 26-33 使用唯一前台 `CoroutineWorker`。两条路径通过 Room lease 保证全进程只有一个 runner，不复用 `PlaybackService`。

队列规则：

- 默认 FIFO；等待项可以重排；
- 单项暂停后 runner 继续下一项，另有持久化“暂停全部”；
- 媒体错误只阻塞该项；认证、scheme、model / protocol 或 policy 错误阻塞同方案任务；
- 其他方案继续运行；网络恢复后唤醒符合许可的等待任务；
- runner 与进程重建后从 window / batch / request attempt 检查点恢复。

UI 只通过可替换的 `AsrEngine`、`TranslationProtocolAdapter`、`CredentialStore`、`GeneratedTextAssetStore` 和 `SyncTextScheduler` 间接工作，不直接调用网络、Worker 或媒体解码器。

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

- 窗口完整所有权、overlap 去重、失败二分、并发字节预算；
- 队列 lease、暂停 / 恢复、全局暂停、同方案阻塞与原子结果替换；
- prompt hash、80 / 40 只读上下文、严格 ID、格式恢复、repair、通用语言与简中 policy；
- 四种协议的 URL、认证头、请求结构、响应解析、usage、错误分类、重试、跨源重定向和脱敏；
- Room 的不可变方案 snapshot、任务恢复、slot 替换、凭据引用约束、TTL prune；
- VTT 稳定 cue ID 往返，以及普通字幕无 ID 往返；
- 音频 / 视频、声道转换、多音轨选择、内嵌文字物化和失败回退。

合并前执行：

```text
gradlew.bat testDebugUnitTest
gradlew.bat lint
gradlew.bat assembleDebug
```

真机只做一次短主线：短媒体、Whisper、Responses、自动源语言、`zh-CN`、原文先发布、目标结果启用、纯译文 / 对照切换及重启恢复。

Grok、长媒体、重启中断、计费网络，以及 Anthropic / Chat / Google 的真实 Endpoint 进入后续人工矩阵，不阻塞首条 Whisper + Responses 主线。
