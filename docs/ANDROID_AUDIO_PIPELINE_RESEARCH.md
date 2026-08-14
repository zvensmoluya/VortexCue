# Android 无 FFmpeg 音频解码与稳健分片研究

> 状态：独立研究记录。
>
> 本文回答“TransVortex 的 FFmpeg 静音切片经验如何在 Android 上转化为更稳健的 ASR 分片管线”。当前里程碑范围仍以 `docs/GUIDE.md` 为准；进入实现时，以 `docs/ASR_TRANSLATION_V1_SPEC.md` 的行为契约为准。

## 1. 结论先行

可以做到，而且不需要把 FFmpeg 打进 Android 包。但 Android 端不应逐字复刻 `silencedetect + 最后一个静音中点`，而应采用“硬覆盖、软边界”的设计：

- **硬覆盖**：原始时间轴必须被连续、完整地归属到 ASR 窗口，任何分析失败都不能丢音频；
- **软边界**：在固定目标附近优先选择相对安静的局部能量谷，找不到可信候选就固定切分；
- **时间轴不变**：低能量分析只移动逻辑边界，不删除、压缩或拼接所谓静音；
- **固定窗兜底**：固定窗口 + overlap 仍是正确性基线，不依赖 VAD 才能工作。

还要把三个容易混淆的概念分开：

1. **传输分窗**：受请求体大小、上游处理超时和设备资源限制，长媒体必须分批上传；
2. **边界优化**：在允许范围内选择一个较不容易截断语音的切点；
3. **字幕分段**：ASR 返回后的 cue 组织与翻译批次处理，不应由本地静音检测替代。

推荐管线可以拆成以下步骤：

```text
编码音频/视频
  -> 解复用
  -> 解码为 PCM
  -> 每声道边界特征 + 上传 PCM 规范化
  -> 有界滚动临时块
  -> ChunkPlanner 生成归属区间和上传区间
  -> 流式生成 WAV 请求
```

FFmpeg 在 TransVortex 中主要承担解复用、解码、重采样和 PCM 扫描。这些职责都可以由 Android 媒体栈与 Kotlin 纯逻辑覆盖。需要继承的是“有 overlap、优先在安静处切、没有候选就 hard cut”的工程经验，不是 FFmpeg 的日志格式或绝对 dB 判定本身。

但需要区分两件事：

- 可以达到“切在安静处、保留 overlap、没有合适边界时 hard cut”的产品行为；
- 不应承诺所有设备和所有媒体格式都能得到与 FFmpeg 逐采样完全相同的边界。

解码器来自设备或系统媒体栈，格式覆盖和浮点/整数转换可能不同。工程目标应定义为覆盖不变量、资源上界和端到端 ASR 质量，而不是与 FFmpeg 得到二进制完全相同的切点。

## 2. TransVortex 当前算法到底做了什么

TransVortex 的 `split_audio_for_asr` 在 `silence` 模式下大致分为四步：

1. 先把媒体提取、转换为 16 kHz、单声道、16-bit PCM WAV。
2. 调用 FFmpeg `silencedetect=noise=<dB>:d=<seconds>`，得到若干 `[start, end]` 静音区间。
3. 对每个 ASR 窗口，在 `start + min_window` 到 `hard_end` 之间寻找合适的静音区间中点，取最后一个符合条件的点；切点再加一个很小的 padding，并限制在 hard end 内。
4. 下一窗口从 `end - overlap` 开始。如果没有合适静音点，就直接在 hard end 处切分。

这套实现证明了固定窗与静音优先切点都可工作，但不应原样成为 Android 的接口设计：

- 全局固定 dB 阈值会把低声压耳语、气声和稳定底噪混为一类；
- “取最后一个合格静音”只偏向窗口更长，不代表它是目标附近风险最低的切点；
- 静音中点再加固定 padding 可能越出很短的静音区间；
- 先 downmix 再分析可能因左右声道相位抵消制造假静音；
- 用 `nextStart = end - overlap` 同时表达上传范围和结果归属，后续去重只能依赖启发式判断。

因此 Android 端应把“边界特征”和“窗口规划”作为两个纯逻辑组件重新定义，而不是逐函数移植。

当前 TransVortex 的实践仍然说明固定窗口是可接受的工程基线：OpenRouter Whisper/Grok profile 默认约 300 秒窗口、3 秒 overlap，静音模式没有合适边界时也会回退到 hard cut。VortexCue 的首发可以继续使用这个更简单的基线。

## 3. Android 替换关系

