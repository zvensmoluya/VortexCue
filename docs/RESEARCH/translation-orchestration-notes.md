# ASR 源字幕准备与翻译编排设计笔记

> 状态：v1 已进入内核实现；本文保留设计依据与后续实验项。
>
> 本文记录已经收敛的职责边界、数据流和验证问题，用于解释 Prompt 与内核设计并继续真实样本实验。它不是当前产品规格；与现有行为冲突时以 [`../GUIDE.md`](../GUIDE.md) 为准。

## 1. 背景与目标

VortexCue 现有翻译 Prompt 从 TransVortex 的另一套工作流迁入，其中包含动态术语维护、跨批工作记忆和人类参与修订等假设。VortexCue 当前编排并未提供这些能力，产品也不是字幕制作控制台，因此 Prompt 与实际运行边界不一致。

本轮设计解决两个相连但必须分开的质量问题：

1. ASR 输出可能包含固定污染短语、声音事件标签、明显听错或上下文缺字；这些问题同样影响只观看源语言字幕的用户。
2. 翻译按批并发时，单个批次看不到完整内容，可能在人名、称呼和源内容风格上产生解释漂移。

目标是在单次同步文字生成任务中提高源字幕可消费性与翻译一致性，同时保留并行、检查点、局部重试和恢复能力。当前不建立跨作品、长期维护或由翻译批次反向更新的记忆系统。

### 观察样本

真实 SRT 中曾观察到以下内容：

```srt
42
00:02:15,270 --> 00:02:16,870
ご視聴ありがとうございました。

26
00:01:46,140 --> 00:01:47,820
*Sigh*

25
00:01:39,330 --> 00:01:44,010
*Humming*
```

- `ご視聴ありがとうございました。` 是 Whisper 类模型常见的可疑固定短语之一，但仅凭字幕文本不能确认该次一定是 ASR 幻觉，仍需结合音频位置与上下文判断。
- `*Sigh*`、`*Humming*` 属于标记式发声或声音事件文本，不等同于源语言中的实际语气词。按照 VortexCue 的消费用途，这类标签可以从正式源字幕中移除。

## 2. 核心术语

| 名称 | 含义 |
| --- | --- |
| `Raw ASR Artifact` | ASR 的原始、不可覆盖产物，仅用于追溯、恢复和重新处理。 |
| `ASR Partial` | ASR 进行中已经发布的连续前缀，可临时展示，但不是翻译输入。 |
| `Source Preparation` | 从完整 Raw ASR 生成正式源字幕的内部处理阶段；产品名称尚未确定。 |
| `Preparation Scan` | Source Preparation 中使用专属 Prompt 的模型扫描。 |
| `Source Patch` | 对指定 cue 执行 `DROP` 或 `REPLACE` 的稀疏修改。 |
| `Published Source Timeline` | Source Patch 应用后的正式源字幕，供源语言观看和翻译共同使用。 |
| `SourceContext` | Preparation Scan 附带生成的紧凑、目标语言无关的源侧上下文。 |
| `Translation Policy` | 目标语言、忠实度、简洁度、字幕格式等固定产品翻译规则。 |
| `Target Timeline` | 独立翻译流程产生的目标语言字幕。 |

`Source Refinement` 可作为 Source Preparation 的能力描述，但“清洗”不足以覆盖听错修正与必要补全，因此不作为已确定的产品名称。

## 3. 已确认的架构边界

### 3.1 ASR 处理与翻译处理分离

```text
ASR processing
  Raw ASR Artifact + deterministic flags
  → Source Preparation
      ├─ sparse Source Patches
      └─ compact SourceContext
  → Published Source Timeline

Translation processing
  Published Source Timeline + SourceContext + Translation Policy
  → translation planning / batching
  → Target Timeline
```

