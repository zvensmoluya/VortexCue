# Generated Text Storage Detailed Design

## 生成文字与导入字幕统一存储详细设计（2026-08-20）

> 状态：已实施；真机生命周期矩阵待执行。
>
> 本文把生成文字的 Artifact / Revision 设计与用户导入字幕的资源拥有权设计放在同一个内部模型中。它是 [`DECISION_2026-08-20_GENERATED_TEXT_ARTIFACT_STORAGE.md`](DECISION_2026-08-20_GENERATED_TEXT_ARTIFACT_STORAGE.md) 的详细设计和实现状态记录；当前产品行为仍以 [`GUIDE.md`](../GUIDE.md) 为准。

## 1. 设计结论

VortexCue 的运行时不依赖外部字幕文件，也不把字幕交换格式当作内部事实协议。用户完成“导入”后，外部文件只完成一次输入职责；VortexCue 读取、解析、规范化并拥有自己的内部时间轴资产。

统一模型如下：

```text
外部 SRT / VTT / ASS / LRC
          │
          │ 仅在导入边界读取一次
          ▼
  ImportCoordinator + Canonicalizer
          │
          ▼
  Immutable Timeline Artifact
          │
          ├── Room：关系、状态、引用、revision、发布指针
          └── Memory：播放用 SubtitleTimeline

ASR / translation task
          │
          ▼
  WorkUnit Artifact -> TimelineAssembler -> TimelineRevision
```

两条入口最终都产生 VortexCue 自有的 canonical timeline。播放层不关心它原来是 ASS、SRT、VTT、LRC，还是模型生成的结果。

核心原则：

> **Subtitle source controls what is said and when.**
>
> **VortexCue controls how it is rendered.**

原文件不会被删除、移动或修改；“吞噬”只表示运行时不再依赖它。原始路径可以在导入完成后完全丢弃，最多保留不可用于播放的导入 provenance。

## 2. 目标与非目标

### 2.1 目标

- 导入完成后，即使原文件被移动、重命名、删除或失去 SAF 权限，字幕仍可播放。
- 生成文字和导入字幕使用统一的内部时间轴模型与读取路径。
- Room 管理 durable facts、任务编排和发布关系，不保存逐 Cue 正文。
- Artifact Store 保存不可变、可校验、可恢复的结构化 payload。
- 播放开始后一次顺序加载到内存，不按播放位置逐句查询 Room 或文件。
- 进程终止、重试、拆分、部分发布和垃圾回收都有明确的引用关系。
- 为未来显式导出保留 `revision reader -> exporter` 边界，不在本轮增加导出 UI。

### 2.2 非目标

- 不保留 ASS 的字体、颜色、位置、边距、层级和样式语义。
- 不把每个 Cue 设计成一条 Room 行。
- 不把 Room BLOB 或通用 JSON 快照作为默认内部正文存储。
- 不实现通用 workflow engine、动态依赖 DSL 或全局任务历史中心。
- 不修改、复制、移动或删除用户的原始媒体和原始字幕文件。
- 不承诺导出时恢复原 ASS 样式；需要样式保真时必须另立产品和数据契约。

## 3. 分层与职责

### 3.1 外部输入层

外部字幕 URI、MIME type、扩展名和文件名只在导入边界有意义。它们是输入线索，不是运行时身份。

```text
ExternalSubtitleInput
  uri
  displayName
  mimeType
  detectedFormat
```

`uri` 只允许出现在“导入尚未完成”的操作上下文中。已完成的 `TimedTextTrack` 不以 URI 作为 locator，也不在播放时回读 URI。

### 3.2 Room 控制面

Room 保存关系型持久事实：

- `TimedTextTrack` 与 `Content` 的关系；
- Track 的语言、label、role、选择状态和 readiness；
- `TextImportOperation` 的导入状态、临时输入引用和失败原因；
- Artifact metadata 与引用关系；
- `TimelineRevision`、source/target 关系和 completeness；
- `GenerationTask`、`GenerationAttempt`、`WorkUnit`、lease、retry 和 usage；
- slot 的当前发布指针；
- GC 所需的可达引用。