| TransVortex / FFmpeg 职责 | Android 方案 | 关键限制 |
| --- | --- | --- |
| 读取容器、找到音频轨 | Media3 `MediaExtractorCompat` 或平台 `MediaExtractor` | 能否读取取决于提取器和设备解码栈；使用 `content://` URI 时不必复制原文件 |
| 解码编码音频 | `MediaCodec` 音频解码器 | 解码器通常由系统/设备提供，格式覆盖不是跨设备保证 |
| 重采样、声道转换、PCM16 | Media3 `AudioProcessor` 组合或自有流式转换器 | 当前依赖中尚未单独引入 Transformer；必须明确输出采样率、声道数和 PCM 编码，不能假设输入已经是 16 kHz 单声道 |
| FFmpeg `silencedetect` | 自有 `BoundaryAnalyzer` | 只输出带原始时间戳的低成本能量特征，不删除样本，也不判定字幕语义 |
| `_choose_silence_cut` / `_build_silence_windows` | 重新定义的纯 Kotlin `ChunkPlanner` | 同时输出连续的归属区间和可重叠的上传区间，失败时固定切分 |
| FFmpeg 分片输出 | 分块 PCM spool + 小型 WAV writer/流式请求体 | 只保留当前窗口、前视和 overlap 所需块，不落整部媒体的规范化 PCM |

`MediaExtractorCompat` 适合希望与 Media3 提取语义保持一致的非播放场景；若不增加 `media3-inspector` 依赖，也可以先使用平台 `MediaExtractor`。两者都只负责 sample 提取，编码音频仍交给 `MediaCodec` 解码。`MediaCodec.BufferInfo.presentationTimeUs` 是原始时间映射的主要依据。

当前 VortexCue 使用 Media3 1.10.1，且只引入 ExoPlayer/Session。后续若增加 inspector、Transformer 或 audio processor 相关模块，所有 Media3 模块必须保持同一版本，并用真机验证实际容器和解码器矩阵。播放能力不能自动推导为稳定导出能力。

## 4. 从“静音检测”改为“边界评分”

### 4.1 为什么不做二元静音判断

VortexCue 不需要回答“这一段是不是语音”，只需要回答“目标切点附近哪里相对不容易截断内容”。这与 VAD 的目标不同，也不需要先生成完整的静音区间清单。

绝对阈值仍可作为诊断数据或极端峰值保护，但不应成为唯一决策信号。对于 ASMR，局部相对能量比固定 `-35 dB` 更有意义：整段素材可能都很轻，也可能始终带有风声、音乐或设备底噪。

### 4.2 流式特征

解码后的 PCM 以 10 到 20 ms 为一帧，对每个声道分别计算 RMS 和 peak，再保守地合并：

```text
frameRmsDb  = max(rmsDb(channel)  for channel in channels)
framePeakDb = max(peakDb(channel) for channel in channels)
shortEnergy = smooth(frameRmsDb, 200..500 ms)
localFloor  = rollingPercentile(shortEnergy)

emit BoundaryFeature(
    sourceTimeUs,
    shortEnergy,
    localFloor,
    framePeakDb
)
```

使用各声道较大的能量值，能保证任一声道仍有明显内容时不会被另一声道抵消。边界分析应发生在破坏性 downmix 之前；上传用的 16 kHz、单声道、S16 PCM 可以在旁路中继续生成。

短时平滑和双阈值滞回用于抑制底噪附近的快速抖动。具体帧长、平滑宽度、局部分位数和阈值属于带版本的 planner 配置，必须通过固定 fixture 确定，不能提供成首发用户参数。

### 4.3 候选评分

`BoundaryAnalyzer` 不直接输出“静音/非静音”，而是在目标附近产生候选谷。一个候选的逻辑评分可以由以下部分组成：

```text
score = relativeQuietness
      + valleyDuration
      + valleyDepth
      - distanceFromTarget
      - peakGuard
```

- `relativeQuietness`：相对局部噪声底的安静程度；
- `valleyDuration`：谷底稳定持续时间，避免选中单个偶然低样本；
- `valleyDepth`：相对前后能量的落差；
- `distanceFromTarget`：离目标越远惩罚越大；
- `peakGuard`：附近有瞬态 peak 时拒绝或显著降分。

评分不足不是错误。planner 直接使用固定目标切点，并记录 `FIXED_NO_CONFIDENT_BOUNDARY`。

## 5. 窗口规划：归属区间与上传区间分离

### 5.1 核心数据契约

每个窗口必须明确区分字幕结果归属范围和实际上传范围：

