# Android 无 FFmpeg 音频解码与静音切片研究

> 状态：独立研究记录。
>
> 本文只回答“TransVortex 的 FFmpeg 静音切片能否在 Android 不引入 FFmpeg 的情况下实现，以及应如何分阶段实现”。不修改 `docs/GUIDE.md`，也不覆盖其他仓库文档或当前 V1 规格。

## 1. 结论先行

可以做到，而且不需要把 FFmpeg 打进 Android 包。

TransVortex 的这部分能力可以拆成普通的音频处理步骤：

```text
编码音频/视频
  -> 解复用
  -> 解码为 PCM
  -> 按振幅和持续时间寻找静音区间
  -> 选择窗口边界
  -> 生成带绝对时间的 PCM WAV 分片
```

FFmpeg 在这里主要承担的是解复用、解码、重采样以及 `silencedetect` 的 PCM 扫描。`silencedetect` 本身不是一个必须依赖 FFmpeg 的黑盒模型，它是一个带阈值和最短持续时间的流式检测器。因此，Android 可以用 `MediaExtractor`、`MediaCodec` 和 Kotlin 的纯逻辑重建同等语义。

但需要区分两件事：

- 可以达到“切在安静处、保留 overlap、没有合适边界时 hard cut”的产品行为；
- 不应承诺所有设备和所有媒体格式都能得到与 FFmpeg 逐采样完全相同的边界。

解码器来自设备或系统媒体栈，格式覆盖和浮点/整数转换可能不同。工程上应把等价目标定义为同一规范化 PCM 输入下的边界一致，以及真实设备上允许一个分析帧的时间误差，而不是二进制完全相同。

## 2. TransVortex 当前算法到底做了什么

TransVortex 的 `split_audio_for_asr` 在 `silence` 模式下大致分为四步：

1. 先把媒体提取、转换为 16 kHz、单声道、16-bit PCM WAV。
2. 调用 FFmpeg `silencedetect=noise=<dB>:d=<seconds>`，得到若干 `[start, end]` 静音区间。
3. 对每个 ASR 窗口，在 `start + min_window` 到 `hard_end` 之间寻找合适的静音区间中点，取最后一个符合条件的点；切点再加一个很小的 padding，并限制在 hard end 内。
4. 下一窗口从 `end - overlap` 开始。如果没有合适静音点，就直接在 hard end 处切分。

这意味着真正需要移植的是“PCM 静音检测 + 窗口规划”这两块纯逻辑。FFmpeg 的命令调用和日志解析不应成为 Android 的接口设计。

当前 TransVortex 的实践仍然说明固定窗口是可接受的工程基线：OpenRouter Whisper/Grok profile 默认约 300 秒窗口、3 秒 overlap，静音模式没有合适边界时也会回退到 hard cut。VortexCue 的首发可以继续使用这个更简单的基线。

## 3. Android 替换关系

| TransVortex / FFmpeg 职责 | Android 方案 | 关键限制 |
| --- | --- | --- |
| 读取容器、找到音频轨 | `MediaExtractor` | 能否读取取决于 Android 媒体栈对容器的支持；使用 `content://` URI 时不必复制原文件 |
| 解码编码音频 | `MediaCodec` 音频解码器 | 解码器通常由系统/设备提供，格式覆盖不是跨设备保证 |
| 重采样、声道转换、PCM16 | Media3 `AudioProcessor` 组合或自有流式转换器 | 当前依赖中尚未单独引入 Transformer；必须明确输出采样率、声道数和 PCM 编码，不能假设输入已经是 16 kHz 单声道 |
| FFmpeg `silencedetect` | 自有流式静音检测器 | 只处理 PCM 样本，不依赖媒体格式 |
| `_choose_silence_cut` / `_build_silence_windows` | Kotlin 纯函数移植 | 应与解码和网络层隔离，便于单元测试 |
| FFmpeg 分片输出 | 小型 WAV writer，或先写一份规范化 PCM 临时文件再按字节范围切片 | 每个分片要保存 `sourceStartSeconds`，不能把 overlap 后的时间轴当成新时间轴 |

`MediaExtractor` 负责读取解复用后的编码 sample，`MediaCodec` 输出解码后的 buffer，并在 `BufferInfo.presentationTimeUs` 中提供输出时间戳。两者正好覆盖 FFmpeg 命令链中与输入媒体相关的部分。Android 的 `MediaFormat` 也会暴露采样率、声道数和 PCM 编码等信息。当前 VortexCue 只依赖 Media3 ExoPlayer/Session；即使后续采用 Media3 的 `AudioProcessor`，也应先锁定版本 API 并用真机验证，不能把 Transformer 的格式能力自动推定为应用导出能力。

## 4. 静音检测器的最小实现

为了接近 FFmpeg 的默认语义，第一版检测器可以只接受规范化的 signed 16-bit PCM，并按采样点流式运行。`noise_db` 是相对于满量程的 dB 阈值：