Room 不保存正文 Cue，也不在播放热路径中承担时间查询。

### 3.3 Immutable Artifact Store 数据面

Artifact Store 位于应用私有目录，保存不可变 payload。文件名和目录只由 Artifact identity 派生，不由外部 URI、窗口名称或业务显示名派生。

推荐逻辑布局：

```text
<app-private>/text_artifacts/
  <kind>/
    <artifact-id-prefix>/
      <artifact-id>.bin
```

生成任务的 PCM、原始 provider response 和导出临时文件仍有各自目录和保留策略，不与 Timeline Artifact GC 混用。

### 3.4 Memory 播放层

```text
Room current publication
  -> resolve artifact(s)
  -> sequential decode
  -> SubtitleTimeline + in-memory index
  -> resolve(positionMs)
```

运行时不查询单个 Cue 的 Room 行，不重新解析 VTT，也不等待外部 ContentProvider。

## 4. 统一领域模型

### 4.1 Cue

`Cue` 是领域对象，不是数据库行：

```text
Cue {
  cueId: StableCueId
  startMs: Long
  endMs: Long
  text: String
}
```

内部 Cue 只表达“说什么”和“什么时候有效”。字号、颜色、位置、渐变、Glow 强度、当前句/上一句/下一句布局等属于 VortexCue renderer。

`cueId` 在同一 source revision 内稳定。导入时由规范化后的 `(startMs, endMs, text, occurrence)` 确定性生成；生成和翻译必须沿用已固定 source revision 的 Cue identity。不能只用数组 ordinal 跨 revision 关联。

### 4.2 TimelineArtifact

`TimelineArtifact` 是不可变的 canonical payload。Artifact 的 identity 由以下 envelope 计算，而不是由裸 URI 或业务文件名计算：

```text
artifactId = SHA-256(
  kind
  + schemaVersion
  + codecVersion
  + canonicalizerVersion
  + canonicalPayload
)
```

可用类型至少包括：

```text
ImportedTimeline       // 用户导入后得到的完整时间轴
AsrWorkUnitOutput      // 一个 ASR window 的结构化输出
TranslationWorkUnitOutput
FinalTimeline          // 已发布的完整时间轴
TranslationDelta       // 固定 source revision 上的翻译文本映射
```

Partial revision 当前不另写累计 Artifact，而由 immutable revision-artifact rows 直接引用连续完成前缀的 `AsrWorkUnitOutput`。

Artifact payload 不包含 URI、SAF 权限、原始绝对路径或用户设备路径。

### 4.3 TimelineRevision

Revision 是一个不可变、可发布的产品结果版本，至少包含：

```text
revisionId
contentId
role                 // source / target
artifactId 或 manifestId
sourceRevisionId     // target 必须填写
sourceRevisionHash   // target 必须填写
completeness         // Partial / Final
cueCount
coveredEndMs
contentHash
assemblerVersion
createdAtMs
```

Artifact 是数据对象，Revision 是产品可见版本。一个 Artifact 可以被多个关系引用；一个 Revision 只能指向不可变 Artifact 或不可变 manifest。

### 4.4 TimedTextTrack

用户可选的 Track 是内容关系，不是外部文件引用：

```text
TimedTextTrack {
  trackId
  contentId
  revisionId
  language
  label
  roles
  origin              // Imported / Generated / Embedded / Discovered
  readiness           // Ready / Importing / Failed / MissingArtifact
  matchBasis
  provenance          // 可选、非运行时
}
```

对于 `Ready` Track，`revisionId` 必须能够解析到存在且校验通过的 Artifact。`uriValue` 不能再作为 Ready Track 的唯一 locator。

`Discovered` 只是扫描到的外部候选，不等同于完成导入；只有用户确认导入，或者产品明确执行 materialize，才转换成 VortexCue-owned revision。

### 4.5 GeneratedTextSlot

