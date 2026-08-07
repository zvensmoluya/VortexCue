# VortexCue / 荧荧当前指南

> 状态：当前权威产品与工程指导
>
> 当前阶段：M0B 本地音频播放基础
>
> 更新时间：2026-08-07

本文是仓库当前唯一的产品与工程规格。实现、评审和范围判断优先依据本文；历史记录和参考资料只提供证据与背景。

规则发生变化时直接更新本文并说明原因，不再新建平行的产品规格、架构草案或路线图。

## 1. 产品目标

VortexCue / 荧荧是一个会逐渐退场的 OLED 微光字幕阅读器：媒体提供声音和时间，屏幕只保留正在发生的文字。

当前首先服务这样的场景：用户已经拥有本地音频与 LRC 或 SRT，希望在睡前、暗室或宿舍继续听，并依靠同步文字理解内容。音频与歌词/字幕是当前实现入口；是否重新加入本地视频，留待真实使用反馈后判断。

产品解决的不是“如何管理媒体”，而是：

> 当我不想继续看明亮画面、又需要文字理解内容时，让声音继续播放，只留下当前需要阅读的一小段文字。

### 1.1 产品边界

- 字幕是主要内容，媒体是声音与时间源；
- 工作层负责选择、排错和校准，微光层负责持续阅读；
- 首页围绕继续上次和打开新内容，不建立媒体库；
- 普通播放器仍以字幕语境为中心，不复制完整音乐播放器；
- 微光模式不显示封面、Logo、常驻时间或进度条；
- 本地音频、LRC 和 SRT 无需账号、网络或 TransVortex；
- M1 之外的能力必须由真实使用需求重新进入范围。

## 2. 产品表面与交互

### 2.1 M0B 路径

```text
启动
→ 选择本地音频
→ 选择 LRC / SRT
→ 播放与字幕同步
→ 隐藏控制进入微光
→ 暂停 / 恢复 / ±10 秒
```

M0B 直接使用用户自己的音频与字幕文件做真机验证，不准备内置样片。当前只完成播放器、格式解析、字幕时间轴和微光表面，不提前固定最终导航与交互体验。

### 2.2 M1 路径

```text
首页
├─ 继续播放 → 恢复上次位置与显示状态
└─ 打开本地音频 → 选择 LRC / SRT
                    ↓
              Ready Resolver
                    ↓
                 普通播放
                    ↓
                   微光
```

各表面的职责固定如下：

- **Home**：只提供“继续播放”和“打开本地音频”；M1 只保存一个最后会话，不做最近列表；
- **Ready Resolver**：只在首次导入、缺少字幕、解析失败、文件权限失效或播放失败时出现，不是每次播放的固定页面；
- **Normal Player**：显示当前 cue、相邻语境、时间、seek、速度、字幕偏移和显示设置；
- **Glow**：控制隐藏时只有纯黑背景和当前 cue；控制按需短暂出现。

### 2.3 当前交互默认值

- 单击微光表面：显示或隐藏当前原型控制；
- 可见控件提供播放、暂停、前后 10 秒和字幕偏移；
- 系统 Back：控制隐藏时先恢复控制；
- 音量键保持系统默认行为；耳机键、蓝牙键和锁屏控制留到 M1 `MediaSessionService`；
- 所有手势都有至少 `48dp × 48dp` 的可见控件替代；
- 字幕默认略低于屏幕中心并保持稳定，不做周期性位置漂移；
- 字幕 gap、播放结束和播放失败时立即清空当前 cue；
- 暂停时可以保留当前 cue，但应用不为暂停状态无限保持屏幕唤醒。

这些只是为了完成播放器实测的低成本默认值，不冻结最终信息架构、手势或控制布局。后续交互可以根据真机感受直接重做。

## 3. 工程约束

### 3.1 保持简单

