# 生成文字的 Artifact / Revision 存储架构决策摘要（2026-08-20）

> 状态：设计已确定，待实施；不改变当前实现契约。
>
> 本记录定义应用自生成 ASR / translation 文字的长期存储目标。当前代码仍按 `ASR_TRANSLATION_V1_SPEC.md` 使用应用私有 VTT 工件；在完整迁移实施前，旧实现继续有效。
>
> 统一的导入字幕拥有权、规范化规则、Room / Artifact 边界、恢复协议和 GC 细节见 [`DESIGN_2026-08-20_GENERATED_TEXT_STORAGE_DETAILED.md`](DESIGN_2026-08-20_GENERATED_TEXT_STORAGE_DETAILED.md)。本记录保留决策摘要和取舍，不重复详细设计。
>
> 本记录不替代 `GUIDE.md` 或当前 M4 / V1 规格。真正实施后，必须把已落地的行为和新的迁移约束同步回当前规格。

## 1. 决策摘要

生成文字不迁移为“每个 CUE 一行的 Room 表”，也不把 Room 当作通用正文容器。用户导入的外部字幕同样不保留为运行时 URI 依赖；导入完成后先转换为 VortexCue 自有的 canonical Timeline Artifact。两条入口最终共享同一套内部时间轴读取和发布模型。

长期目标采用三层模型：

```text
Room
  = 控制面与关系型持久事实

Immutable Artifact Store
  = 不可变、可校验、可恢复的生成正文

Memory
  = 播放用 SubtitleTimeline 与运行时索引
```

Room 负责任务编排、尝试快照、工作单元状态、Artifact 引用、Timeline revision 和发布指针。Artifact Store 保存结构化的 ASR / translation 输出以及最终时间轴。播放开始后只物化当前已发布 revision，不按播放时间查询数据库。

对于当前“一次生成、顺序读取、整体加载、很少修改”的时间轴，最终 revision 默认使用**一个不可变的顺序可读 Artifact**。只有真实出现随机访问、流式加载或超大正文需求时，才增加分块；不预先引入 `TimelineChunk` 或逐 CUE 表。

## 2. 解决的问题

当前链路同时依赖 Room 和业务文件：

```text
Provider response
  -> Structured segments / cues
  -> VTT encode
  -> private file
  -> VTT parse
  -> SubtitleTimeline
```

当前实现有意使用原子文件、hash 和启动清理，并非错误。但它仍然需要维护：

```text
Room state
  +
checkpoint / result files
```

两套持久化状态之间的引用关系。ASR 连续窗口推进时，当前 Runner 还会反复生成累计 prefix 结果，造成重复合并、编码和写入。

本设计解决的不是“Room 一定比文件快”，而是：

- 去掉内部 `structured data -> VTT -> structured data` 往返；
- 让 WorkUnit 成功输出成为可校验的不可变恢复事实；
- 让用户可见结果具有明确的 revision 和 publication 边界；
- 让失败、重试、split、进程终止和清理都有稳定的引用关系；
- 让播放器只依赖一次顺序加载和内存中的时间轴。

## 3. 术语与边界

### 3.1 Cue

`Cue` 是带时间范围的显示片段：

```text
startMs
endMs
text
stable identity
```

它可能是一句话、半句话、若干词或 ASR 合并后的展示片段。Cue 是领域对象，不是数据库行，也不是任务恢复的工作单元。

### 3.2 WorkUnit

`WorkUnit` 是可独立执行、重试、恢复或拆分的任务单元。当前主要包括：

- ASR window；
- translation batch。

WorkUnit 的输出仍可能需要 normalization、overlap 处理、去重和 merge，不应直接等同于最终用户可见的 Cue。

### 3.3 Checkpoint Artifact

一个已经成功完成的 WorkUnit 产生一个不可变的结构化输出 Artifact。它是恢复事实，不是直接暴露给播放器的最终时间轴。

### 3.4 TimelineRevision

`TimelineRevision` 是某一 source 或 target 时间轴的不可变版本。它记录了输入 revision、组装策略、编码版本、完整性和生成 provenance，并由 slot 的当前指针决定是否对用户可见。

### 3.5 Publication

Publication 是把一个完整或合法的部分 revision 设为当前结果的明确边界。播放器只能读取已发布 revision，不能读取进行中的 WorkUnit 或未提交的 Artifact。

## 4. 持久化分层

### 4.1 Room：控制面

Room 保存可变的关系型 durable facts：