Slot 表达某个内容的生成结果位置，例如 source、target language 和 presentation mode。Slot 只保存当前 publication pointer，不复制完整正文：

```text
GeneratedTextSlot.currentResultId -> GeneratedTextResult -> source / target TimelineRevision
```

历史 revision 可以保留用于恢复、诊断、target pin 或 GC 判断，但播放器只读取当前发布版本。

## 5. 导入规范化设计

### 5.1 格式只存在于边界

```text
Import:  SRT / VTT / ASS / LRC -> canonical Timeline
Export:  canonical Timeline -> SRT / VTT / ASS / LRC
```

内部不以某一种交换格式作为事实协议。内部 codec 必须有明确的 `schemaVersion`、`codecVersion` 和 decoder 版本拒绝规则。

### 5.2 文本规范化

规范化器只去除格式层的差异，不改写用户实际要读到的内容：

- 去除 BOM，统一换行符为 `\n`；
- 清理行尾空白，保留有意义的行内换行；
- 解析 SRT / VTT 的时间格式和 cue 边界；
- 忽略 VTT cue settings、regions 和展示样式；
- 解析 ASS `Dialogue`，将 `\N` 转为换行，去除 override tags 和样式控制；
- LRC 的时间标签转为 cue 起点，结束时间按下一 cue 起点或媒体时长推导；
- MIME type 仅作为提示，扩展名和内容探测共同决定 parser；
- 对无法形成有效时间范围的 cue 失败，不把坏数据推迟到播放阶段。

ASS karaoke、`\pos`、字体、颜色、边距、层级等渲染语义不进入内部模型。若某种标签影响文本或时间语义，必须在 parser 中显式转换，而不是用“删除所有大括号内容”这种无规则字符串替换。

### 5.3 时间范围规则

- `startMs >= 0`；
- `endMs > startMs`；
- 时间轴按 `startMs` 和导入顺序稳定排序；
- 重叠 cue 默认保留，不在导入时擅自合并；
- LRC 缺少显式结束时间时，使用下一 cue 的开始时间；最后一条使用媒体时长，若媒体时长不可用则使用明确版本化的默认尾长；
- 超出媒体时长的 cue 不静默丢弃，按规范化策略裁剪或标记导入失败；策略版本写入 Artifact metadata；
- cue 文本为空且没有可见内容时不进入 canonical timeline。

规范化规则变化必须提升 `canonicalizerVersion`，避免新旧导入结果在同一个 content hash 下混淆。

## 6. 用户字幕导入流程

导入不是“先存 URI、以后播放时再解析”，而是一次具有提交边界的拥有权转换。

### 6.1 生命周期

```text
Requested
  -> Reading
  -> Parsed
  -> ArtifactPublished
  -> Committed
  -> Cleanup
```

失败和取消是终态：

```text
Failed / Cancelled
```

导入操作不是通用工作流引擎。它是一个有限状态机，既可以由一次短 suspend 调用完成，也可以在大文件或进程重建时由受控恢复逻辑继续。

### 6.2 提交协议

`ImportCoordinator` 必须按以下顺序工作：

1. 接收外部 URI，确认目标 `contentId` 和导入类型。
2. 在导入尚未完成期间，必要时取得可持久读取权限，并把 URI 只记录在 `TextImportOperation` 的暂存字段中。
3. 打开 URI 一次，解析并规范化为 canonical timeline。
4. 将 payload 写入应用私有临时文件，flush 后以 content-addressed identity 原子发布 Artifact。
5. 在一个 Room write transaction 中：
   - 注册 Artifact metadata；
   - 创建 `TimelineRevision`；
   - 创建或更新 `TimedTextTrack`，写入 revision 引用；
   - 写入语言、label、role 和非运行时 provenance；
   - 将导入操作标记为 `Committed`；
   - 清除 staging URI 和临时状态。
6. 事务成功后，尽力释放 SAF 权限。释放失败只记录清理问题，不影响播放；完成后的 Track 绝不回退到 URI。

