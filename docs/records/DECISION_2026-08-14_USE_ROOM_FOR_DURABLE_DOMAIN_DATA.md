# 持久领域数据升级为 Room（2026-08-14）

> 状态：已实现，自动化验证完成，待真机验收
>
> 影响范围：内容目录、来源快照、用户关系、当前播放 Session / Queue，以及未来需要断点恢复的任务
>
> 当前规格：[`GUIDE.md`](../GUIDE.md)

## 背景

M3.2a 为了先建立内容目录事实，把当时的实现范围限制为版本化快照，并明确“不在该切片引入 Room”。这个限制只描述一个短期实现切片，却没有同时记录退出条件。后续 `PlayableContentUnit`、多 locator、文字轨、Recent、Collection、Source、Playlist 和当前 Queue 继续加入同一持久化模型，最终形成了一个存放在 Preferences DataStore 中的完整 `LibraryConfig` JSON。

切换前的实现已经出现以下信号：

- 任意一项内容关系变化都需要重新编码和写回完整 Library 快照；
- 冷启动需要解码完整目录，再在内存中重建分区和查找索引；
- `fix(startup): restore cached home state promptly` 为降低恢复延迟，开始维护内容单元压缩编码、encoding version 和恢复专用索引；
- Session 同时保存当前媒体快照和完整 `MediaSourceRef` Queue，locator 变化后还需要在启动阶段重新解析和回写；
- Home 为尽快显示状态而提前消费持久化 Queue 快照，但 Queue 的运行时编辑仍属于 Player，因而需要额外处理恢复与用户操作的竞态；
- 已规划的 ASR / 翻译任务需要窗口计划、批次、检查点、重试和结果引用，不适合继续进入完整 JSON 快照。

这些问题说明当前数据已经具备关系、局部更新、索引查询和引用完整性需求。继续为 JSON 增加压缩、索引、增量写入、引用修复和任务检查点，会使应用逐步承担存储引擎职责。

实施采用一次性运行时切换，没有引入 JSON / Room 双写。最近的启动修复在切换前继续解决了真实体验问题；Room Home 投影和 Playback 恢复接管后，其专用压缩、索引和启动竞态协调随旧存储一并删除。

## 决策

### 1. 持久化边界

- Room 成为关系型持久领域数据的唯一真相，负责 Content、locator、Source、文字轨、Recent、Collection、忽略决定、Playlist 和当前播放 Session / Queue 的恢复快照。
- Preferences DataStore 只保存少量、相互独立的设置，例如显示预设和引导完成状态；不得保存内容集合、Queue、任务列表、跨对象引用或完整领域聚合 JSON。
- Android Keystore 保护 API key 等凭据；普通数据库、设置、日志和任务报告只保存凭据引用或脱敏信息。
- 规范化字幕、原始诊断响应和其他适合流式访问的工件保存在应用私有文件中，Room 只保存元数据、状态和文件引用。
- 用户原始媒体继续保留在原位置，数据库只保存授权范围、稳定内容身份和 locator，不复制、移动、重命名或删除原文件。

### 2. 持久关系

目标关系至少表达：

```text
Content
├─ MediaLocator 1..N
├─ TimedTextTrack 0..N
├─ CollectionEntry 0..1
├─ RecentEntry 0..1
├─ IgnoreDecision 0..1
├─ PlaylistEntry 0..N
└─ CurrentQueueEntry 0..N

Source 0..1 ──< MediaLocator
CurrentPlaybackSession 1 ──< CurrentQueueEntry >── 1 Content
```

具体表名和 DAO 拆分属于实现设计，但以下不变量不能在实现中退化：

- 内容稳定身份使用 `contentId`；URI 只属于可替换的 locator。
- Collection、Recent、Playlist 和 Queue 保存 `contentId` 关系，不复制完整媒体快照作为身份。
- Collection 与 IgnoreDecision 对同一 Content 互斥；候选状态由已发现且不存在这两种用户决定推导，不额外复制一份候选集合。
- `RecentEntry` 只负责最近打开关系和排序；当前播放位置、速度和最后表面属于 `CurrentPlaybackSession`。
- Playlist 和 Queue 条目不保存各自的播放进度；Session 只保存当前项的位置。
- 文字轨、主副选择、拒绝决定和 offset 继续属于 Content，不进入 Draft 或 Session 副本。
- 删除 Source、移出 Collection 或清空 Queue 不删除用户原始媒体；仍被其他关系引用的 Content 及其用户决定不得被误删。

### 3. 播放状态所有权