```text
GenerationTask
GenerationAttempt
WorkUnit
Artifact metadata / references
TimelineRevision
GeneratedTextResult / Slot.currentRevisionId
Runner lease and queue control
```

Room 不保存每条 Cue 的正文，也不重新引入完整聚合 JSON 快照。

### 4.2 Immutable Artifact Store：数据面

Artifact Store 使用应用私有目录保存不可变的结构化 payload。Artifact 采用 content-addressed identity，并按类型和 codec 版本解码。

建议的 Artifact 类型至少包括：

```text
AsrWorkUnitOutput
TranslationWorkUnitOutput
PartialTimeline
FinalTimeline
```

文件路径只由 Artifact identity 派生，不由 task、window 或 batch 的业务名称决定。当前目录仍应位于应用私有、不会被用户原始媒体覆盖的范围；现有 `noBackupFilesDir` 边界和重建清理语义继续保留。

### 4.3 Memory：运行时

播放开始或当前 revision 改变时：

```text
Room current revision
  -> Artifact sequential decode
  -> SubtitleTimeline
  -> in-memory resolve(position)
```

播放期间不按时间进度访问 Room 或 Artifact Store。可以按 revision identity 和完整性 hash 缓存内存对象；缓存丢失后可重新物化。

## 5. Artifact 身份与编码

Artifact 必须是不可变、可校验、可幂等写入的内容对象。Artifact identity 不应只依赖裸 payload 字节，而应由规范化 envelope 决定，至少涵盖：

```text
artifact kind
codec version
schema version
canonical payload
```

Room 中的 Artifact metadata 至少表达：

- `artifactId`；
- `kind`；
- `codecVersion` / `schemaVersion`；
- 字节大小；
- 完整性 hash；
- 创建时间；
- 所需的 source revision 或 provenance 引用。

时间轴 payload 应是内部稳定的结构化编码，而不是 VTT 文本。编码格式可以在实现阶段选择紧凑二进制或其他明确版本的序列格式，但 decoder 必须能够拒绝未知版本和不完整 payload。

VTT / SRT / ASS / LRC 的职责固定为：

```text
Import:  exchange format -> domain timeline
Export:  domain timeline -> exchange format
```

它们不再作为内部 checkpoint、恢复或 generated result 的事实协议。

## 6. WorkUnit 编排

任务生命周期保持专用、有限的结构，不抽象为通用 workflow engine：

```text
GenerationTask
  -> GenerationAttempt (冻结配置与输入版本)
  -> WorkUnit plan
  -> WorkUnit output Artifact
  -> Timeline assembly
  -> publication
```

### 6.1 Attempt 快照

Attempt 必须冻结本次执行所需的：

- provider、model 和 endpoint；
- source / target language；
- prompt / policy identity、版本和 hash；
- segmentation / planner / assembly policy；
- 输入 source revision 或媒体快照引用；
- 其他影响输出解释的处理参数。

配置变化创建新的 attempt；同一 attempt 内的网络重试不改变这些事实。

### 6.2 WorkUnit 输出

网络请求、音频提取和 provider 解析在事务外执行。成功后必须按以下顺序提交：

```text
produce structured payload
  -> write temp artifact
  -> flush and atomically publish content-addressed file
  -> Room transaction:
       register artifact metadata
       reference artifact from WorkUnit
       mark WorkUnit completed
       record count / range / usage / hash
```

Room 与文件系统不共享事务，因此不宣称跨存储原子性。提交协议只保证一个关键不变量：

```text
Room has a durable artifact reference
  => the referenced artifact was published before that transaction
```

如果进程在文件发布后、Room transaction 前终止，结果是 orphan Artifact；它可以在宽限期后由 GC 删除。不能把“Room 已完成但引用 Artifact 缺失”当作普通 orphan；这种情况必须报告为存储损坏或任务局部失败。

相同 canonical payload 的并发写入必须幂等。多个引用可以共享一个 Artifact，删除引用时不能直接删除文件。

### 6.3 Retry 与 split

- WorkUnit 使用稳定的逻辑键和 attempt 归属，重复提交不会生成两个成功输出；
- parent split 和 children 创建在同一 Room transaction 中完成；
- parent 进入 `Split` 后不再作为最终输入；
- 已完成的 child Artifact 不因其他 child 失败而被覆盖；
- 进程终止后，已提交 Artifact 的 WorkUnit 直接恢复，只有未提交输出的单元重新执行。

外部 provider 请求本身可能因进程终止而重复发送；本地 Artifact 提交必须幂等，费用和网络重试仍按现有 attempt / request attempt 规则记录。

