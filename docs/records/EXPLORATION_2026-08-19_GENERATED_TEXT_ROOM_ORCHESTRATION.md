# 生成文字进入 Room 的编排探索（2026-08-19）

> 状态：探索中，不改变当前实现契约。
>
> 本记录只保存一次架构复盘的方向，不替代 `GUIDE.md` 或现有 ASR / translation 规格。真正实施前，需要先验证 schema、恢复和播放性能。

## 背景

当前 ASR 和 translation 已经引入 Room 保存任务、attempt、ASR window、translation batch、结果 slot 和运行状态，但生成的 cue 正文仍然通过应用私有 VTT 文件保存。内部链路大致是：

```text
Provider response
  -> List<SubtitleCue>
  -> window / batch VTT checkpoint
  -> Room 状态 + 文件引用
  -> source / target VTT result
  -> 播放时重新解析为 SubtitleTimeline
```

这种做法是有意的规范化存放，并非错误；它使用原子文件、hash 和启动清理来维持文件与 Room 引用的可恢复性。当时的设计重点是先建立稳定的生成工件边界。

## 新的观察

生成字幕不是用户导入的交换文件，而是应用自己生成、自己编排、自己播放的结构化时间轴。对这类数据，VTT 更像导入 / 导出格式，不一定需要继续作为内部事实来源。

因此可以探索以下方向：

```text
Room：生成文字的持久事实与任务 checkpoint
Memory：播放用 SubtitleTimeline 缓存
Files：外部字幕、导出文件、PCM 临时数据和大型原始响应
```

## 方向草图

### 1. 结构化生成时间轴

为生成结果增加应用拥有的 timeline / cue 数据模型。大致包含：

- timeline 元数据：内容、语言、来源、revision、完整性和 provenance；
- cue 数据：稳定 ID、序号、起止时间和文本；
- result / slot：当前 source、target timeline 的关系和可见版本。

翻译结果可以引用已有 source timeline，只保存 target timeline 或 target cue 文本，避免每次翻译都重新写一份 source VTT。

### 2. checkpoint 与状态同事务

一个 ASR window 或 translation batch 成功时，在同一个 Room transaction 中完成：

1. 写入该单元的结构化 cue 或 checkpoint payload；
2. 写入用量、hash / revision 等必要元数据；
3. 把 window / batch 标记为完成。

这样恢复逻辑直接读取数据库，不需要处理“状态已完成但 checkpoint 文件缺失”的跨存储补偿情况。

checkpoint 最终采用 cue 子表还是版本化的结构化 payload，暂不在本记录中决定。前者便于局部更新，后者表面更小；应以实际数据量和恢复测试为依据。

### 3. 内存只负责播放

播放开始或当前结果 revision 改变时，一次性从 Room 读取 cue，构造 `SubtitleTimeline` 并缓存。播放位置解析继续在内存完成，不按时间进度逐句访问 Room。

### 4. 文件退回边界职责

- 外部 SRT / VTT / ASS 等仍按现有导入路径处理；
- 用户明确导出时再生成 SRT / VTT；
- PCM spool 和大型 raw response 继续使用文件，避免把媒体二进制或诊断响应塞进 Room；
- 新的内部 ASR / translation 结果不再默认依赖可读字幕文件。

## 预期收益

- cue 正文、任务状态和结果指针可以在同一个持久化边界内更新；
- 减少 `cue -> VTT -> cue` 的重复编码和解析；
- ASR 部分结果和 translation batch 更容易做增量恢复；
- source / target 的关系更清晰，也为后续 cue 级质量检查或局部修订留下空间；
- VTT / SRT 格式变化不再影响任务恢复模型。

## 上手前需要验证的点

- 一条长音频通常产生多少 cue，Room 批量写入和 WAL 体积是否可接受；
- ASR overlap、split 和 merge 后，cue 稳定 ID 是否足以支持增量 upsert；
- translation batch 失败、进程终止、重复恢复时，数据库事务能否保持幂等；
- 当前部分结果的播放切换如何从 result ID 改为 revision 或 working timeline；
- 现有私有 VTT 是否需要一次性导入、懒迁移，还是允许开发阶段重建；
- 真机上长文本加载、进程重建和冷启动的耗时与内存峰值。

## 建议的验证顺序

先不重写全部模块，选一条最小闭环验证：

1. 为一个生成结果建立 Room timeline / cue 原型；
2. 让一个 translation batch 在事务中写入并恢复；
3. 用同一份 cue 构造现有 `SubtitleTimeline` 播放；
4. 再把 ASR window checkpoint 接入，验证 split、重试和进程终止；
5. 通过后才决定是否移除内部 VTT result / checkpoint。

在方向被验证前，当前规范化 VTT 存放继续有效；本记录不要求立即修改现有代码。