恢复时保留持久化阶段：`Reading` / `Parsed` 可从 staging URI 重读，`ArtifactPublished` 必须按 Artifact ID 校验并继续提交，即使原 URI 已不可读也不得回退或重新解析。

核心不变量：

```text
TimedTextTrack.readiness == Ready
  => revision/artifact 已在 Room 中有引用
  => artifact 文件已在 Room transaction 之前发布
  => 播放不需要外部 URI
```

如果原文件在第 3 步之后立刻消失，导入失败；不能创建一个“稍后再读取”的半成品 Track。

### 6.3 幂等与重复导入

- Artifact 按 canonical payload 去重；同样的规范化内容可以由多个 Track 共享；
- `trackId` 是一次内容关系的稳定身份，不使用原始 URI 作为 Track 身份；
- 同一文件再次导入可以复用 Artifact，但是否创建新 Revision 由用户操作和导入 provenance 决定；
- 同一字幕导入到不同 `contentId` 时，Artifact 可共享，Track 关系不能共享；
- 重复提交相同 `importId` 不得创建两个 Ready Track 或两个不可达的发布指针。

### 6.4 进程中断与失败

进程可能在以下位置终止：

```text
读取中
规范化中
Artifact 已发布、Room transaction 尚未提交
Room transaction 已提交、权限清理尚未完成
```

恢复规则：

- 读取/规范化中：根据 `TextImportOperation` 和仍有效的暂存 URI 重试，或标记需要用户重新选择；
- Artifact 已发布但未注册：作为 orphan Artifact，等待宽限期后 GC；
- Room 已提交：直接视为导入完成，不重新读取外部文件；
- 权限清理失败：不影响 Track，启动清理再次尝试；
- 已完成 Track 的 Artifact 缺失或 hash 校验失败：标记 `MissingArtifact`，要求重新导入，禁止静默回读原 URI。

### 6.5 导入后的数据使用

用户原字幕文件可以被重命名、移动、删除，甚至所在存储卷不可用。VortexCue 仍然从自己的 Artifact 读取。

导入后的显示名、原格式和原始文件 hash 只用于诊断、列表展示或未来导出提示，不参与播放身份，也不允许成为运行时 fallback。

## 7. 生成文字任务编排

生成文字沿用同一 Artifact Store，但它有额外的任务和恢复状态：

```text
GenerationTask
  -> GenerationAttempt（冻结配置与输入版本）
  -> WorkUnit plan
  -> WorkUnit output Artifact
  -> TimelineAssembler
  -> TimelineRevision
  -> publication
```

### 7.1 Attempt 快照

Attempt 必须冻结影响输出解释的事实：

- provider、model、endpoint 和 credential identity；
- source / target language；
- prompt / policy identity、version 和 hash；
- segmentation、ASR window、overlap、planner 和 assembly policy；
- 输入 source revision、source Artifact hash 或媒体快照引用；
- request schema 和 codec version。

配置变化创建新的 Attempt；同一 Attempt 内的网络重试不改变快照。

### 7.2 WorkUnit 输出

ASR window 和 translation batch 都是可执行、可重试、可恢复的 WorkUnit，但它们的输出不是最终用户 Cue。

成功提交顺序：

```text
provider response
  -> parse structured output
  -> write temp Artifact
  -> flush + atomic publish
  -> Room transaction:
       register Artifact
       reference from WorkUnit
       store usage / hash / count
       mark WorkUnit completed
```

Room 与文件系统不共享事务，因此不宣称跨存储原子性；只保证 Room 中的 durable Artifact 引用一定指向先前已发布的文件。

### 7.3 Retry 与 split

- WorkUnit 使用稳定的逻辑键和 Attempt 归属；重复提交按 canonical payload 幂等；
- parent split 与 children 创建在一个 Room transaction 中完成；
- parent 进入 `Split` 后不再作为最终输入；
- 已完成 child Artifact 不被其他 child 的失败覆盖；
- 进程终止后，已提交的 WorkUnit 直接恢复，只有没有提交 Artifact 的单元重新执行；
- provider 请求可能重复发送，但本地 Artifact 提交、usage 记录和 WorkUnit 状态必须可判定、可去重。