```text
ChunkPlan
  ownedStartUs
  ownedEndUs
  uploadStartUs
  uploadEndUs
  pcmStartFrame
  pcmEndFrame
  cutReason
  plannerVersion
```

相邻 `owned` 区间首尾相接且不重叠；相邻 `upload` 区间通过上下文发生 overlap。若总 overlap 为 3 秒，可在逻辑边界两侧各保留 1.5 秒上下文：

```text
owned:  [B0--------------B1][B1--------------B2]
upload: [B0-----------------B1+1.5s]
                         [B1-1.5s-----------------B2+1.5s]
```

ASR 返回的相对时间先加 `uploadStartUs` 转为绝对时间；word/segment 的中点落在哪个 `owned` 区间，就优先归属哪个窗口。文本和时间相似度只用于处理 provider 分段差异，不再承担定义基本覆盖范围的职责。

### 5.2 Planner 参数和流程

profile 只描述可验证的传输约束，不把 planner 的内部推导暴露为产品配置：

```text
targetUploadDuration
hardMaxUploadDuration
maxEncodedBytes
overlapDuration
boundaryPlannerVersion
```

低能量 planner 再从 overlap 分配方式和上传限制推导内部参数：

```text
targetOwnedDuration
searchBeforeTarget
searchAfterTarget
contextBefore
contextAfter
```

`fixed_v1` 先生成固定重叠的上传窗口，再以相邻 overlap 中点推导 `owned` 边界，与 V1 规格保持一致。后续低能量 planner 才改为先在目标附近选择逻辑边界，再向边界两侧加入上下文；两种模式输出相同的 `ChunkPlan`。

固定 profile 可以让 target 与 hard max 相等；启用向目标之后搜索的 profile 必须在二者之间预留前视和上下文空间。所有候选最终仍受 hard max 和编码字节上限裁剪。

低能量 planner 的流程如下：

```text
ownedStart = 0
while ownedStart < duration:
    preferredCut = min(ownedStart + targetOwnedDuration, duration)
    latestCut = latest boundary satisfying duration and byte limits
    candidateRange = [preferredCut - searchBeforeTarget,
                      preferredCut + searchAfterTarget]
                     clipped to progress and latestCut constraints

    ownedEnd = best confident boundary in candidateRange
               or min(preferredCut, latestCut)

    uploadStart = max(0, ownedStart - contextBefore)
    uploadEnd = min(duration, ownedEnd + contextAfter)

    emit ChunkPlan(...)
    ownedStart = ownedEnd
```

planner 必须保证每轮有最小前进量，最终 `owned` 区间精确覆盖 `[0, duration]`。低能量特征缺失、时间戳异常或候选置信度不足时，只允许退化为固定切点。

重试拆分也遵守同一契约：子窗口的 `owned` 区间必须精确分割父窗口，上传区间可以重新加入上下文。这样 timeout 拆分不会制造时间轴缺口或重复所有权。

### 5.3 时间戳注意事项

不能简单地把“解码输出的第 N 个样本”当成原始媒体的第 N 个样本。实际设备上可能出现 codec priming、丢帧、时间戳间隙、重复时间戳或轨道起始偏移。建议：

- 以 `BufferInfo.presentationTimeUs` 作为每个解码 buffer 的时间基准，并记录音频轨的起始时间；
- 对规范化 PCM 维护“样本序号 -> 原始秒数”的映射，只有在时间戳连续且经过校验时才使用固定采样率直接换算；
- 对异常时间戳停止边界优化，回退固定窗口，同时保留诊断原因；
- 每个分片保存 `ownedStartUs`、`ownedEndUs`、`uploadStartUs`、`uploadEndUs` 和规范化 PCM 的整数 frame 范围，避免后续合并依赖浮点四舍五入。

时间映射校验是正确性前提；低能量候选只是可丢弃的优化数据。两者不能共享一个“分析成功”开关。

## 6. 为什么不直接使用 `SilenceSkippingAudioProcessor`

Media3 提供了 `SilenceSkippingAudioProcessor`，它可以在 16-bit PCM 上跳过静音，并提供阈值、最短静音时长和保留比例等参数。这对播放或实时处理很有用，但它的职责是改变输出流时长，不是为原始时间轴上的候选边界打分。直接把它用于 ASR 分窗会有两个问题：

- 被跳过的样本已经从输出时间轴中消失，难以还原原媒体的边界；
- 它不会提供 `ChunkPlanner` 所需的原始时间特征和候选置信度。

因此不应把它放入 ASR 规范化主链。固定 planner 不需要任何静音 processor；后续边界优化由只读的 `BoundaryAnalyzer` 观察原始 PCM，不改变输出样本。