- Source Preparation 不输出目标语言译文，也不依赖用户是否请求翻译。
- Translation processing 不修改 Published Source Timeline，也不继续判断 cue 是否真实存在。
- 纯源语言观看和双语观看使用同一份 Published Source Timeline。
- 重新翻译可以复用同一 source revision 的 Published Source Timeline 与 SourceContext，不重跑 ASR 处理。
- 任务大小只影响两个阶段内部的一次请求、切片或并发策略，不改变职责边界。

### 3.2 数据流保持单向

翻译批次读取同一份冻结 SourceContext，不反向修改它，也不各自维护术语记忆。批次执行顺序、重试顺序和恢复时机不应改变任务级上下文。

```text
Published Source Timeline + frozen SourceContext
→ parallel translation batches
→ strict batch validation
→ Target Timeline
```

### 3.3 用户不进入 Raw ASR 工作流

VortexCue 是消费产品，不提供面向用户的 Raw ASR 编辑或审计入口。Raw ASR 与最小 patch 记录作为内部 Artifact 保留；用户最终消费 Published Source Timeline。

## 4. Source Preparation

### 4.1 输入与产物

Preparation Scan 读取 Raw ASR 时间轴与代码产生的确定性 flags，一次完成两类工作：

1. 生成实际修正源字幕的稀疏 Source Patches。
2. 顺带提取无法安全写回源文本、但可能帮助多个翻译批次理解源内容的 SourceContext。

```text
Raw ASR + deterministic flags
→ Preparation Scan
   ├─ source_patches
   └─ source_context
→ apply patches
→ Published Source Timeline + frozen SourceContext
```

扫描不是“标记一批可疑行，再让翻译模型裁决”。它应在 Source Preparation 内完成当前消费场景所需的源文本决定。

### 4.2 稀疏 Patch 协议

当前协议只显式输出发生改变的 cue：

```yaml
source_patches:
  - cue_id: cue_42
    operation: DROP

  - cue_id: cue_108
    operation: REPLACE
    replacement_text: "..."
```

- 未出现在输出中的 cue 隐式保持不变，不逐条输出 `KEEP`。
- `DROP` 用于明确的 ASR 污染、产品不展示的标记式声音事件或其他明显非内容文本。
- `REPLACE` 用于在源语言内修正上下文足以确定的错误；它不翻译文本。
- `REPLACE` 保留原 cue ID、开始时间与结束时间，只替换其源语言文本。
- `DROP` 使该 cue 不进入 Published Source Timeline，但不移动或压缩媒体时间；原时间区间自然成为字幕 gap，Raw ASR 仍保留该 cue 及其时间信息。
- Source Preparation 不新增、拆分、合并 cue，也不承担文本重排。VortexCue 自己控制字幕布局与换行，不需要为外部播放器宽度生成另一套分段。
- 不向模型提供常规 `UNCERTAIN` 动作，也不要求自然语言解释、置信度或推理过程。
- 内部保存最小 patch 记录，包括 cue ID、操作、替换文本、policy version 和已有触发 flags，用于应用、失效、对照实验与问题追踪。
- Patch 不覆盖 Raw ASR Artifact。

### 4.3 污染模式库

系统维护小型、版本化的 Whisper 可疑污染短语与模式库，并由代码进行确定性匹配：

```text
Versioned pattern library
→ deterministic matcher
→ suspicion flags
→ Preparation Scan with timeline context
→ Source Patches
```

- 模式库是注意力先验和候选召回工具，不是自动删除黑名单，也不是翻译记忆。
- 模型结合前后 cue、时间位置、重复次数、主语言及现有 ASR 信息判断本次命中。
- 扫描可以发现库外异常，但运行时不自动把新候选写回模式库；新增模式经过样本复核与版本更新后进入。
- 已由产品规则确定不展示的标记式事件标签可以由代码直接处理；实际语言中的 `はぁー`、`嗯`、`啊` 等发声内容不能套用同一删除规则。

## 5. SourceContext

SourceContext 不是剧情摘要、自动术语库或可持续编辑的工作记忆。字段准入条件是：

> 信息无法安全写入 Published Source 文本，会影响翻译对源内容的解释，并可能被多个翻译批次复用。