## 7. TimelineAssembler

`TimelineAssembler` 是纯领域组件，不依赖 Room、文件系统或 Android 生命周期：

```kotlin
assemble(
    workUnitOutputs,
    assemblyPolicy,
    assemblerVersion,
) -> Timeline
```

相同的输入 Artifact、顺序、policy 和 assembler version 必须产生相同的 Timeline。

Assembler 负责：

- window ownership 和 overlap 处理；
- split / parent 结果选择；
- 去重、排序和 merge；
- 最终 Cue identity 生成；
- source timeline 的完整性检查。

provider segmentation 不得被直接固化成用户可见的 Cue segmentation。现有 `AsrTimelineMerger` 和 `CueIdNormalizer` 是可复用的纯逻辑基础，但不应让 Runner 同时承担组装、持久化和发布职责。

## 8. Timeline Revision 与发布

一个 revision 至少记录：

- `revisionId`；
- content / slot / role；
- source revision reference（target 时必需）；
- Artifact reference 或 partial manifest；
- completeness：`Partial` / `Final`；
- cue count、covered range 和完整性 hash；
- assembler / codec version；
- generation provenance 和创建时间。

### 8.1 Final revision

最终 source 或 target 时间轴默认编码为一个不可变的 `FinalTimeline` Artifact。发布流程为：

```text
all required WorkUnits completed
  -> decode WorkUnit outputs
  -> TimelineAssembler
  -> write FinalTimeline Artifact
  -> Room transaction:
       create TimelineRevision
       switch Slot.currentRevisionId
       update GeneratedTextResult / track relation
       mark task completed
```

播放器只在 publication transaction 完成后观察到新结果。

### 8.2 Partial revision

Partial 是合法的已发布结果，但它不能暴露进行中的 WorkUnit。它可以采用：

- 引用连续已完成 WorkUnit Artifact 的不可变 manifest；或
- 在明确的里程碑生成的 PartialTimeline Artifact。

不应在每个 ASR window 完成时原地重写累计 prefix。Partial source 可以继续被播放和展示为“部分可用”，但 translation 仍然只接受 `Final` source revision。

当前仓库已经通过 `GeneratedResultCompleteness.Partial`、连续 prefix 恢复和失败/暂停时的“部分可用”状态表达这一契约；本设计保留它，而不是把所有可见结果强制收敛为 Final。

### 8.3 Source / target 关系

Target revision 必须固定一个不可变 source revision：

```text
TargetRevision
  -> sourceRevisionId + sourceRevisionHash
  -> target Artifact
```

当前翻译的 source 与 target 使用相同时间范围和稳定 Cue ID，因此 target payload 可以只保存 source Cue identity 与翻译文本，不重复保存 source 时间轴。不能只依赖 ordinal；ordinal 只在被固定的 source revision 内有效。

如果未来允许翻译重新分段，应增加新的 target payload schema，而不是让当前一一对应模型隐式失效。

## 9. 播放与读取

播放层只需要一个面向 revision 的 reader：

```text
loadPublishedRevision(slotId)
  -> resolve current revision
  -> open one final/partial artifact representation
  -> decode sequentially
  -> SubtitleTimeline
```

不得在播放热路径中：

- 查询单个 Cue；
- 读取 WorkUnit 状态；
- 读取 prefix 文件；
- 观察未发布的 checkpoint；
- 重新解析 VTT。

如果未来确实需要超大时间轴的流式或随机加载，再以真实数据和访问模式增加 chunk 或粗粒度时间索引。分块是后续存储优化，不是当前领域模型的前置要求。

## 10. Artifact 保留与 GC

Artifact 清理按 Room 引用图执行，而不是按文件名猜测：

```text
Slot / Result
  -> TimelineRevision
  -> Final / Partial Artifact

Active Task / Attempt / WorkUnit
  -> recovery Artifact

Translation Task
  -> pinned source revision / Artifact
```

只有同时满足以下条件的 Artifact 才可清理：

- 不再被当前或历史保留的 revision 引用；
- 不再被 active / paused / needs-attention task 恢复；
- 不再被待重试或待组装流程引用；
- 已超过 orphan / deletion grace period。

WorkUnit Artifact 在 Final 发布后不一定立即删除；只要 Partial revision、重试、诊断或其他 source pin 仍然引用它，就必须保留。content-addressed 共享 Artifact 只有在不可达时才可删除。

启动清理需要同时处理：