- `PlaybackService` 中的 Player / MediaSession 继续是进程存活时的实时播放真相。
- Room 中的 Current Session 是进程重建和次日继续所需的恢复快照，不作为第二个运行时 Queue 与 Player 双向编辑。
- `MediaItem.mediaId` 使用稳定 `contentId`，实际播放 URI 来自该内容的 active locator；重新定位可以更换 URI 而不更换 Queue 身份。
- Queue 结构变化、当前项切换和清空按发生顺序持久化；位置更新可以采样或合并，但不能越过较新的结构操作并让已清空 Queue 复活。
- Session 持久化由 Playback 层的单一协调器负责。Activity 和 Compose UI 不再各自启动未跟踪的 `saveLastSession()` 写入。
- Home 可以先展示 Room 中的恢复投影，但所有 Queue 操作仍路由到 Playback 层；服务未完成恢复时应等待或排队操作，不允许显示一份可编辑但实际未连接到 Player 的平行 Queue。
- Sleep Timer 当前仍是 PlaybackService 运行时状态；本决策不新增进程死亡后的定时恢复语义。

### 4. 查询与更新方式

- Home、我的内容、来源、发现和内容详情分别使用有界查询投影，不在冷启动时加载完整数据库对象图再进行全量内存分区。
- 单个用户动作只更新受影响的行，并在需要跨表保持不变量时使用 Room transaction。
- 不提供新的 `save(AllState)`、`save(LibraryConfig)` 或“删除后重写全部表”接口来模拟旧快照。
- Source 扫描使用可识别的一次扫描批次或 generation 增量写入；只有一次扫描成功提交后，才能把本次未出现的 locator 标记为不可用或移除非保留引用。
- 内容身份匹配、文字轨排序和自动选择等领域规则继续保持为可测试的纯逻辑；数据库负责持久关系、查询和事务，不把全部产品规则隐藏在 SQL 中。

### 5. Schema 与切换策略

- 首个正式 release 前仍只支持当前开发 schema；不兼容的 Room 数据可以破坏性重置，但不得删除或修改用户原始媒体。
- Library / Session 旧 DataStore 快照默认不迁移。Room 首次启用后重置旧目录和会话状态，并移除旧 key；独立设置继续保留。
- 不建立长期 JSON / Room 双写，也不维护旧模型 fallback。若实现前确认某台开发设备的数据必须保留，只允许增加一个仅识别当前 Library v7 / Session v2 的一次性导入器，成功后立即删除旧状态。
- Room schema 应导出并纳入审查。首个正式 release 冻结数据契约后，停止依赖破坏性重置，并从当时版本开始维护正式 migration。

## 实施顺序

1. 建立 Room database、实体、DAO、事务和测试基础，并保持单一 `app` Gradle 模块。
2. 先切换内容、locator、文字轨、Source 和用户关系；Home 改为读取有界查询投影。
3. 再把 Current Session / Queue 切换为 `contentId` 关系，并将恢复和持久化协调移入 Playback 层。
4. 删除 Library JSON、Queue JSON、内容单元压缩编码、encoding version 和完整目录恢复索引。
5. 完成冷启动、进程重建、Activity 重建、外部打开竞态、Source 刷新和真机生命周期验证。
6. 只有在 ASR / 翻译进入实现时才增加任务、窗口、批次和结果表，不为尚未进入当前范围的能力预建空 schema。

## 非目标

- 本决策不引入依赖注入框架或新 Gradle 模块。
- 不因为采用 Room 就扩展 Artist、Album、标签、复杂资源库或全设备扫描范围。
- 不把字幕 cue 全文或媒体二进制默认写入数据库。
- 不在本轮处理 `GUIDE.md` 的治理流程、文档拆分或决策审查机制；这些问题另行讨论。

## 结果

- Room schema v1 已导出，17 张表覆盖内容身份、locator、文字关系、用户关系、Draft 和 Current Session / Queue；生产使用 Android SQLite framework driver，JVM 数据库测试使用 bundled driver。
- `ContentRepository`、`PlaybackStateRepository`、`PlaybackGateway` 和应用级 `AppContainer` 已成为 Activity 与 Service 的内部边界；Home 使用固定投影，内容列表使用 page size 50 的 PagingSource。
- `LibraryConfig`、`LibraryStore`、`SessionStore`、完整状态 JSON 编码、内容单元压缩、恢复索引和启动竞态桥接已经删除。`settings_state` 只保存显示和引导设置，并在数据库成功打开后一次性清空旧 Library / Session DataStore。
- Queue 结构命令由 `PlaybackService` 串行写入，位置和当前项写入受 `queueRevision` 保护；进程存活时采用 Player，进程重建后按需从 Room 实体化 Queue。
- 自动化验证为 26 个 suite、113 项测试、0 失败；`lint` 与 `assembleDebug` 通过。覆盖安装、SAF、耳机恢复、强制停止、临时 URI、大目录和破坏性 reset 仍需按真机清单验收。
- 未来功能需要持久化集合、关系、局部状态或检查点时，默认先判断是否属于 Room，而不是向 DataStore 增加新的 JSON key。
- 采用数据库不会消除领域 schema 设计责任；它消除的是应用自行实现整文件写入、局部更新、索引、事务和引用完整性的责任。

## 文档优先级

本记录解释本次架构变化和历史原因，不替代当前规格。若本记录与代码或历史验证记录存在冲突，以 [`GUIDE.md`](../GUIDE.md) 为准。