## 7. 推荐的分阶段方案

### 阶段 0：先固定窗口契约

首发继续使用固定窗口 + overlap，不引入 FFmpeg、VAD 或低能量边界决策。但从第一版就使用 `ChunkPlan`，分开记录 `owned` 和 `upload` 区间，并把窗口时长、字节上限和 overlap 放入 ASR profile。

这样可以先验证 Android 的媒体 URI、解码、WAV 上传、时间戳归一化、overlap 归属和断点恢复，不让边界优化成为首发阻塞项。

### 阶段 1：实现有界单遍管线

按以下顺序处理：

1. `MediaExtractorCompat` 或 `MediaExtractor` 选择音频轨。
2. `MediaCodec` 解码并读取带时间戳的 PCM。
3. 流式规范化到 16 kHz、单声道、S16，并写入 5 到 10 秒的临时 PCM 块。
4. 固定 planner 只读取当前窗口、overlap 和少量前视所需的块。
5. 边界确定后按 frame 范围生成 WAV 请求，检查点确认后删除不再需要的块。
6. 待上传队列达到上限时暂停解码，形成显式背压。

不生成整部媒体的规范化 PCM，也不把每个窗口复制成长期文件。实现可以在边界确定后以“WAV header + PCM block slices”组成流式请求体；若先使用独立临时 WAV，生命周期也必须限定在当前窗口和有界重试范围内。

### 阶段 2：边界评分 shadow 模式

在 downmix 前增加 `BoundaryAnalyzer`，记录它建议的切点、置信度和固定切点差值，但仍按固定 planner 上传。用同一批 fixture 和真实 ASMR 素材评估：

- 候选是否落在呼吸、尾音或耳语内部；
- 相比固定切点，窗口边缘的漏词、重复词和时间戳是否改善；
- 分析耗时、额外磁盘和不同设备输出是否稳定；
- 固定回退比例和触发原因是否可解释。

FFmpeg 结果只作为参考，不是正确答案。最终启用条件是覆盖不变量不变且端到端 ASR 指标改善。

### 阶段 3：启用软边界

通过 shadow 验证后，为已验证 profile 启用带版本的低能量 planner。任何特征缺失、候选不可信、时间映射异常或资源上限冲突都回退固定切点，并在 `cutReason` 中记录原因。

WebRTC VAD 或 Silero VAD 若未来参与实验，只能提供附加分数，不能裁掉音频、改变时间轴或成为窗口推进条件。

### 阶段 4：格式和性能扩展

根据真实设备矩阵决定是否扩展 FLAC、Opus、MKV、AC3/EAC3 等格式。不要为了追求格式数量重新引入完整 FFmpeg runtime。分块 spool 已经提供有界处理，不需要先以整文件方案上线再补环形缓冲区。

## 8. 格式、设备和资源边界

Android 原生媒体栈的支持是“设备/系统版本相关”的。ExoPlayer 能播放某种媒体，也不等于应用可以稳定地把它解码、重采样并导出为上传用 PCM。建议首批建立真实设备矩阵，至少覆盖：

- WAV PCM；
- MP3；
- MP4/M4A 中的 AAC；
- 一个常见的视频容器中的 AAC 音轨。

FLAC、Opus、MKV、AC3/EAC3 等应通过设备测试后再列入支持范围。遇到不支持的轨道时应显示可解释错误，而不是静默地上传未经规范化的压缩数据。

处理必须流式进行。16 kHz、单声道、16-bit PCM 每分钟约 1.83 MiB，300 秒约 9.2 MiB；JSON base64 后约 12.2 MiB，还不含请求 JSON。实现不得构造包含整段 base64 的大字符串，应使用流式请求体或有界临时文件。

`300 秒` 是当前 profile 的经验默认值，不是跨模型的产品不变量。OpenRouter 的限制同时受编码后字节数和上游处理时间影响；profile 必须分别记录目标时长、hard max、编码格式、传输方式和已验证日期，失败拆分仍是必要兜底。

分块 spool 的磁盘上界由 `hardMaxUploadDuration + search lookahead + overlap` 和有界待上传队列决定，不随媒体总时长增长。长任务仍需要合适的后台执行策略，并在取消时删除临时 PCM 块和未完成分片。

## 9. 验证清单

### Planner 与边界分析