### 7.4 Partial 与 Final

Partial 是已发布的、合法的部分 revision，不是进行中的 WorkUnit 快照：

```text
已完成 WorkUnit Artifact
  -> Partial manifest 或 PartialTimeline Artifact
  -> publication
```

不在每个 ASR window 完成时原地重写累计 prefix。Final 生成时由 `TimelineAssembler` 完成 overlap、去重、排序、merge 和 Cue identity 稳定化，再发布新的不可变 Final revision。

Translation 只能固定一个 source revision：

```text
TargetRevision
  -> sourceRevisionId + sourceRevisionHash
  -> TranslationDelta / target FinalTimeline
```

不能将不同 Attempt、不同 source revision 或不同 normalization policy 的结果混合成一个 target。

## 8. Room 逻辑数据设计

以下是目标逻辑边界，不要求一张表对应一个领域对象，也不把 Cue 作为表粒度。

### 8.1 Artifact metadata

```text
text_artifact
  artifact_id             primary key
  kind
  schema_version
  codec_version
  canonicalizer_version
  payload_sha256
  byte_size
  cue_count               nullable
  created_at_ms
  gc_candidate_since_ms   nullable
```

Artifact metadata 不保存正文。`payload_sha256` 与 identity 用于完整性检查和幂等写入。

### 8.2 Revision 与引用

```text
timeline_revision
  revision_id             primary key
  content_id
  role
  source_revision_id      nullable
  source_revision_hash    nullable
  completeness
  covered_end_ms
  cue_count
  content_hash
  assembler_version
  created_at_ms

timeline_revision_artifact
  revision_id
  artifact_id
  ordinal
  role
```

Final revision 通常只需要一个 Artifact；Partial 或未来分块实现可以通过不可变 manifest 引用多个 Artifact。当前不预建逐 Cue 或逐时间段索引表。

### 8.3 Track 与导入操作

```text
timed_text_track
  track_id                primary key
  content_id
  revision_id
  origin
  display_name
  language
  label
  readiness
  match_basis

text_import_operation
  import_id               primary key
  content_id
  candidate_id            nullable
  staging_uri             nullable, only before completion
  source_display_name
  detected_format
  state
  artifact_id              nullable
  revision_id              nullable
  track_id                 nullable
  failure_category         nullable
  failure_message          nullable
  created_at_ms
  updated_at_ms
```

`staging_uri` 是恢复导入所需的临时事实，不是 Track 的运行时依赖。提交成功后必须清空。

### 8.4 生成控制面

保留当前已有的 `GenerationTask`、`GenerationAttempt`、`AsrWindow`、`TranslationBatch`、`RequestAttempt`、`GeneratedTextSlot` 等控制面实体，但把以下文件名字段逐步替换为 typed Artifact / Revision 引用：

```text
transcript_asset_file_name -> transcript_artifact_id
result_asset_file_name     -> result_artifact_id
source_asset_file_name     -> source_revision_id / source_artifact_id
target_asset_file_name     -> target_revision_id / target_artifact_id
```

`GeneratedTextResult` 可以保留为面向当前 UI 和审计的投影，但正文来源必须转为 revision，而不是 VTT 文件名。

## 9. 播放、恢复与性能

### 9.1 播放读取

选中 Track 后只执行一次有界 metadata 查询和一次顺序 Artifact decode：

```text
selectedTrackId
  -> revisionId
  -> artifact / manifest
  -> sequential decode
  -> SubtitleTimeline
```

时间位置解析在内存索引中完成。典型复杂度是：

- Room metadata resolve：常数级、低频；
- Artifact decode：按时间轴正文大小线性一次；
- position resolve：内存中的二分或邻近索引；
- 播放期间 Room / SAF / 外部文件读取：零次。

