# VortexCue ASR 与翻译 V1 规格

> 状态：本轮方案记录，供 ASR / 翻译实现使用。
>
> 本文是独立的方案文档。它不修改 `docs/GUIDE.md`，也不试图覆盖仓库中其他历史研究、验收或里程碑记录。本文只记录本轮确定的轻量实现边界。

## 1. 决策摘要

V1 采用以下路线：

```text
Android 本地媒体
  -> 原生音频提取与规范化
  -> OpenRouter 云 ASR
  -> 带时间轴的源字幕段
  -> 用户配置的 LLM 翻译接口
  -> 目标语言字幕轨
  -> VortexCue 播放与 Glow 展示
```

产品不依赖 TransVortex 运行，不实现 TransVortex 的桌面 UI、服务进程或完整供应商目录。TransVortex 只作为已经验证过的工程经验来源：

- 固定窗口与 overlap 的 ASR 处理；
- 上传区间与结果归属区间分离的窗口计划；
- 稳定的 segment ID 与绝对时间轴；
- 分批翻译、检查点和断点恢复；
- 版本化的翻译 system/style prompt 与只读前后文协议；
- 严格响应校验、一次格式恢复和有界坏行补回；
- 可恢复、可审计的任务工件；
- ASR 与翻译传输层分离。

V1 不做特殊厂商业务分支。翻译侧识别的是协议，不是厂商；ASR 侧只开放经过验证的 OpenRouter profile。Prompt 对用户保持内部实现细节，但必须作为可版本化、可快照、可测试的 `TranslationPolicy` 资产管理，不能散落为不可追踪的代码字符串。

## 2. 产品边界

### 2.1 V1 必须支持

1. 从 Android 本地音频或视频中提取音频。
2. 使用用户自己的 OpenRouter API key 调用云 ASR。
3. 在 OpenRouter ASR 中选择已验证的 Whisper 或 Grok profile。
4. 将 ASR 结果归一化为带绝对时间的源字幕段。
5. 使用用户自己的 LLM API key 调用翻译协议。
6. 生成并持久化目标语言字幕轨。
7. 对 ASR 窗口和翻译批次保存进度，支持失败后继续。
8. 在设置中进行一次最小请求测试，并显示可解释的成功或失败结果。
9. 使用有界的本地临时空间处理长媒体，不把整部规范化 PCM 或完整 base64 请求常驻内存。
10. 使用内置、版本化的翻译 policy，并在任务中保存可复现的 policy 快照。

### 2.2 V1 明确不做

- 应用代付 token、内置额度、代理转发或统一计费；
- TransVortex 作为运行时依赖；
- 本地 Whisper、FunASR 或其他本地 ASR；
- 把本地 VAD 或 ML 语音活动检测作为首发依赖；
- 删除、压缩、拼接静音，或以任何方式改变原媒体时间轴；
- 把低能量边界优化作为 V1 正确性的前提；
- ffmpeg 依赖；
- 自动开放 OpenRouter 模型目录中的任意 ASR 模型；
- 翻译厂商专用业务逻辑或厂商自动识别；
- 自定义额外请求体、自定义 Header、厂商私有参数；
- 用户可调的 temperature、top-p、top-k、reasoning/fast 等高级参数；
- 用户自由编辑 system、repair、recovery 或 memory prompt；
- 把 raw prompt 文件路径、自定义 prompt 模板或 prompt DSL 暴露到消费者 UI；
- 流式翻译 UI；
- 在手机上提供字幕精修工作台；
- 为了“修得更好”而对每条字幕进行人工式重写。

## 3. 翻译 Provider 配置

### 3.1 配置模型

翻译连接是一个本地保存的 provider profile：

```yaml
protocol: openai_chat
endpoint: https://api.example.com/v1
model: example-model
api_key: <stored-secret-reference>
```

用户界面只需要暴露以下字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| 协议 | 是 | 选择请求和响应契约 |
| Endpoint | 是 | Base URL 或协议入口地址 |
| Model | 是 | 模型 ID，可拉取或手动输入 |
| API key | 是 | 以脱敏形式显示，安全存储 |

