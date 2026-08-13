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
- 稳定的 segment ID 与绝对时间轴；
- 分批翻译、检查点和断点恢复；
- 统一的响应校验与诊断工件；
- ASR 与翻译传输层分离。

V1 不做特殊厂商业务分支。翻译侧识别的是协议，不是厂商；ASR 侧只开放经过验证的 OpenRouter profile。

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

### 2.2 V1 明确不做

- 应用代付 token、内置额度、代理转发或统一计费；
- TransVortex 作为运行时依赖；
- 本地 Whisper、FunASR 或其他本地 ASR；
- VAD、静音切分或基于语音活动的自适应切片；
- ffmpeg 依赖；
- 自动开放 OpenRouter 模型目录中的任意 ASR 模型；
- 翻译厂商专用业务逻辑或厂商自动识别；
- 自定义额外请求体、自定义 Header、厂商私有参数；
- 用户可调的 temperature、top-p、top-k、reasoning/fast 等高级参数；
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

每个翻译 profile 提供“测试请求”：

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
```

适配器可以使用协议所需的固定安全默认值，但这些默认值不是用户配置契约。需要额外 Header、特殊签名、项目 ID 或私有字段的网关不属于 V1 支持范围。

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

真实请求使用 JSON base64 传输。V1 不在 JSON 与 multipart 之间自动切换，也不提供 OpenRouter provider routing、排序、fallback 或私有 options 的 UI。

### 5.4 切片策略

ASR 不使用 VAD。初始策略直接沿用已验证的固定窗口经验：

```text
window target: 300 seconds
overlap: 3 seconds
```

实际执行还必须考虑媒体格式、请求体大小、网络超时和设备内存；这些是执行保护，不改变“固定窗口 + overlap”的产品策略。

失败处理：

1. 可重试的 timeout、429、5xx 使用有界重试；
2. 单个窗口持续失败时，可以将该窗口拆成更小的子窗口再重试；
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
source_profile
raw_reference
```

`start_ms` 和 `end_ms` 必须是相对于整段媒体的绝对时间。窗口内时间先加回窗口起点，再进行 overlap 合并。

统一层负责：

- 规范化空白和明显非法时间；
- 去除重叠窗口产生的重复词或重复段；
- 保留原始响应引用和诊断；
- 输出稳定、单调、可供翻译使用的 segment ID。

统一层不负责语义精修，不重写模型原文。

## 6. 翻译流水线

### 6.1 处理顺序

```text
ASR segments
  -> 按 segment ID 分批
  -> 固定系统提示与语言目标
  -> 协议适配器请求
  -> 严格解析译文
  -> 按 ID 写回目标文本
  -> 生成目标字幕轨
```

翻译批次沿用 TransVortex 已验证的分批和检查点经验，但不引入桌面端完整的质量工作台、翻译记忆管理或多供应商 fallback 图。

### 6.2 请求与响应契约

每个批次都携带稳定 ID，不依靠模型返回顺序猜测对应关系。推荐的逻辑载荷为：

```json
{
  "items": [
    {"id": "s000123", "start_ms": 12000, "end_ms": 14500, "text": "..."}
  ],
  "source_language": "ja",
  "target_language": "zh-CN"
}
```

模型被要求返回同一组 ID 与译文。应用校验：

- ID 不得缺失或重复；
- 不得出现未请求的 ID；
- 译文不得为空；
- 时间轴只能来自 ASR 段，模型不能修改时间；
- JSON/结构解析失败时执行一次有界格式重试，仍失败则暴露错误。

这是响应可靠性校验，不是人工精修。应用不默认对译文做二次润色、回译或逐句质量判定。

### 6.3 进度与恢复

任务至少记录：

- 使用的 ASR profile、模型和请求配置快照；
- 每个 ASR 窗口的完成状态；
- 每个翻译批次的完成状态；
- 已生成源段和目标段的稳定 ID；
- 可重试错误的次数和最后原因；
- 取消、恢复和最终失败状态。