```text
fullScale = 32767
threshold = fullScale * 10^(noiseDb / 20)

silenceStart = none

for each PCM frame at timestamp t:
    frameIsSilent = every channel abs(sample) < threshold

    if frameIsSilent and silenceStart is none:
        silenceStart = t

    if not frameIsSilent:
        if silenceStart is not none and t - silenceStart >= minSilence:
            emit [silenceStart, t]
        silenceStart = none

at end of stream:
    if silenceStart is not none:
        emit [silenceStart, endOfStream]
```

实现时不必逐个保留样本。可以以 10 到 20 ms 的 PCM frame 为单位计算该 frame 的最大绝对振幅，并在状态机中累计持续时间。若要与 FFmpeg 更接近，应在同一份 16 kHz、单声道、S16 PCM fixture 上做逐样本或小帧扫描；如果先降采样或混音，边界自然可能出现少量漂移。

多声道处理要先定规则。FFmpeg `silencedetect` 默认要求所有通道都落在阈值内才认为整体静音；VortexCue 的上传格式本来就是单声道，因此建议在检测前统一 downmix 到单声道，避免左右声道一边有声音时被误切。

## 5. 窗口规划的移植方式

静音检测只产生区间，窗口规划仍应单独测试。可以直接保留 TransVortex 的行为模型：

```text
start = 0
while start < duration:
    hardEnd = min(start + maxWindow, duration)
    if hardEnd == duration:
        end = duration
    else:
        candidates = silence ranges whose midpoint is in
                    [start + minWindow, hardEnd]
        end = last candidate midpoint + cutPadding,
              clamped to [start + minWindow, hardEnd]
        if no candidate:
            end = hardEnd

    emit [start, end]
    start = max(end - overlap, start + smallProgress)
```

分片的 `sourceStart` 和 `sourceEnd` 必须始终指向原始媒体时间。overlap 只是上传上下文，不是把字幕时间轴整体向前或向后移动。后续 ASR 合并仍应使用绝对时间和已有的 overlap 去重逻辑。

### 时间戳注意事项

不能简单地把“解码输出的第 N 个样本”当成原始媒体的第 N 个样本。实际设备上可能出现 codec priming、丢帧、时间戳间隙、重复时间戳或轨道起始偏移。建议：

- 以 `BufferInfo.presentationTimeUs` 作为每个解码 buffer 的时间基准，并记录音频轨的起始时间；
- 对规范化 PCM 维护“样本序号 -> 原始秒数”的映射，只有在时间戳连续且经过校验时才使用固定采样率直接换算；
- 对异常时间戳停止静音规划，回退固定窗口，同时保留诊断原因；
- 生成每个分片时同时保存 `sourceStartUs`、`sourceEndUs` 和规范化 PCM 的字节范围，避免后续合并依赖浮点四舍五入。

这也是为什么阶段 1 要先生成 manifest 并和 TransVortex 对照，而不是一开始就让检测结果直接决定最终字幕边界。

## 6. 为什么不直接使用 `SilenceSkippingAudioProcessor`

Media3 提供了 `SilenceSkippingAudioProcessor`，它可以在 16-bit PCM 上跳过静音，并提供阈值、最短静音时长和保留比例等参数。这对播放或实时处理很有用，但它的职责是改变输出流时长，不是报告“原始时间轴中的静音区间”。直接把它当成 TransVortex 的分窗器会有两个问题：

- 被跳过的样本已经从输出时间轴中消失，难以还原原媒体的边界；
- 它不会直接给出 `_build_silence_windows` 所需的 `[start, end]` 区间清单。

因此，若采用 Media3，建议把它或 `TeeAudioProcessor` 当作 PCM 管线的参考/观察点，静音区间仍由自有 detector 产生。若只需要第一版固定窗口，则甚至不需要引入这些 processor。

## 7. 推荐的分阶段方案

### 阶段 0：首发能力

保持当前 V1 规格：顺序读取本地媒体，规范化后使用固定窗口（约 300 秒 + 3 秒 overlap）上传 OpenRouter ASR。暂不做 VAD 或静音切片，也不引入 FFmpeg。

这样可以先验证 Android 的媒体 URI、解码、WAV 上传、时间戳归一化和断点恢复，不把“边界优化”和网络链路同时变成首发阻塞项。

### 阶段 1：只做本地分析

增加一个顺序解码器：

1. `MediaExtractor` 选择音频轨。
2. `MediaCodec` 解码并读取带时间戳的 PCM。
3. 流式 downmix/resample 到 16 kHz 单声道 S16。
4. 同时写规范化 PCM 临时文件，并生成静音区间 manifest。
5. 在同一批固定 fixture 上与 TransVortex 的 FFmpeg 结果比较。

先产出 manifest 而不改变 ASR 窗口，可以单独验证检测器，失败时仍然使用固定窗口。

### 阶段 2：启用静音边界

将 manifest 交给纯 Kotlin 的窗口 planner，启用“静音优先、hard cut 回退、overlap 保留”。任何解码器不支持、时间戳异常或分析失败，都明确回退到固定窗口并记录原因。

### 阶段 3：性能和格式扩展