`endpoint` 下拉框只提供常用地址模板，不限制用户输入。模板是便利功能，不是厂商注册表，也不产生厂商分支。用户可以始终选择“自定义”并手动填写地址。

Provider profile 只描述连接、协议和模型，不保存 prompt 正文。翻译行为由独立的 `TranslationPolicy` 决定，避免同一套字幕策略被复制到每个 Endpoint 配置中。

### 3.2 支持的协议

协议适配器的初始集合为：

```text
openai_chat
openai_responses
anthropic_messages
google_generate_content
```

适配器负责：

1. 规范化 Endpoint 和请求路径；
2. 使用协议规定的认证方式发送 API key；
3. 构造最小非流式请求；
4. 从协议响应中提取文本；
5. 将 HTTP、认证、限流和协议错误转换为统一错误类型。

适配器不得根据厂商名称、模型前缀或 URL 猜测另一套协议。用户选择协议；URL 只决定请求目标。

初始请求/响应契约：

| 协议 | 请求入口 | 主要响应文本 |
| --- | --- | --- |
| `openai_chat` | Chat Completions | `choices[0].message.content` |
| `openai_responses` | Responses | `output_text` 或协议等价文本节点 |
| `anthropic_messages` | Messages | `content[]` 中的文本块 |
| `google_generate_content` | `generateContent` | candidates/content/parts 中的文本 |

响应解析必须严格失败关闭：无法找到非空文本时，任务失败并保留状态，不把整段原始 JSON 当作译文显示。

### 3.3 常用 Endpoint 模板

模板可以包含以下常见入口，实际地址和模型 ID 以用户及上游文档为准：

```text
OpenAI Chat / Responses
DeepSeek
Kimi
OpenRouter
Google Gemini OpenAI-compatible
Anthropic Messages
自定义
```

模板中的名称只用于选择体验。代码仍然按协议运行，不为 DeepSeek、Kimi、Claude 或 Gemini 增加独立分支。

### 3.4 模型列表

模型拉取是可选便利能力，不是配置前置条件：

- 如果协议和 Endpoint 提供可用的模型列表接口，设置页可以读取并展示模型 ID；
- 列表请求失败、网关不支持、权限不足或返回结构未知时，不阻止保存连接；
- 用户可以直接手动填写模型 ID；
- 应用只保存用户选定的模型 ID，不把上游完整目录复制进产品数据库；
- 不因为一次列表请求没有返回已保存模型，就自动删除该模型。

### 3.5 请求测试

每个翻译 Provider profile 提供“测试请求”：

1. 使用当前协议、Endpoint、模型和 key；
2. 发送一个最小、非流式、低输出量请求；
3. 只验证连通性、认证、模型可用性和响应解析；
4. 不保存测试文本，不记录 API key 或完整 prompt；
5. 明确提示请求可能产生少量上游费用；
6. 展示状态码、统一错误类型和简短诊断，不展示敏感请求头。

测试请求使用正式翻译的同一适配器，避免“测试能通、正式请求走另一条代码路径”。

### 3.6 不支持的高级参数

V1 不向用户开放以下参数：

```text
temperature
top_p
top_k
reasoning / fast / thinking 档位
max_tokens 的自由调整
自定义 body 字段
自定义 Header
厂商路由、fallback、排序和 provider options
自定义 system / repair / recovery prompt
自由文本 style prompt
```

适配器可以使用协议所需的固定安全默认值，但这些默认值不是用户配置契约。需要额外 Header、特殊签名、项目 ID 或私有字段的网关不属于 V1 支持范围。后续若增加翻译风格选择，应提供版本化 preset，而不是允许用户覆盖机器可解析的系统协议。

## 4. API Key 与隐私

### 4.1 BYOK 定义

BYOK 在本产品中只表示：

```text
用户提供自己的 API key
应用直接向用户填写的 Endpoint 发起请求
应用不出售 token、不代付、不做代理计费
```