第一版方向只保留四类紧凑信息：

```text
source_style_profile
entity_mentions
alias_clusters
addressing_functions
```

### 5.1 源内容风格

必须区分固定 Translation Policy 与扫描推断出的源内容风格：

- Translation Policy 规定目标语言、忠实度、简洁度、格式和协议，不由扫描修改。
- `source_style_profile` 描述内容模式、语域、礼貌层级、话语形态和需要保留的源侧特征。

结构应短而受限，例如：

```yaml
source_style_profile:
  content_mode: intimate_roleplay
  register: soft_casual
  politeness: mixed
  utterance_shape: fragmented_spoken
  preserve_traits: [hesitation, repetition, emotional_tone]
```

它告诉翻译器源内容是什么样，不替翻译器创作目标语言文风。

### 5.2 实体、别名与称呼

```yaml
entities:
  - entity_id: person_1
    kind: PERSON
    observed_forms: [ミサキ, みさき]
    spelling_status: unresolved

mentions:
  - cue_id: cue_108
    text: ミサキ
    entity_id: person_1

addressing:
  - cue_id: cue_205
    text: 先生
    function: VOCATIVE_TITLE
```

- 扫描能安全修正名字时，修正后的 Published Source 已经承载信息，不重复生成 hint。
- 无法确定正确写法、但能判断某段文本是人名时，可以标记其实体角色，避免被当成普通词。
- 疑似同一人物的多个表面形式可以归入 alias cluster，但这不代表系统知道官方角色名。
- 称呼标注描述源句中的功能，不指定目标语言译法。
- ASR 没有识别出可用线索时，扫描和翻译都不凭世界知识伪造官方名称。

### 5.3 明确排除的内容

以下内容默认不进入 SourceContext：

- 长篇剧情或内容摘要；
- 完整人物设定和世界观数据库；
- 普通局部上下文；
- 已经通过 patch 写入源文本的事实；
- 固定产品翻译规则；
- 官方目标语言人名或术语译法；
- 模型置信度、解释和推理过程；
- 逐 cue 的近似译文。

是否需要为实体生成目标语言渲染表，第一版默认不做；只有实测发现明显跨批漂移时再评估。

## 6. 翻译 Prompt 与模型分工

普通翻译 Prompt 应与真实编排一致，按以下职责分层：

```text
System
  翻译职责、输入边界、输出协议

Translation Policy
  目标语言与固定字幕规则

SourceContext
  当前 source revision 的冻结源侧上下文

Batch Payload
  当前待翻译 cue 与局部前后文
```

- 从普通翻译 Prompt 中移除 ASR 污染裁决、源 cue 删除、动态记忆维护和并不存在的人类术语维护流程。
- Preparation Scan 使用专属 Prompt，不复用普通翻译 Prompt。
- Preparation Scan 的价值主要来自提前看过完整源字幕和稳定执行结构化协议，不以模型识别主流作品知识为前提。
- 当前模型假设是 Preparation Scan 使用快速、低思考配置；实际翻译批次可以使用更高思考强度。两者最终应由真实样本确定，而不是只按直觉固定。

## 7. 展示与发布体验

为了让用户尽快看到同步文字，Source Preparation 不阻塞 ASR Partial 的临时展示：

```text
ASR Partial
→ 立即展示临时源字幕

ASR complete
→ Source Preparation
→ Published Source Timeline
→ 播放器无感替换临时字幕
```

- Partial 是处理期间的临时体验，不改变 Published Source Timeline 的正式边界。
- Translation processing 只读取完成 Source Preparation 的正式 source revision，不翻译 ASR Partial。
- 正式 revision 替换时应保持当前播放位置、选轨状态和字幕舞台稳定。
- 是否展示“正在优化”、采用局部还是整体 revision 切换，以及是否需要替换动画，暂不在本文确定。

## 8. 明确不采用的方向

以下路线不进入当前设计基线：