恢复时只提交未完成的窗口或批次，不重复提交已经确认成功的结果。key、完整请求体和完整响应正文不进入可分享的任务报告。

## 7. Android 媒体处理

V1 不依赖 ffmpeg。媒体处理使用 Android 平台能力：

```text
Content URI
  -> MediaExtractor / Media3 探测
  -> MediaCodec 解码
  -> 应用内 PCM/WAV 规范化
  -> 固定窗口编码与上传
```

实现必须先声明实际支持的输入格式和设备限制，不把“Android 能播放”自动等同于“Android 能稳定解码并上传给 ASR”。无法提取音频时给出明确错误，不能把空音频提交给云端。

音频规范化属于本地临时工件：

- 不修改用户原始媒体；
- 不把完整音频长期复制到公共存储；
- 任务取消或清理后删除可删除的临时片段；
- 生成字幕只引用原媒体内容 ID 和任务结果。

## 8. 错误分类

错误面向用户保持少量、明确的类别：

| 类别 | 示例 | 处理 |
| --- | --- | --- |
| 配置错误 | 缺少协议、Endpoint、模型或 key | 保存前阻止，并指出字段 |
| 认证错误 | 401、403、无效 key | 停止当前请求，提示检查 key |
| 模型/协议错误 | 模型不存在、响应无法解析 | 停止当前批次并保留诊断 |
| 限流/暂时故障 | 429、5xx、超时 | 有界重试，必要时细分窗口 |
| 时间轴错误 | ASR 没有 profile 要求的 timestamps | 明确失败，不生成伪字幕 |
| 媒体错误 | 无法解码或格式不支持 | 在上传前失败 |
| 用户取消 | 主动停止任务 | 保存已完成结果，可继续 |

日志和诊断可以保留 HTTP 状态、请求 ID、profile、模型和耗时，但必须脱敏 key、Authorization、完整音频和完整敏感 prompt。

## 9. 最小验收标准

### Provider 设置

- 可以新增、编辑、删除翻译 profile；
- Endpoint 可以从模板选择，也可以手动填写；
- 模型列表请求失败时仍可手动填写模型；
- API key 显示脱敏，重启后仍可使用；
- 测试请求使用正式适配器并能区分认证、模型和响应解析错误；
- 不存在高级参数入口。

### ASR

- OpenRouter key 可以完成 Whisper 短音频真实请求；
- Whisper 缺少 segments 时明确失败；
- Grok 缺少 words 时明确失败；
- 300 秒窗口与 3 秒 overlap 产生正确的绝对时间；
- 重叠区不会产生明显重复字幕；
- 中断后可从未完成窗口继续。

### 翻译

- 至少一个 OpenAI-compatible Chat Endpoint 完成真实翻译；
- 四种协议各自通过最小 mock 响应解析测试；
- 返回缺行、重复 ID、未知 ID 或空译文时不会静默生成错误字幕；
- 翻译失败后已完成批次保留，恢复不会重复提交已完成批次；
- 目标字幕与源字幕共享同一时间轴。

### 播放

- 生成轨可以作为 VortexCue 的目标语言字幕显示；
- 任务结束后不需要用户导入 SRT 才能播放；
- 退出、重启和再次打开内容后，已生成轨和播放位置仍可恢复。

## 10. 实施顺序

1. 建立本地安全凭据存储和翻译 profile 数据模型。
2. 实现四个协议适配器的非流式请求、响应解析和统一错误。
3. 实现 Endpoint 模板、手动 URL、模型列表 best-effort 和测试请求。
4. 实现 Android 原生音频提取、PCM/WAV 规范化和固定窗口计划。
5. 接入 OpenRouter Whisper profile，打通带时间轴的源段。
6. 接入 OpenRouter Grok profile，完成词级 overlap 合并。
7. 复用 TransVortex 的 segment/batch/checkpoint 思路完成翻译流水线。
8. 将生成的目标轨接入播放和 Glow，补齐恢复与真机验收。

实现过程中如需扩大协议、开放新 ASR 模型或加入高级参数，应先修改本文的范围记录，而不是在适配器中隐式增加行为。