- 使用单一 `app` 模块；
- 使用 Kotlin、Compose 和 Media3；
- M0B 只引入两次 SAF 文件选择，不引入服务、数据库或依赖注入；
- M1 不引入多模块、DI framework、Room 或自建媒体库；
- 先完成端到端路径，再按实际复杂度抽取边界。

### 3.2 播放器时间是唯一真相

```kotlin
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class DisplayCalibration(
    val windowBrightness: Float,
    val subtitleLevel: Int,
)
```

当前字幕始终按下面的关系求值：

```text
Player.currentPosition + subtitleOffsetMs
                    ↓
          SubtitleTimeline.resolve()
                    ↓
                currentCue
```

- 字幕层不自行累加播放时间；
- 暂停、seek、倍速、缓冲恢复和媒体切换后重新读取播放器位置；
- UI 可以周期读取 `currentPosition` 或调度到下一个 cue 边界，但不能拥有另一套时钟；
- `SubtitleTimeline` 保持为纯 Kotlin 逻辑，并覆盖边界单元测试。

### 3.3 M0B 结构

```text
MainActivity
├─ ExoPlayer
└─ VortexCuePlayer
   ├─ ControlsVisible
   └─ ControlsHidden

SubtitleParser
├─ LrcParser
└─ SrtParser

SubtitleTimeline
DisplayCalibration
```

ExoPlayer 暂时由 Activity 持有并在 Activity 停止时暂停、销毁时释放。LRC 与 SRT 解析为同一种 `SubtitleCue`，再由统一时间轴按播放器位置求值。

### 3.4 M1 结构

```text
PlaybackService : MediaSessionService
├─ ExoPlayer
└─ MediaSession

MainActivity
└─ MediaController

SubtitleRepository
├─ LrcParser
├─ SrtParser
├─ SubtitleTimeline
└─ CurrentCueResolver

LastSessionStore
├─ mediaUri
├─ subtitleUri
├─ positionMs
├─ subtitleOffsetMs
└─ displayPreset
```

- Player 和 MediaSession 由 `MediaSessionService` 持有；
- Activity 通过 `MediaController` 展示状态和发出命令；
- 当前只选择本地音频；视频输入与视频 Surface 均不进入 M1 默认范围；
- LastSession 使用 DataStore 级别的轻量持久化即可；
- URI、解析和播放失败是可恢复业务状态，不以崩溃或短暂 toast 处理。

### 3.5 文件访问

M0B 起使用两次明确的 Storage Access Framework 选择：

```text
ACTION_OPEN_DOCUMENT(audio)
→ takePersistableUriPermission
→ ACTION_OPEN_DOCUMENT(LRC / SRT)
→ takePersistableUriPermission
→ parse
```

- 不请求广泛存储权限；
- 不把 `content://` 转换成真实路径；
- 不承诺扫描音频所在目录或自动寻找相邻字幕；
- 可以用 `DocumentsContract.EXTRA_INITIAL_URI` 尽量让第二次 picker 靠近上次位置，但不能依赖它一定成功；
- 文件被移动、删除或授权失效时，让用户重新定位原媒体或字幕。

### 3.6 OLED 与系统行为

- 微光根表面固定为 `#000000`；
- 窗口亮度和字幕颜色分别控制；
- 控制可以短暂比字幕更亮，但不改变整个窗口亮度；
- `keepScreenOn` 只随实际播放或短时交互启用；
- 不尝试关闭自动亮度、Extra Dim、护眼或色彩校正；
- 不承诺零功耗、零残影或零烧屏；
- 来电、闹钟、通知和系统亮色浮层属于平台行为，应用只保证自身不制造常驻亮色表面。

## 4. 阶段与实施顺序

### 4.1 M0A：完成