- 继续生搬 TransVortex 的动态术语维护和人类编辑工作记忆。
- 让翻译批次顺便判断 ASR 污染并修改源字幕。
- 翻译一批、更新记忆、再影响下一批的渐进式反馈循环。
- 因内容较短而把源修正和整篇翻译合并为一次 one-shot 请求。
- 建立宽泛的 `TranslationTaskBrief`，包含剧情摘要、完整人物表或大量逐行提示。
- 把污染关键词库作为命中即删除的黑名单。
- 向消费者暴露 Raw ASR、模型不确定标注、扫描 attempt 或独立的“重试扫描”操作。

## 9. 与现有内核约束的关系

后续实现必须继续遵守 `GUIDE.md` 已有边界：

- Timeline 与生成结果使用 immutable revision 和 typed Artifact。
- 任务按已提交 Artifact 推进，Partial 与 Final 的语义不能混淆。
- 翻译结果固定其 source revision/hash；正式源 revision 变化时不得继续复用旧译文。
- 重试与恢复只处理尚未提交的工作单元，不能静默重复付费请求。
- 用户界面不暴露 Scheme、Attempt、Token、Hash 或内部任务编排术语。

Source Patch、SourceContext、Preparation policy version 及其失效关系需要在该模型内表达，而不是另建一套可变旁路状态。

## 10. 待验证与待决定

### 10.0 v1 已落地的工程取值

- Source Preparation 是所有 ASR 来源任务的固定内部阶段，不增加用户开关；导入字幕与已有 source revision 不重复扫描。
- attempt 在提交时冻结 preparation policy version 与 prompt hash；checkpoint 同时绑定完整 Raw ASR 的 canonical hash。
- 配置完整的翻译模型时执行一次全量扫描，固定使用独立于翻译配置的 `Low` 思考档位；只有提供商明确拒绝 reasoning/thinking 控制参数时才省略参数兼容重发一次，超时、空输出和普通模型错误不重发；当前不分段汇总，也不向用户暴露思考档位或单独重试。
- 没有文本模型时只执行确定性事件标签清理并记录 `SkippedNoModel`；请求、协议或输出校验失败时记录 `DegradedError`，以确定性结果继续发布，不把 Raw ASR 作为新的用户入口。
- 模型输出采用严格 JSON、稀疏 `DROP` / `REPLACE` 和受限 `SourceContext`；未知字段、未知 cue、重复 patch、过量 patch、越权修改 policy-drop cue 或空最终时间轴均被拒绝。
- 翻译 policy 已升级为 v3，删除动态术语维护、ASR 修复、压缩和 reflow 职责，并以通用规则自然化简中译文中的源侧表达性标记；每个并发批次只读同一份冻结 `SourceContext`。
- ASR Partial 始终保持 Partial；只有 Source Preparation 完成或降级落盘后才发布 Final source，并保留 ASR 窗口与 usage 元数据。

以下小节保留为后续真实样本与产品体验实验项，不阻塞 v1 内核。

### 10.1 Source Preparation 权限

- 默认启用的收益能否覆盖误改率、时延和模型成本。
- `REPLACE` 只修正明显听错，还是允许上下文可确定的缺字补全。
- `DROP` 的证据标准，以及标记式事件标签与真实语气词的边界。

### 10.2 扫描规模与协议

- 长时间轴采用单次全量请求，还是分段提取后再汇总。
- SourceContext 的最终 schema、枚举、字段上限和总长度限制。
- Preparation Scan 的模型选择、思考强度、输出预算和结构化校验方式。

### 10.3 失败、重试与配置

- Source Preparation 失败时发布原始字幕、显示降级状态，还是让生成任务失败。
- SourceContext 生成失败时是否允许无上下文翻译。
- Source Preparation 复用现有翻译模型配置，还是使用独立的文本模型配置。
- Artifact 按 source revision、pattern/policy version 和模型协议如何复用与失效。

### 10.4 播放中的 revision 切换

- 如何保持 cue 身份与当前字幕连续性。
- 正式源字幕采用整体发布还是允许局部 refinement revision。
- App 进程恢复、暂停和任务重试期间如何识别临时与正式源字幕。