在真实设备上决定是否需要环形缓冲区、边解码边写分片，以及是否扩展 FLAC、Opus、MKV、AC3/EAC3 等格式。不要为了追求格式数量在首发阶段重新引入完整 FFmpeg runtime。

## 8. 格式、设备和资源边界

Android 原生媒体栈的支持是“设备/系统版本相关”的。ExoPlayer 能播放某种媒体，也不等于应用可以稳定地把它解码、重采样并导出为上传用 PCM。建议首批建立真实设备矩阵，至少覆盖：

- WAV PCM；
- MP3；
- MP4/M4A 中的 AAC；
- 一个常见的视频容器中的 AAC 音轨。

FLAC、Opus、MKV、AC3/EAC3 等应通过设备测试后再列入支持范围。遇到不支持的轨道时应显示可解释错误，而不是静默地上传未经规范化的压缩数据。

处理必须流式进行。16 kHz、单声道、16-bit PCM 每分钟约 1.83 MiB，300 秒约 9.2 MiB；这是临时文件级别的体量，不应一次性全部放进内存。长任务还需要后续接入 WorkManager 或前台服务，并在取消时删除临时 PCM 和未完成分片。

## 9. 验证清单

### 算法一致性

- 合成前导、内部、尾部静音，验证最短持续时间和 EOS 收尾。
- 测试恰好在阈值上下的样本，确认严格比较规则与边界单位；若产品选择包含阈值，必须在 detector 版本中固定记录。
- 测试立体声单边有声，确认 downmix/全通道规则不会误切。
- 比较同一份规范化 PCM 上的 Android detector 与 FFmpeg，允许不超过一个分析 frame 的边界差异。
- 验证没有候选静音时一定 hard cut，窗口始终前进且 overlap 不会形成死循环。

### 媒体和设备

- Android 8（API 26）及当前目标版本各至少一台真机。
- 本地文件 URI、`content://` URI、带视频轨的 MP4/MKV。
- 解码器输出采样率/声道数变化、异常 EOS、旋转屏幕和后台取消。
- 失败后重试不会重复上传或丢失 `sourceStart`。

### 端到端质量

- 与固定窗口结果比较 ASR 文本、时间轴和 overlap 去重结果。
- 在 ASMR/低音量/长静音素材上检查是否把气声、背景底噪或尾音切掉。
- 记录 detector 版本、阈值、最短静音时长和回退原因，保证后续可复现。

## 10. 产品关系决定

这条路线会复用 TransVortex 的工程经验，但不把 TransVortex 作为 Android 的运行时依赖。两者的关系应暂定为：

- VortexCue 先实现移动端所需的最小 ASR + 翻译闭环；
- TransVortex 继续作为桌面/批处理和复杂媒体能力的参考实现；
- 未来再决定是否通过导入导出 manifest、字幕文件或任务包联动；
- 不在首发阶段为“联动”设计远程服务、共享进程或跨端协议。

## 11. 决策

1. 不为 Android 引入 FFmpeg，只为复刻 `silencedetect` 而引入完整 runtime 不划算。
2. 首发继续固定窗口 + overlap；静音感知切片是可独立验收的后续增强。
3. 后续实现优先使用 `MediaExtractor + MediaCodec + 自有 detector/planner`，Media3 processor 只作为 PCM 管线的可选组件。
4. 格式覆盖以真机矩阵为准，不能把“理论上能解码”写成跨设备承诺。
5. 与 TransVortex 的运行时联动推迟，先共享可验证的数据契约和测试样本。

## 12. 参考资料

- [FFmpeg `silencedetect` filter](https://ffmpeg.org/ffmpeg-filters.html)
- [Android `MediaExtractor`](https://developer.android.com/reference/android/media/MediaExtractor)
- [Android `MediaCodec`](https://developer.android.com/reference/android/media/MediaCodec)
- [Android `MediaCodec.BufferInfo`](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo)
- [AndroidX Media3 `AudioProcessor`](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor)
- [AndroidX Media3 `SilenceSkippingAudioProcessor`](https://developer.android.com/reference/androidx/media3/exoplayer/audio/SilenceSkippingAudioProcessor)
- [Media3 Transformer supported formats](https://developer.android.com/media/media3/transformer/supported-formats)

## 13. 本地核验位置

以下位置用于和当前 TransVortex 实现逐项对照（路径位于相邻的 `TransVortex` 仓库）：

- `src/transvortex/core/media.py:split_audio_for_asr`：根据模式选择固定窗口或静音窗口。
- `src/transvortex/core/media.py:_choose_silence_cut`：在合法范围内选择静音中点并应用 padding。
- `src/transvortex/core/media.py:_build_silence_windows`：推进窗口、保留 overlap、处理 hard-cut 回退。
- `src/transvortex/core/media.py:detect_audio_silence`：调用 FFmpeg `silencedetect` 并解析区间。
- `src/transvortex/core/media.py:_extract_audio` 与分片转换逻辑：输出 16 kHz、单声道、S16 PCM WAV。