首轮 Vivo OLED 暗室测试已经证明“纯黑背景视觉消失、低灰字幕可读”的基本物理可行性，记录见 [`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)。

多设备一致性、客观亮度、长时间功耗和残影仍未知，但不再阻塞体验原型。它们在遇到实际问题或准备公开发布时补充。

### 4.2 M0B：当前工作

目标：用本地音频与真实 LRC/SRT 完成一次微光文字播放闭环，先验证播放器和同步效果。

实施顺序：

1. 在 Activity 内加入 ExoPlayer 与本地音频选择；
2. 实现 LRC/SRT parser、`SubtitleTimeline.resolve()` 及边界测试；
3. 实现极简播放器与微光控制显隐；
4. 接入播放、暂停、±10 秒、字幕偏移和亮度校准；
5. 在现有 OLED 真机使用用户文件完整播放一次并记录明显问题。

完成条件：

- Debug APK 可安装并完整播放所选本地音频；
- LRC 与 SRT 可以解析为统一时间轴；
- 暂停、恢复和 seek 后字幕仍与播放器位置一致；
- 微光隐藏状态没有非字幕常驻 UI；
- 在一台 OLED 上完成一次暗室体验，没有发现必须先解决的阻断问题。

默认验证：`gradlew.bat testDebugUnitTest` 覆盖字幕时间轴，`gradlew.bat assembleDebug` 验证集成，再做一次真机 smoke test。不要求用户研究、设备矩阵或长时间实验才能进入 M1。

### 4.3 M1：本地播放 MVP

目标：完成“打开自己的音频和 LRC/SRT，并能在后台播放、再次继续”的最小可用产品。

范围：

- `MediaSessionService`、ExoPlayer 和 MediaController；
- SAF 选择音频与 LRC/SRT；
- LRC/SRT parser、字幕时间轴和字幕偏移；
- 普通播放器与微光播放器；
- 后台、锁屏和外部媒体按键；
- 一个 LastSession 及文件权限恢复；
- UTF-8、GB18030、UTF-8 BOM、UTF-16 BOM、多行 cue、LRC offset、LF/CRLF、gap 和错误输入的 parser fixtures。

建议顺序：

1. 把 Player 迁入 `MediaSessionService`；
2. 固化并测试 LRC/SRT parser；
3. 接入两次 SAF 文件选择和持久 URI 权限；
4. 建立 Ready Resolver 错误状态；
5. 保存并恢复一个 LastSession；
6. 验证 Home、锁屏、耳机断开、蓝牙断开和 Activity 重建。

完成条件：

- 用户可以打开本地音频与 LRC/SRT；
- 正常播放、seek、倍速和 offset 后 cue 正确；
- 屏幕关闭后音频继续，系统媒体状态准确；
- 再次打开可以通过一次“继续播放”恢复位置；
- 文件授权失效时可以重新定位，而不是崩溃。

M1 明确不做：

- WebVTT、ASS、内嵌字幕；
- 视频 Surface 和视频观看模式；
- 媒体扫描、最近列表和完整媒体库；
- ASR、翻译、字幕编辑和在线字幕搜索；
- `.vtxsub`、TransVortex 自动配对和云任务；
- 账号、推荐、收藏、队列和社交；
- FFmpeg、Room、DI framework 和多模块架构。

## 5. 验证原则

验证服务于发现真实风险，不以研究规模本身作为阶段成果。

- 纯逻辑改动运行最小单元测试；
- 播放或集成改动构建 APK，并走一遍直接相关路径；
- 设备、媒体或系统生命周期行为使用真机 smoke test，并记录已知限制；
- 不默认要求 A/B 测试、多人样本、完整设备矩阵或 20–60 分钟实验；
- 只有观察到可读性、同步、功耗、残影或系统兼容问题时，才扩大对应验证；
- 多品牌 OLED、LCD 对照、长时间功耗和系统干扰矩阵放到公开发布前处理。

## 6. 参考资料

- M0A 记录：[`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)
- 产品与交互研究：[`reference/research.md`](reference/research.md)
- 项目设计种子：[`reference/PROJECT_SEED.md`](reference/PROJECT_SEED.md)

参考资料中的长期设想、竞品观察和实验建议不会自动进入范围。需要采用时，先把结论收敛后写入本文。