这不是“数据库一定比文件快”。对小字幕文件，单次顺序读本来就很快；设计的收益是取消外部 provider 不稳定性、避免 URI 权限依赖、去除 VTT 往返，并让恢复和引用关系可判定。绝不通过每 Cue 一次数据库查询换取所谓结构化。

### 9.2 进程重建

- 播放恢复只读取当前 Track 的 published revision；
- 未完成的生成任务从 Room 的 Attempt / WorkUnit 状态恢复；
- 已完成 WorkUnit 不重新请求 provider；
- 未完成的导入操作只按 `TextImportOperation` 处理，不会伪装成 Ready Track；
- 外部 URI 失效不会影响已经 Committed 的 Track；
- revision 指针更新必须在 Room transaction 中完成，播放器不会看到半个 revision。

### 9.3 大时间轴

当前默认整体顺序加载。只有实际出现超大正文、明显内存压力或随机访问需求时，才引入粗粒度 chunk / manifest / sparse time index。即便分块，播放仍应由 revision reader 管理，不把 chunk 设计成逐 Cue Room 查询。

## 10. Artifact 提交、完整性与 GC

### 10.1 完整性不变量

1. Artifact 一旦发布不可原地修改；新内容产生新 identity。
2. Room 不能提交一个事先不存在的 Artifact 引用。
3. `Ready` Track 必须能解析到完整性 hash 匹配的 Artifact。
4. orphan Artifact 可以在宽限期后 GC；dangling durable reference 不能静默忽略。
5. 只有 published revision 对播放可见。
6. Partial revision 可以可见，但未完成 WorkUnit 不可见。
7. Target revision 必须固定 source revision。
8. 共享 Artifact 只有在无任何可达引用时才能删除。

### 10.2 可达性 GC

```text
TimedTextTrack / GeneratedSlot
  -> TimelineRevision
  -> Artifact / Manifest

Active GenerationTask / Attempt / WorkUnit
  -> recovery Artifact

TranslationTask
  -> pinned source revision
```

只有不再被当前或历史保留 revision、active/paused/needs-attention task、待重试或待组装流程引用，且超过 orphan/deletion grace period 的 Artifact 才能清理。

启动审计同时检查：

- Room 引用指向的 Artifact 缺失；
- 文件存在但尚未注册的 orphan；
- 未知 codec / schema；
- 导入操作与临时文件不一致；
- 破坏性 schema 重建后的旧目录残留。

损坏引用应转成明确的 `MissingArtifact` 或局部任务失败，不得静默恢复到外部 URI。

## 11. 导出边界

导出是内部 canonical timeline 的反向适配：

```text
published revision
  -> revision reader
  -> exporter
  -> Android CreateDocument
  -> user-owned SRT / VTT / ASS / LRC
```

导出不会覆盖导入时的原文件，也不会要求原文件仍然存在。默认导出只表达 canonical 文本和时间，不恢复被剥离的 ASS 样式。若将来需要样式保真，应另建一个明确的 style-preservation 产品能力，不能偷偷把 ASS 样式重新混入当前内部模型。

## 12. 实现状态（2026-08-20）

| 已实施组件/字段 | 当前职责 |
| --- | --- |
| `PrivateTextArtifactStore` | content-addressed `TextArtifactStore`，按 kind/prefix/id 发布确定性 binary envelope |
| `TimedTextTrackEntity.revisionId` | Imported Ready Track 的唯一 owned locator；实体不再保存 URI/cache 文件名 |
| `TextImportCoordinator` | parse、canonicalize、Artifact publish、revision/track/selection transaction、恢复和 SAF grant 释放 |
| `TimelineRevisionReader` | 统一读取 imported/final、partial manifest 和 translation delta；播放只消费内存 timeline |
| `TextStorageMaintenance` | dangling 审计、`MissingArtifact` / `Storage` 标记、24 小时两阶段 Artifact GC 和旧目录清理 |
| `AsrWindowEntity.transcriptArtifactId` | `AsrWorkUnitOutput` Artifact reference |
| `TranslationBatchEntity.resultArtifactId` | `TranslationWorkUnitOutput` Artifact reference |
| `GeneratedTextResultEntity.sourceRevisionId/targetRevisionId` | source / target immutable revision reference |
| `AsrTimelineMerger`、`CueIdNormalizer` | 组合为纯逻辑 `TimelineAssembler` |
| `SubtitleTimeline` | 保持为播放运行时对象，不接触 Room 实体 |