### 10.5 真实样本实验

至少需要对照验证：

- 固定污染短语的召回率与误删率；
- 标记式声音事件和真实语气词的区分；
- `REPLACE` 的正确修复率与误改率；
- SourceContext 对跨批人名、称呼和风格一致性的实际提升；
- Preparation Scan 额外输入成本、时延与模型思考强度的关系；
- 无 SourceContext、精简 SourceContext 和宽泛 Brief 三组翻译结果的差异。

首轮日语真实样本中，Gemini 3.7 Flash（Low）在完整 59 cue 扫描里删除了 5 条片尾式感谢污染，并把共同出现的 `吐息`、`トイキ` 和 `トイキ達` 聚为同一实体。样本所有者随后确认 `吐息` 确为角色本名，因此这里不是 ASR 把普通名词误作名字：至少在 `吐息` 这一处 ASR 原文正确，跨汉字与片假名表面形式的 alias 发现也得到真实语义验证。该结论不等于确认模型对同一批次的每一处源修复都正确。

同一份 59 cue 与同一 prompt hash 的 OpenRouter 对照中，GPT-5.6 Luna（Low）使用 736 reasoning tokens，但编造了不存在的 evidence cue id，并误改 `吐息`，整份输出被严格校验拒绝；Luna（Medium）使用 970 reasoning tokens，通过校验且转为保守清理，但没有识别 `吐息 / トイキ` 关系。GPT-5.6 Terra（High）使用 2,932 reasoning tokens，在约 38 秒内通过校验并正确建立该 alias，同时完成污染删除与关键句修复。三个档位不能单独证明模型能力排序，但 Luna 的 Low / Medium 与 Terra 的 High 都呈现了可观察的推理投入和行为差异。

相较之下，DeepSeek v4 Flash 在明确请求 Low 时仍产生约 15,000 reasoning tokens，耗时和语义结果也明显异常。当前只把它记录为该模型版本或服务端 effort 映射的兼容性异常，不据此否定 Preparation 使用显式思考档位的总体设计，也不假定 Low 是跨提供商可比较的绝对预算。

同次翻译把源侧表达性标记处理成了不完整的括号式 `（笑`。翻译 policy v3 因此增加目标语言无关于具体标记和模型名称的通用约束：自然化非词汇语气标记、网络速记和表达性符号，避免重复表达情绪，不新增括号式说明，并保证标点完整。具体标记只进入回归样例，不写成生产 Prompt 的专项规则。

### 10.6 可选 Work Reference

后续应允许 Source Preparation 消费一份与 Work 关联、版本化且只读的 `Work Reference`。DLsite 等作品包附带的作品介绍是首个明确场景，但能力不绑定特定商店，也不在当前 v1 范围内实现。

候选资料包括作品标题、作者或社团、角色名及读音、角色关系、章节或音轨标题和简短背景。它只为实体、别名、关系和源文本修复提供证据；对话文风仍从实际时间轴判断，营销介绍不得直接规定翻译语气。资料内容始终作为数据而不是 Prompt 指令，也不演化为跨任务可变记忆。

进入实现前需要单独设计：本地作品包中的文件发现与格式范围、用户授权、资料来源与可信度、Work 关联方式、用户覆盖、reference snapshot、变更失效，以及与 Source Preparation checkpoint 的复用关系。当前不承诺自动联网抓取，也不因这项后续能力扩大媒体文件搜索范围。

## 11. 后续工作入口

进入实现前，优先完成以下设计产物：

1. Source Patch 与 SourceContext 的最小结构化协议。
2. Preparation Scan 专属 Prompt 和普通翻译 Prompt 的职责删改稿。
3. 覆盖污染、事件标签、人名、称呼和口语风格的真实 SRT 对照集。
4. 单次全量扫描与分段汇总的成本、时延和准确性实验。

完成上述验证后，再决定默认启用策略、失败降级方式和具体内核迁移顺序。