- Room 引用指向的 Artifact 缺失；
- 文件存在但尚未注册的 orphan；
- 旧 codec 或未知类型的 Artifact；
- 破坏性 schema 重建后遗留的生成目录。

用户原始媒体、外部字幕文件和 PCM / raw response 的生命周期仍由各自现有边界负责，不与 generated Artifact GC 混用。

## 11. 必须保持的不变量

1. Cue 是领域对象，不决定数据库行粒度。
2. WorkUnit output 不等于最终 Cue，也不能直接成为用户可见结果。
3. Artifact 一旦成功提交不可原地修改；新内容产生新 identity。
4. Room 不能提交一个事先不存在的 Artifact 引用。
5. Orphan Artifact 可以被 GC，dangling durable reference 不能被静默忽略。
6. 只有已发布 revision 对播放器可见。
7. Partial revision 可以可见，但未完成 WorkUnit 不可见。
8. Target revision 必须固定 source revision，不得混用不同 source / attempt / policy 的输出。
9. 播放只在内存中进行时间位置解析，不按 Cue 查询 Room。
10. VTT / SRT / ASS / LRC 只负责导入和导出，不负责内部恢复事实；已完成导入的 Track 不依赖外部 URI。
11. Task orchestration、Artifact Store、TimelineAssembler、publication 和 playback reader 保持独立职责。
12. 不为当前明确的 ASR -> source -> translation -> target 结构提前建设通用 workflow engine。

## 12. 非目标

- 不把生成文字正文全部改存为 Room cue 行；
- 不把 Room BLOB chunk 作为当前默认最终方案；
- 不引入通用工作流图、动态依赖 DSL 或全局任务历史中心；
- 不修改用户的外部 SRT / VTT / ASS / LRC 原文件；导入边界的规范化细节见详细设计；
- 不在本设计阶段改变 Partial / Final 的产品语义；
- 不为旧开发数据建立长期 Room / VTT 双写；
- 不因为采用 Artifact Store 就复制、移动或重命名用户原始媒体。

## 13. 对当前实现的目标映射

实施时应将现有字段和组件按以下方向收敛：

| 当前实现 | 目标职责 |
| --- | --- |
| `GenerationTaskEntity` / `GenerationAttemptEntity` | 保留为控制面任务与冻结执行上下文 |
| `AsrWindowEntity` / `TranslationBatchEntity` | 收敛为 WorkUnit 状态与 Artifact 引用 |
| `transcriptAssetFileName` / `resultAssetFileName` | 替换为 typed Artifact identity |
| `GeneratedTextResultEntity` | 引用 source / target TimelineRevision 和 provenance |
| `GeneratedTextSlotEntity.currentResultId` | 继续作为稳定 publication pointer，必要时指向 revision |
| `PrivateGeneratedTextAssetStore` | 演化为 content-addressed Artifact Store；VTT 编解码移出内部路径 |
| `AsrTimelineMerger` / `CueIdNormalizer` | 组合为可测试的 TimelineAssembler 领域逻辑 |
| `SyncTextRepository.publishResult` | 拆分 Artifact 提交、revision 创建和 publication transaction |
| `SubtitleTimeline` | 保持为播放运行时对象，不接触 Room |

当前仍允许破坏性重建 schema，因此实施时不建立旧 VTT 与新 Artifact 的长期双写。旧生成工件可以按现有开发阶段策略重建或清理；用户原始媒体和外部 SAF 文件不受影响。

## 14. 文档关系与实施门槛

本记录是目标架构决策摘要，不是已完成实现的声明。详细设计另见 [`DESIGN_2026-08-20_GENERATED_TEXT_STORAGE_DETAILED.md`](DESIGN_2026-08-20_GENERATED_TEXT_STORAGE_DETAILED.md)。当前行为仍由：

- [`GUIDE.md`](../GUIDE.md)；
- [`ASR_TRANSLATION_V1_SPEC.md`](../ASR_TRANSLATION_V1_SPEC.md)；
- 现有代码和验证记录；

共同决定。

实施前需要把本记录转化为一次完整的存储边界迁移：Artifact Store、WorkUnit 提交、TimelineAssembler、revision publication、reader、GC 和恢复路径应作为同一逻辑变更处理。验证属于该实现的自动化和真机验收组成部分，不把一次性试验或临时双写当作长期架构。

原 [生成文字进入 Room 的编排探索](EXPLORATION_2026-08-19_GENERATED_TEXT_ROOM_ORCHESTRATION.md) 保留为问题发现记录；其中“逐 CUE Room 表 / Room BLOB / 最小闭环试验”的未决选项由本记录收敛。