ASR key 和翻译 key 是两个逻辑连接。用户可以为它们填写同一份实际 key，但应用不假设二者相同。

### 4.2 存储与显示

- API key 使用 Android Keystore 保护的本地加密存储；
- 设置界面默认只显示脱敏值；
- key 不写入普通配置文件、日志、崩溃信息、任务导出物或字幕文件；
- 删除连接时删除对应凭据引用；
- 测试和正式请求都不得把 key 拼入 URL；
- 请求失败时只保存脱敏后的 Endpoint、协议和模型信息。

用户填写自定义 Endpoint 即表示同意把 key 和媒体/字幕请求发送到该地址。远程 Endpoint 默认要求 HTTPS；不安全地址不作为常规模板提供。

## 5. OpenRouter ASR

### 5.1 连接边界

ASR V1 只支持 OpenRouter 的云 STT 入口：

```text
Endpoint: https://openrouter.ai/api/v1/audio/transcriptions
认证: Authorization: Bearer <OpenRouter API key>
```

应用不在 ASR 设置中提供任意厂商 URL，也不把翻译协议列表复用到 ASR。

### 5.2 已验证的 ASR profile

初始 profile：

| Profile | 模型 ID | 时间轴契约 | 备注 |
| --- | --- | --- | --- |
| `openrouter_whisper` | `openai/whisper-large-v3` | 必须有有效 `segments` 时间戳 | 直接使用模型返回的 segment 时间轴 |
| `openrouter_grok` | `x-ai/grok-stt-1.0` | 必须有有效 `words` 时间戳 | 先合并重叠词，再生成统一字幕段 |

这里的 profile 是请求和响应契约，不只是模型下拉框：

- Whisper 使用该 profile 固定的响应格式和 segment timestamp 请求；
- Grok 使用该 profile 固定的 word timestamp 请求，并按词时间合并窗口 overlap；
- profile 固定音频格式、传输方式、窗口目标、hard max、编码字节上限和 planner 版本；
- 缺少 profile 所要求的时间信息时明确失败；
- 不按上传窗口起止时间伪造字幕时间轴；
- 新增 OpenRouter transcription 模型前，必须先验证请求字段、响应结构和真实时间轴语义，再加入 profile。

模型列表可以显示这两个已验证 profile，但不自动把 OpenRouter 目录中的其他 transcription 模型开放为可选项。

### 5.3 ASR 请求字段

V1 只发送 profile 必需字段：

```json
{
  "model": "openai/whisper-large-v3",
  "input_audio": {
    "data": "<base64 audio bytes>",
    "format": "wav"
  },
  "language": "<optional ISO-639-1>",
  "response_format": "<profile fixed value>"
}
```

真实请求使用 JSON base64 传输。实现必须按 base64 后的大小执行请求上限，不得先构造包含完整音频的大字符串；应使用流式请求体或有界临时文件。

