# ASR overlap 真机验证记录

## 2026-08-31：Vivo V2458A

状态：`PASS`

- 设备：vivo V2458A，Android 16，API 36，1260 × 2800。
- 应用：`io.github.zvensmoluya.vortexcue.prototype`，Debug `0.0.1`（versionCode 1）。
- APK SHA-256：`57d9f6002eec6bb4c6c5a3e6b590725fdd9ac19eb0dc6694ad4290981448d3e2`。
- 夹具：AAC-LC、48 kHz、双声道、334315 ms；测试使用前 330000 ms。
- 夹具 SHA-256：`bc4134467212b5180853e33297cb9572603494f0abd6bad1105a2fb611013075`，推送前后哈希一致。

测试通过 Android instrumentation 直接调用正式 `PlatformAudioWindowExtractor`，覆盖平台 `MediaExtractor`、`MediaCodec`、混合为单声道以及重采样到 16 kHz PCM16 的完整设备路径。分别提取相邻窗口，再对两侧 3 秒 overlap PCM 搜索 ±100 ms 范围内的最佳相关性和帧偏移。

| Profile | 上传窗口（ms） | WAV 字节数 | 最佳偏移 | 相关性 | 结果 |
| --- | --- | --- | --- | --- | --- |
| OpenRouterQwen3Asr17BV1 | `0–175000`、`172000–330000` | `5600044`、`5056044` | 0 帧 / 0.000 ms | 1.00000000 | PASS |
| OpenRouterWhisperV1 | `0–300000`、`297000–330000` | `9600044`、`1056044` | 0 帧 / 0.000 ms | 0.99998667 | PASS |

四个 WAV 的文件长度均与计划中的 16 kHz mono PCM16 帧数完全一致。该设备未观察到 AAC 解码、时间戳裁取或重采样导致的 overlap 错轴。

`connectedDebugAndroidTest` 首次被主机侧 Unified Test Platform 依赖下载的 TLS 握手失败阻断；随后使用已构建的主 APK 与 test APK，通过 `adb shell am instrument` 运行同一用例并通过。执行期间 Vivo 曾冻结处于后台的 instrumentation 目标进程，所有线程停在 `do_freezer_trap`；唤醒设备并把 MainActivity 放到前台后，原测试继续完成且 PCM 对齐结果正常。该现象记录为厂商后台管控观察，不据此判定正式 WorkManager 任务通过或失败。