Room 已升级到 v6 并采用破坏性重建；没有旧 VTT 与新 Artifact 双写，也不迁移 v5 generated VTT 或字幕缓存。启动时受控清理旧 `subtitle_assets`、`session_subtitles`、result/checkpoint VTT 目录；用户原始媒体、外部字幕文件和 Settings 不受影响。当前不存在导出 UI，因此只保留 codec、revision reader 与未来 exporter 的边界。

## 13. 实施边界与顺序

这不是把长期架构拆成若干“最小闭环单点验证”再决定方向。方向已经由本设计确定；实现应作为一次完整的存储边界迁移交付，验证用于证明不变量和恢复路径，而不是替代设计。

实际落地顺序：

1. 建立 canonical timeline payload、版本化 codec、Artifact Store 和完整性校验。
2. 增加 Artifact metadata、Revision、导入操作和 Track 引用的 Room schema。
3. 将手动字幕导入切换为导入即规范化、原子发布和事务提交。
4. 将生成 WorkUnit 的 VTT 文件引用切换为 Artifact 引用，保留现有任务、attempt、retry、split 和 publication 语义。
5. 统一 playback reader，删除已完成 Track 对外部 URI 的 fallback。
6. 保留 exporter 边界并清理旧的内部 VTT 路径；本轮不新增导出 UI。
7. 完成 GC、恢复和损坏引用处理的实现与自动化；SAF 生命周期保留为真机验证矩阵。

任何中间提交都必须保持明确的兼容边界；不把“临时双写”当作长期架构，也不在没有真实访问压力前引入逐 Cue 表或分块索引。

## 14. 验证要求

### 14.1 纯逻辑测试

- SRT / VTT / ASS / LRC parser 和 canonicalizer；
- ASS 样式剥离、换行、karaoke 标签处理；
- LRC 结束时间推导；
- cue 时间边界、重叠、排序和稳定 ID；
- canonical payload hash、codec version 拒绝和重复写入幂等；
- `TimelineAssembler` 的 overlap、split、merge 和 source/target 对齐。

### 14.2 Room 与存储测试

- Artifact 发布后再提交 Room 引用；
- Room transaction 前后进程中断；
- orphan 与 dangling reference 审计；
- 同一 Artifact 多 Track 共享和安全 GC；
- import operation 的重试、取消、重复提交和失败状态；
- target 对 source revision 的 pin；
- Partial / Final publication pointer 的原子切换。

### 14.3 真机生命周期测试

- SAF 单文件导入完成后撤销权限，仍可播放；
- 原字幕文件移动或删除，已导入 Track 不受影响；
- 导入过程中强制停止后可恢复或明确要求重新选择；
- 进程重建后生成任务从已完成 WorkUnit 恢复；
- 外部文件不可用时不会出现“播放时才失败”的隐藏依赖；

## 15. 文档关系

- 本文是详细设计：定义统一 Artifact、导入拥有权、任务恢复、播放读取和 GC 的实施边界。
- [`DECISION_2026-08-20_GENERATED_TEXT_ARTIFACT_STORAGE.md`](DECISION_2026-08-20_GENERATED_TEXT_ARTIFACT_STORAGE.md) 是决策摘要和取舍记录。
- [`EXPLORATION_2026-08-19_GENERATED_TEXT_ROOM_ORCHESTRATION.md`](EXPLORATION_2026-08-19_GENERATED_TEXT_ROOM_ORCHESTRATION.md) 保留早期问题发现，不再表达未决选项。
- [`GUIDE.md`](../GUIDE.md) 是当前产品和工程规格；实现迁移完成后，应把已落地行为和新的用户可见约束同步回该文档。