[OpenRouter STT](https://openrouter.ai/docs/guides/overview/multimodal/stt) 当前也支持 multipart，但 V1 不在 JSON 与 multipart 之间自动切换。传输方式属于已验证 profile 契约；后续若采用 multipart，应新增或升级 profile 并重新验证模型字段、时间戳和请求上限，而不是在网络层静默切换。V1 也不提供 OpenRouter provider routing、排序、fallback 或私有 options 的 UI。

### 5.4 分窗与边界契约

ASR 不使用 VAD 作为正确性前提。V1 使用已验证的固定窗口经验，但参数属于 profile 快照：

```text
boundary planner: fixed_v1
target upload duration: 300 seconds
hard max upload duration: 300 seconds
upload overlap duration: 3 seconds
max encoded bytes: profile/transport validated value
```

`300 秒` 是初始 profile 的经验默认值，不是所有 provider、模型和传输格式共享的产品常量。实际 planner 必须同时满足编码后请求体大小、上游处理超时和设备资源限制；在开始上传前已经能确定超限时，应缩小窗口而不是等待请求失败。

#### 5.4.1 窗口计划

每个 ASR 窗口持久化以下计划：

```text
ChunkPlan
  owned_start_ms
  owned_end_ms
  upload_start_ms
  upload_end_ms
  pcm_start_frame
  pcm_end_frame
  cut_reason
  planner_version
```

- `owned` 是该窗口对 ASR 结果的逻辑归属范围；相邻区间必须首尾相接、无空洞且不重叠；
- `upload` 是实际发送给模型的音频范围；相邻区间允许 overlap；
- `pcm_*_frame` 固定本次计划对应的规范化 PCM 整数范围，不能依赖浮点秒数重新推导；
- `cut_reason` 至少区分固定边界、资源约束拆分和失败重试拆分；
- `planner_version` 与 profile 快照一起保存，恢复任务时不得用新算法静默重算已持久化窗口。

`fixed_v1` 先生成固定的重叠上传窗口。相邻上传窗口的 overlap 中点作为逻辑所有权边界，首窗口从媒体起点开始，末窗口覆盖到媒体终点。例如上传窗口 `[0, 300]` 与 `[297, 597]` 的 overlap 是 `[297, 300]`，逻辑边界为 `298.5` 秒。

ASR 返回的窗口内时间先加 `upload_start_ms` 转换为绝对时间。word/segment 的中点落在哪个 `owned` 区间，就以该窗口为主要来源；时间和文本相似度只负责调和 provider 在边缘处的分段差异，不能重新定义基本覆盖范围，也不能伪造时间戳。

#### 5.4.2 可扩展的软边界

后续可以增加带版本的低能量边界 planner，但不改变上述数据契约：

- 只在固定目标附近移动逻辑边界；
- 上传窗口在边界两侧继续保留上下文；
- 找不到可信候选、时间映射异常或资源上限冲突时回退固定边界；
- 不删除样本、不拼接静音、不修改原媒体时间轴；
- WebRTC/Silero 等 VAD 即使以后参与，也只能提供附加评分，不能成为窗口推进条件。

启用向固定目标之后搜索的 profile，必须让 `target upload duration` 小于 `hard max upload duration`，为前视和边界上下文预留空间。

具体算法和 Android 单遍管线见 `docs/ANDROID_AUDIO_PIPELINE_RESEARCH.md`。软边界不属于 V1 最小验收条件，启用前必须先以 shadow 模式通过 ASMR/耳语 fixture 和真实设备验证。

#### 5.4.3 失败处理

1. 可重试的 timeout、429、5xx 使用有界重试；
2. 单个窗口持续失败时，可以将其 `owned` 区间拆成更小的子窗口再重试；子区间必须精确分割父区间，上传范围可以重新加入 overlap；
3. 每次拆分决定先写入任务检查点；
4. 取消或进程中断后，从最后一个未完成窗口继续；
5. 不无限重试，也不静默丢弃窗口。

### 5.5 ASR 统一输出

两个 profile 最终都转换为相同的内部段：

```text
segment_id
start_ms
end_ms
text_src
language?
words?
confidence?
asr_risk?
source_profile
raw_reference
```

`start_ms` 和 `end_ms` 必须是相对于整段媒体的绝对时间。窗口内时间先加 `upload_start_ms`，再按 `owned` 区间确定主要来源并处理 overlap。

统一层负责：

- 规范化空白和明显非法时间；
- 按 `ChunkPlan` 归属并去除重叠上传产生的重复词或重复段；
- 保留可验证的 confidence 和结构化 ASR 风险，不把模型猜测伪装成确定事实；
- 保留原始响应引用和诊断；
- 输出稳定、单调、可供翻译使用的 segment ID。

统一层不负责语义精修，不重写模型原文。

## 6. TranslationPolicy 与翻译流水线

### 6.1 TransVortex 能力迁移边界

VortexCue 不依赖 TransVortex 进程或文件目录运行。实现时在选定 revision 上迁移经过验证的 prompt 资产、请求构造规则和 fixture，并由 VortexCue 自己维护版本。迁移范围如下：

| TransVortex 能力 | V1 决定 | VortexCue 落点 |
| --- | --- | --- |
| 翻译 system prompt 与简体中文 style prompt | 迁移 | 打包为只读、带哈希的 prompt bundle |
| `CONTEXT_BEFORE / TRANSLATE_ONLY / CONTEXT_AFTER` | 迁移 | 只翻译目标 ID，前后文只读 |
| ID/内容校验、格式恢复、少量坏行 repair | 迁移精简版 | 由代码验证和有界状态机执行 |
| 容量感知分批和硬容量错误拆分 | 迁移原则 | 使用 policy 内部保守预算，超限时局部拆分 |
| ASR 风险提示 | 保留结构入口 | 只有 source segment 已带结构化风险时才注入，不让模型凭空纠错 |
| memory bootstrap/inject/patch | V1 延后 | policy 固定为 `none_v1`，保留结构化扩展位 |
| compression、reflow、完整质量工作台 | V1 延后 | 不执行有效译文的语义重写 |
| 多模型审校和 provider fallback 图 | V1 延后 | 单批次按用户选定连接执行 |
| raw prompt 文件覆盖 | 不迁移到消费者 UI | 后续只考虑受控 style preset 或结构化术语 |

迁移不是复制一段 system prompt。TransVortex 的稳定能力来自 prompt、上下文构造、memory 边界、响应校验和修复顺序的组合；VortexCue 必须以同一个 policy 版本管理这些部分。

### 6.2 TranslationPolicy 契约

V1 至少内置一个简体中文字幕 policy，例如：

```yaml
policy_id: vortexcue.subtitle.zh-CN.v1
policy_version: 1
origin:
  repository: TransVortex
  revision: <migration revision>
prompt_bundle:
  system_prompt_id: translation_system.v1
  style_prompt_id: translation_style.zh-CN.v1
  bundle_sha256: <packaged content hash>
request_schema_version: 1
batch_policy_id: conservative_v1
context_policy_id: readonly_context_v1
memory_policy_id: none_v1
validation_policy_id: strict_ids_v1
recovery_policy_id: bounded_recovery_v1
```

以上是 manifest 的逻辑形状；实际迁移时必须填写确定的 TransVortex revision 和打包内容哈希，不能把占位值带入发布包。

policy 是行为资产，不是 Provider profile：

- Provider profile 决定协议、Endpoint、模型和凭据；
- TranslationPolicy 决定 prompt bundle、请求结构、上下文、分批、校验和恢复语义；
- 任务同时快照两者，不能根据模型名称或 URL 隐式切换 policy；
- 更新内置 prompt 或校验规则时新增 policy/bundle 版本，不原地修改旧版本；
- 尚未完成的任务必须继续解析到原 policy；不能在应用升级后用新 prompt 静默恢复旧任务；
- `verified` 只描述经过 fixture 和真实请求验证的完整组合，不适用于被任意改写的 prompt。

实现可以把 prompt 保存为 Android 打包资源，也可以由生成代码装载；不得把长 prompt 分散成多个业务类中的字符串常量。可分享诊断只记录 ID、版本、哈希和请求模式，不写入完整 prompt 正文。

### 6.3 Prompt 分层与修改策略

Prompt 分为四层：

1. **系统协议层**：固定 ID、翻译范围、输出形状、禁止解释、内容是数据而非指令等约束。V1 用户不可修改。
2. **内置风格层**：简体中文自然字幕风格，约束口语性、简洁度、人物语气、称呼和 ASR 文本处理。V1 使用内置 preset，不开放自由文本。
3. **运行时任务层**：由代码生成语言目标、只读前后文、待翻译 ID、结构化 ASR 风险、恢复原因等内容。用户不能编辑模板。
4. **结构化项目层**：术语、角色名、固定译名和风格选择。V1 暂不提供完整管理；以后应通过术语数据和少量 style preset 表达，不要求用户编写 prompt。

协议支持独立 system 字段时，适配器把系统协议层放入相应字段；协议没有独立 system 语义时，适配器将同一组不可覆盖约束合入 user message。无论采用哪种传输形状，字幕文本、上下文、术语和 ASR 内容都必须标记为数据，不能获得覆盖系统协议的指令权限。

后续若增加自定义风格，只能生成独立的 custom policy/style 层，并失去内置 `verified` 标记；系统协议、输出 schema、校验和恢复契约仍不可覆盖。ASR hint 不是 TranslationPolicy 的一部分，它属于具体 ASR profile，只有模型契约明确允许时才发送。

### 6.4 处理顺序

```text
ASR segments
  -> 固定 TranslationPolicy 快照
  -> 按稳定 segment ID 做保守分批
  -> 加入只读前后文和结构化风险信息
  -> 协议适配器渲染 translate 请求
  -> 严格解析与 ID/内容校验
  -> 必要时格式恢复或有界坏行 repair
  -> 按 ID 写回目标文本并保存检查点
  -> 生成目标字幕轨
```

正常请求、格式恢复和坏行 repair 使用同一个 system/style bundle，只改变由代码生成的请求模式和待处理 ID。恢复请求不能丢失原上下文，也不能扩大为整片润色。

### 6.5 请求与响应契约

每个批次都携带稳定 ID，不依靠模型返回顺序猜测对应关系。归一化请求的逻辑结构为：

```json
{
  "context_before": [
    {"id": "s000121", "text": "..."}
  ],
  "translate_only": [
    {"id": "s000123", "start_ms": 12000, "end_ms": 14500, "text": "..."}
  ],
  "context_after": [
    {"id": "s000125", "text": "..."}
  ],
  "source_language": "ja",
  "target_language": "zh-CN"
}
```

这不是直接发送给所有协议的统一 JSON body，而是适配器输入。各协议把同一语义渲染为自己的 system/user/message 结构。

模型只能返回 `translate_only` 中的 ID。应用校验：

- ID 集合完整且无重复；
- 不出现未知 ID 或只读 context ID；
- 译文非空，且没有解释性前后缀或明显拒绝文本；
- 文本长度、未翻译比例和格式没有极端异常；
- 时间轴只能来自源 segment，模型不能修改时间；
- prompt-like 字幕内容仍被当作待翻译数据，不能改变输出协议。

恢复顺序固定为：

1. 对同一批次执行一次轻量格式恢复，保留相同 source、context 和 style；
2. 若只有少量缺失或非法 ID，对这些 ID 执行一次有界 repair；
3. 明确的容量错误只拆分当前批次，不改变已经成功的批次；
4. 仍失败时保留诊断并暴露错误，不伪造译文。

坏行数量上限、repair 次数和容量下限属于 `recovery_policy_id`，不作为用户参数。语义正确但措辞普通的译文不触发 repair；V1 不做二次润色、回译或逐句质量评分。

### 6.6 进度与恢复

任务至少记录：

- 使用的 ASR profile、模型和请求配置快照；
- 每个 ASR 窗口的 `ChunkPlan`、完成状态和响应引用；
- 翻译 Provider profile 快照；
- `policy_id`、`policy_version`、origin revision、prompt bundle hash、请求 schema 和各子策略 ID；
- 每个翻译批次的计划、请求模式、完成状态和修复状态；
- 已生成源段和目标段的稳定 ID；
- 可重试错误的次数和最后原因；
- 取消、恢复和最终失败状态。

恢复时只提交未完成的窗口或批次，不重复提交已经确认成功的结果。若原 policy 资源缺失或哈希不匹配，任务明确失败为不可复现配置，不能自动换用最新 prompt。key、完整请求体、完整响应正文和字幕内容不进入可分享的任务报告。

### 6.7 TransVortex 核验位置

迁移实现时至少对照相邻 TransVortex 仓库的以下资产：

- `prompts/translation/system.v1.md`：稳定输出协议、翻译边界和内容安全约束；
- `prompts/translation/style.zh-CN.v1.md`：简体中文自然字幕风格；
- `prompts/memory/bootstrap_system.v1.md` 与 `patch_system.v1.md`：V1 延后能力的边界参考；
- `src/transvortex/prompts/registry.py`：文件资产、代码 fallback 和 override 解析；
- `src/transvortex/providers/factory.py`：normal、repair、compression、reflow 等请求模式；
- `src/transvortex/core/translate.py`：上下文预算、校验恢复、坏行 repair 和容量拆分；
- `tests/test_prompts.py`、`test_translation_validation.py`、`test_translate_repair.py`：需要转写为 VortexCue fixture 的核心行为；
- `src/transvortex/experiments/translation_matrix.py` 与 `tests/test_translation_matrix.py`：模型、容量、reasoning 和 memory 条件的对照实验与汇总契约；
- `src/transvortex/experiments/memory_injection_translation_matrix.py`：V1 延后 memory 能力的实验基线。

VortexCue 只迁移选定 revision 的内容与行为 fixture，不在运行时读取相邻仓库，也不因 TransVortex 后续修改而自动改变已发布 policy。

## 7. Android 媒体处理

V1 不依赖 ffmpeg。媒体处理使用 Android 平台能力：

```text
Content URI
  -> MediaExtractorCompat / MediaExtractor 探测
  -> MediaCodec 解码
  -> 应用内 PCM 规范化
  -> 有界分块 spool
  -> ChunkPlanner
  -> 流式 WAV 请求与上传
```

实现必须先声明实际支持的输入格式和设备限制，不把“Android 能播放”自动等同于“Android 能稳定解码并上传给 ASR”。无法提取音频时给出明确错误，不能把空音频提交给云端。

`MediaExtractorCompat` 是采用 Media3 提取语义时的优先选项；若 V1 不增加 `media3-inspector`，可以使用平台 `MediaExtractor`。两条路径都必须通过同一组格式 fixture 和真机矩阵，编码 sample 仍由 `MediaCodec` 解码。

长媒体采用单遍、有界处理：

1. 解码 PCM，并按原始 `presentationTimeUs` 维护 source time 到规范化 frame 的映射；
2. downmix/resample 为 16 kHz、单声道、S16 PCM，写入固定大小的临时块；
3. 只保留当前上传窗口、overlap、必要前视和有界待上传队列所需的块；
4. 根据 `ChunkPlan` 的 frame 范围生成 WAV 请求；
5. 窗口成功并写入检查点后，删除早于下一 `upload_start_ms` 的临时块；
6. 网络慢于解码时暂停生产，不能让临时文件随媒体总时长无界增长。

V1 不要求实例化 `BoundaryAnalyzer`。为后续软边界保留的观察点必须位于多声道 PCM downmix 之前，按声道计算特征，避免相位抵消制造假低能量区。

音频规范化属于本地临时工件：

- 不修改用户原始媒体；
- 不把整部规范化音频或完整 base64 请求常驻内存，也不长期复制到公共存储；
- 任务取消或清理后删除临时 PCM 块和未完成分片；
- 生成字幕只引用原媒体内容 ID 和任务结果。

## 8. 错误分类

错误面向用户保持少量、明确的类别：

| 类别 | 示例 | 处理 |
| --- | --- | --- |
| 配置错误 | 缺少协议、Endpoint、模型或 key | 保存前阻止，并指出字段 |
| 认证错误 | 401、403、无效 key | 停止当前请求，提示检查 key |
| 模型/协议错误 | 模型不存在、响应无法解析 | 停止当前批次并保留诊断 |
| 策略错误 | policy 资源缺失、版本未知或 prompt 哈希不匹配 | 明确失败，不自动换用最新 policy |
| 限流/暂时故障 | 429、5xx、超时 | 有界重试，必要时细分窗口 |
| 时间轴错误 | ASR 没有 profile 要求的 timestamps | 明确失败，不生成伪字幕 |
| 媒体错误 | 无法解码或格式不支持 | 在上传前失败 |
| 用户取消 | 主动停止任务 | 保存已完成结果，可继续 |

日志和诊断可以保留 HTTP 状态、请求 ID、profile、policy ID/版本/hash、请求模式、模型和耗时，但必须脱敏 key、Authorization、完整音频、字幕正文和完整 prompt。

## 9. 最小验收标准

### Provider 设置

- 可以新增、编辑、删除翻译 Provider profile；
- Endpoint 可以从模板选择，也可以手动填写；
- 模型列表请求失败时仍可手动填写模型；
- API key 显示脱敏，重启后仍可使用；
- 测试请求使用正式适配器并能区分认证、模型和响应解析错误；
- 不存在高级参数或 raw prompt 编辑入口。

### ASR

- OpenRouter key 可以完成 Whisper 短音频真实请求；
- Whisper 缺少 segments 时明确失败；
- Grok 缺少 words 时明确失败；
- `fixed_v1` 使用初始 profile 的 300 秒上传目标与 3 秒 overlap 生成确定性 `ChunkPlan`；
- 所有 `owned` 区间精确覆盖完整媒体时间轴，所有上传窗口满足 profile 的时长和编码字节上限；
- 窗口内时间通过 `upload_start_ms` 转为正确的绝对时间，overlap 中点归属不会产生明显重复字幕；
- timeout 拆分后的子 `owned` 区间精确分割父区间，不丢失或重复所有权；
- 中断后可从未完成窗口继续。
- 长媒体处理时临时 PCM、待上传队列和内存占用保持有界，取消后临时块可清理。

### 翻译

- 至少一个 OpenAI-compatible Chat Endpoint 完成真实翻译；
- 四种协议各自通过最小 mock 响应解析测试；
- 内置 policy 的 prompt bundle 与 manifest 哈希一致，任务快照保存 policy 版本和所有子策略 ID；
- `context_before` 和 `context_after` 只影响理解，不会出现在待回填 ID 中；
- 字幕正文中包含 prompt-like 指令时仍按数据翻译，不会改变输出契约；
- 返回缺行、重复 ID、未知 ID、context ID 或空译文时不会静默生成错误字幕；
- 格式恢复最多执行一次；少量坏行 repair 只处理失败 ID，并保留原 context/style；
- 已持久化任务在新 policy 发布后仍使用原版本；原资源缺失或哈希不匹配时明确失败；
- 翻译失败后已完成批次保留，恢复不会重复提交已完成批次；
- 目标字幕与源字幕共享同一时间轴。

### 播放

- 生成轨可以作为 VortexCue 的目标语言字幕显示；
- 任务结束后不需要用户导入 SRT 才能播放；
- 退出、重启和再次打开内容后，已生成轨和播放位置仍可恢复。

## 10. 实施顺序

1. 建立本地安全凭据存储和翻译 Provider profile 数据模型。
2. 迁移选定 TransVortex revision 的 prompt/fixture，建立 `TranslationPolicy` bundle、manifest、哈希校验和任务快照。
3. 实现归一化翻译请求、四个协议适配器的非流式渲染、响应解析和统一错误。
4. 实现 Endpoint 模板、手动 URL、模型列表 best-effort 和测试请求；UI 不增加 raw prompt 编辑器。
5. 实现 Android 原生音频提取、有界分块 spool、PCM/WAV 规范化和 `fixed_v1` ChunkPlanner。
6. 接入 OpenRouter Whisper profile，打通带时间轴的源段。
7. 接入 OpenRouter Grok profile，完成词级 overlap 合并。
8. 按 `TranslationPolicy` 实现只读 context、分批、校验、格式恢复、有界 repair 和检查点。
9. 将生成的目标轨接入播放和 Glow，补齐恢复与真机验收。

实现过程中如需扩大协议、开放新 ASR 模型、修改内置 TranslationPolicy 或加入高级参数，应先修改本文的范围记录，而不是在适配器或 prompt 字符串中隐式增加行为。