- 合成前导、内部、尾部低能量谷，验证候选评分和 EOS 收尾。
- 测试全程低声压、稳定底噪、短瞬态和阈值附近抖动，确认局部基线与滞回不会产生大量假候选。
- 测试立体声单边有声和左右反相素材，确认分析发生在 downmix 前且不会制造假安静边界。
- 验证无候选、低置信度和分析异常时一定固定切分，窗口始终前进。
- 对任意时长验证 `owned` 区间无缝、无重叠地覆盖完整时间轴。
- 验证相邻 `upload` 区间 overlap 精确，且所有窗口满足时长和编码字节上限。
- 验证重试子窗口的 `owned` 区间精确分割父窗口。

### 媒体和设备

- Android 8（API 26）及当前目标版本各至少一台真机。
- 本地文件 URI、`content://` URI、带视频轨的 MP4/MKV。
- 解码器输出采样率/声道数变化、异常 EOS、旋转屏幕和后台取消。
- 失败后重试不会丢失 `ChunkPlan`、重复确认已完成窗口或改变既有归属边界。
- 慢网络下 spool 和待上传队列保持上界，取消后临时块可以清理。

### 端到端质量

- 与固定窗口结果比较 ASR 文本、时间轴、边缘漏词和 overlap 重复结果。
- 在 ASMR、低音量、耳语、长停顿和持续背景声素材上确认任何 planner 都不会裁掉气声、底噪或尾音。
- 记录 `plannerVersion`、特征配置、候选分数、最终 `cutReason` 和回退原因，保证后续可复现。

## 10. 产品关系决定

这条路线会复用 TransVortex 的工程经验，但不把 TransVortex 作为 Android 的运行时依赖。两者的关系应暂定为：

- VortexCue 先实现移动端所需的最小 ASR + 翻译闭环；
- TransVortex 继续作为桌面/批处理和复杂媒体能力的参考实现；
- 未来再决定是否通过导入导出 manifest、字幕文件或任务包联动；
- 不在首发阶段为“联动”设计远程服务、共享进程或跨端协议。

## 11. 决策

1. 不为 Android 引入 FFmpeg，只为复刻 `silencedetect` 而引入完整 runtime 不划算。
2. 首发继续固定窗口 + overlap，但从第一版就分开 `owned` 和 `upload` 区间。
3. 静音感知改为不改变样本的低能量边界评分；它是可回退、可独立验收的后续增强。
4. 使用有界分块 spool 做单遍处理，不先落整部规范化 PCM 或全局静音 manifest。
5. 后续实现优先使用 `MediaExtractorCompat/MediaExtractor + MediaCodec + 自有 BoundaryAnalyzer/ChunkPlanner`；Media3 processor 只承担明确的 PCM 转换职责。
6. `300 秒` 只保留为已验证 profile 的默认配置，不作为跨 provider、模型和传输格式的产品常量。
7. 格式覆盖以真机矩阵为准，不能把“理论上能解码”写成跨设备承诺。
8. 与 TransVortex 的运行时联动推迟，先共享可验证的数据契约和测试样本。

## 12. 参考资料

- [FFmpeg `silencedetect` filter](https://ffmpeg.org/ffmpeg-filters.html)
- [Android `MediaExtractor`](https://developer.android.com/reference/android/media/MediaExtractor)
- [Android `MediaCodec`](https://developer.android.com/reference/android/media/MediaCodec)
- [Android `MediaCodec.BufferInfo`](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo)
- [AndroidX Media3 `MediaExtractorCompat`](https://developer.android.com/reference/androidx/media3/inspector/MediaExtractorCompat)
- [AndroidX Media3 `AudioProcessor`](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor)
- [AndroidX Media3 `SilenceSkippingAudioProcessor`](https://developer.android.com/reference/androidx/media3/exoplayer/audio/SilenceSkippingAudioProcessor)
- [Media3 Transformer supported formats](https://developer.android.com/media/media3/transformer/supported-formats)
- [OpenRouter Speech-to-Text](https://openrouter.ai/docs/guides/overview/multimodal/stt)

## 13. 本地核验位置

以下位置用于和当前 TransVortex 实现逐项对照（路径位于相邻的 `TransVortex` 仓库）：

- `src/transvortex/core/media.py:split_audio_for_asr`：根据模式选择固定窗口或静音窗口。
- `src/transvortex/core/media.py:_choose_silence_cut`：在合法范围内选择静音中点并应用 padding。
- `src/transvortex/core/media.py:_build_silence_windows`：推进窗口、保留 overlap、处理 hard-cut 回退。
- `src/transvortex/core/media.py:detect_audio_silence`：调用 FFmpeg `silencedetect` 并解析区间。
- `src/transvortex/core/media.py:_extract_audio` 与分片转换逻辑：输出 16 kHz、单声道、S16 PCM WAV。
