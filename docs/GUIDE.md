# VortexCue / 荧荧当前指南

> 状态：当前唯一的产品与工程指导
>
> 当前阶段：M1B 健壮来源与会话恢复（代码收口，待集中真机验收）
>
> 更新时间：2026-08-08

本文是仓库当前唯一的产品与工程规格。实现、评审和范围判断优先依据本文；验证记录、品牌探索和参考资料只提供证据与背景。

规则发生变化时直接更新本文并说明原因，不再新建平行的产品规格、架构草案或路线图。

## 1. 产品目标

VortexCue / 荧荧是一个会逐渐退场的 OLED 微光同步文字播放器：媒体提供声音和时间，屏幕在微光模式中只保留正在发生的文字。

当前首先服务这样的场景：用户已经拥有本地音频或视频文件，以及可选的同步字幕，希望在睡前、暗室或宿舍继续听，并依靠同步文字理解内容。

产品解决的不是“如何管理全部媒体”，而是：

> 当我不想继续看明亮画面、又需要文字理解内容时，让声音继续播放，只留下当前需要阅读的一小段文字。

### 1.1 当前主路径

```text
冷启动
→ Home
   ├─ 继续上次
   ├─ 新建播放
   └─ 设置

新建播放 / 编辑来源
→ 配置草稿
   ├─ 播放源
   ├─ 字幕源
   └─ 校验与恢复
→ 应用并开始播放
→ Normal Player
→ Glow
```

应用存在正在播放或暂停的活动会话时，重新进入应用可以直接返回 Player；服务已经结束但保存有最后会话时，Home 显示“继续播放”。

### 1.2 产品边界

- 字幕是主要视觉内容，媒体是声音与时间源；
- Home 和来源配置负责建立会话，Player 负责消费会话，两者不再挤在同一个页面；
- 播放源可以是本地音频，也可以是包含可播放音轨的视频容器；M1 不显示视频画面；
- 媒体可以在没有字幕时正常播放，但没有有效字幕时不提供有意义的 Glow 阅读体验；
- Home 只保存和恢复一个最后会话，不建立完整媒体库；
- 普通播放器仍以字幕语境为中心，不复制完整音乐播放器；
- Glow 不显示封面、Logo、常驻时间或常驻进度条；
- 本地播放与已有字幕无需账号、网络或 TransVortex；
- M1 之外的能力必须由真实使用需求重新进入范围。

## 2. 产品表面与导航

### 2.1 Home

Home 是默认首屏，也是播放会话的工作层。它承担：

- 继续最后会话；
- 新建播放；
- 查看或修改当前配置草稿；
- 进入应用设置、显示预设和数据管理；
- 为未来经过验证的入口保留空间，但不显示不可用的占位功能。

Home 不是内容发现页，不扫描设备、不展示媒体库，也不默认泄露字幕正文。继续卡片最多显示：

- 媒体标题或文件名；
- 上次位置与总时长；
- 字幕状态；
- “继续”或“重新定位”动作。

品牌角色和较明亮的视觉语言可以出现在 Home、空状态、完成状态和可恢复错误中，但不进入持续发光的 Glow 表面。

### 2.2 配置草稿

来源配置属于 Home 的工作流，可以表现为内嵌卡片、子页面或覆盖层，但不是播放器底部的一组调试控件。

配置使用 `SessionDraft`，当前播放使用 `ActiveSession`。草稿只有在用户明确选择“开始播放”或“应用更改”后，才原子地替换活动会话。

播放源和字幕源是相互独立的槽位：

- 可以先选播放源，也可以先选字幕源；
- 选择顺序不会清空另一侧已经完成的选择；
- 文件选择器被取消时，草稿保持不变；
- 更换播放源时保留外部字幕并重新校验；
- 旧媒体的内嵌字幕轨随旧媒体一起失效，不迁移到新媒体；
- 解析失败的字幕仍保留为带错误状态的草稿项，用户可以重试或替换；
- 修改草稿时，现有活动会话可以继续播放，直到新草稿被应用。

播放源是开始播放的唯一硬性要求。字幕缺失时给出明确状态，但不阻止普通播放。

### 2.3 Ready Resolver

Ready Resolver 不再是固定必经页面，而是配置草稿中的状态与恢复动作集合。它只在下列情况出现：

- 播放源缺失、不可读或不含可播放音轨；
- 字幕缺失；
- 字幕格式无法识别、解析失败或内容为空；
- URI 权限失效；
- 字幕与媒体时长明显不匹配；
- 播放器准备或解码失败。

警告与错误必须区分：字幕时长可疑属于警告，不能自动删除用户选择；文件不可读或没有有效时间轴才属于阻断该字幕源的错误。

每个错误都提供贴近原因的恢复动作，例如“重新定位媒体”“替换字幕”“移除字幕后继续”或“重试”。

### 2.4 Normal Player

普通播放器负责播放、定位和临时校准。默认信息层次为：

1. 媒体标题与必要状态；
2. 上一句、当前句和下一句字幕语境；
3. 当前时间、总时长与可拖动进度；
4. 后退 10 秒、播放/暂停、前进 10 秒；
5. 倍速、字幕偏移和字幕选择；
6. 显示设置；
7. 视觉优先级明确的“进入微光”。

Normal Player 必须明确表现 `preparing`、`buffering`、`ready`、`playing`、`paused`、`ended` 和 `failed`，不能用同一个“播放”文字覆盖所有状态。

从 Normal Player 返回 Home 时，后台播放不自动停止。更换来源进入配置草稿，只有应用后才替换当前播放。

### 2.5 Glow

Glow 是播放页内部的专用阅读模式，而不是用一个布尔值临时隐藏同一组配置控件。

控制隐藏时只能持续显示：

```text
纯黑背景 + 当前 cue
```

字幕 gap、播放结束或播放失败时立即清空当前 cue。暂停时可以暂时保留当前 cue，但应用不为暂停状态无限保持屏幕唤醒。

单击显示或隐藏临时控制。临时控制至少包含：

- 后退 10 秒；
- 播放或暂停；
- 前进 10 秒；
- 进度与时间；
- 倍速；
- 字幕偏移；
- 退出微光。

临时控制自动隐藏。控制可以比字幕更亮，但不能因此把整个窗口亮度瞬间提高。

### 2.6 Back 与系统表面

- Glow 中按 Back 返回 Normal Player；
- Normal Player 中按 Back 返回 Home，播放按当前状态继续；
- Home 中按 Back 退出应用界面，不因此停止正在进行的后台播放；
- Home 与 Normal Player 使用正常可读的深色系统表面；
- 只有 Glow 进入沉浸显示并隐藏系统栏；
- 音量键保持系统默认行为；
- 耳机键、蓝牙键和锁屏控制通过 `MediaSession` 工作；
- 所有手势都有至少 `48dp × 48dp` 的可见控件替代。

## 3. 播放源与字幕源

### 3.1 播放源

M1 使用 Storage Access Framework 选择本地媒体：

```text
ACTION_OPEN_DOCUMENT(audio/*, video/*)
→ takePersistableUriPermission
→ 探测媒体信息与可播放音轨
→ 写入 SessionDraft
```

- 不请求广泛存储权限；
- 不把 `content://` 转换成真实路径；
- 不复制大型媒体到应用私有目录；
- 视频文件只提供音轨和时间轴，不建立视频 Surface；
- 不含可播放音轨的文件以可恢复错误处理；
- 文件被移动、删除或授权失效时，让用户重新定位，而不是崩溃。

### 3.2 字幕来源类型

领域模型从一开始区分：

```text
ExternalSubtitle   外部字幕文件
EmbeddedTrack      媒体容器内的定时文本轨
MetadataLyrics     音频标签中的歌词文本
```

M1 的确定交付范围是 `ExternalSubtitle`。`EmbeddedTrack` 与 `MetadataLyrics` 保留在模型边界中，但不阻塞本阶段完成。

### 3.3 外部字幕格式

M1 至少支持 TransVortex 当前可以导出的四类文件：

| 格式 | 扩展名 | 当前要求 |
| --- | --- | --- |
| SubRip | `.srt` | 时间、纯文本、多行与双语内容 |
| LRC | `.lrc` | 时间标签、offset、重复时间标签与无结束时间推导 |
| WebVTT | `.vtt` | cue 时间与文本；展示设置按荧荧规则归一化 |
| SubStation Alpha | `.ass`、`.ssa` | Dialogue 时间与文本；兼容 TransVortex 的 `Source` / `Target` 双语样式 |

这里的“支持”指把时间、文本和必要的双语角色解析成荧荧自己的字幕文档，不承诺复刻 ASS 字体、定位、动画、描边或特效，也不承诺复刻 WebVTT 的网页布局。

TransVortex 的真实 SRT、ASS、WebVTT 和 LRC 输出必须进入 parser fixtures。ASS 中同一时间范围的 `Source` 与 `Target` Dialogue 应组合为一个 cue 的多个文字块，而不是互相覆盖。

编码至少覆盖：

- UTF-8 与 UTF-8 BOM；
- UTF-16 LE / BE BOM；
- 常见 GB18030 字幕；
- LF 与 CRLF；
- 合法多行 cue；
- 格式可识别但内容损坏的明确错误。

### 3.4 内嵌字幕与标签歌词

容器中的真正字幕轨和音频标签歌词不是同一能力：

- MKV、MP4、WebM 等可能包含可由 Media3 发现的定时文本轨；
- FLAC、Ogg 等可能包含 Vorbis Comment；某些工具会把普通歌词或 LRC 文本放入自定义字段；
- 标签歌词没有足够统一的定时字段，不能默认视为可靠字幕轨。

实施顺序固定为：

1. 完成四类外部格式；
2. 探索 Media3 内嵌字幕轨的发现、选择和时间轴接入；
3. 用真实 FLAC / Ogg 样本验证 `LYRICS` 等标签约定；
4. 只有能稳定识别带时间的内容时，才把标签歌词升级为正式能力。

## 4. 字幕时间与统一文档

### 4.1 播放器时间是唯一真相

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
- `SubtitleTimeline` 保持为纯 Kotlin 逻辑并覆盖边界测试。

### 4.2 统一字幕文档

不同格式统一进入能够保留多文字块的内部模型：

```kotlin
data class SubtitleDocument(
    val format: SubtitleFormat,
    val cues: List<SubtitleCue>,
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val blocks: List<SubtitleBlock>,
)

data class SubtitleBlock(
    val text: String,
    val role: SubtitleRole,
)
```

`role` 用于表达 primary、secondary 或 unknown，不依赖某种文件格式的视觉样式。Glow 可以按显示预设决定只显示主文字或同时显示多个文字块。

## 5. 显示与亮度

### 5.1 三套亮度职责

显示亮度不再由一组实验滑杆同时控制所有表面：

1. **工作层亮度**：Home 与 Normal Player 默认跟随系统，保持正常可读，不继承 Glow 的低亮度；
2. **Glow 窗口亮度**：进入 Glow 后使用单独的低窗口亮度；
3. **Glow 像素亮度**：字幕与临时控制分别设置颜色强度，控制可以高于字幕。

退出 Glow 时恢复进入前的窗口亮度。应用不修改设备全局亮度设置。

### 5.2 显示预设

```kotlin
data class DisplayProfile(
    val glowWindowBrightness: Float,
    val glowSubtitleLevel: Int,
    val glowControlLevel: Int,
    val fontScale: Float,
    val verticalPosition: VerticalPosition,
)
```

普通用户界面优先展示“柔和”“平衡”“高可读”等预设；精确数值可以放入高级设置，不再把 `RGB 12` 作为主要产品语言。

显示预设作为用户默认值保存，播放会话可以保存自己的覆盖值。

### 5.3 OLED 与系统行为

- Glow 根表面固定为 `#000000`；
- 字幕默认略低于屏幕中心并保持稳定，不默认周期漂移；
- `keepScreenOn` 只随实际播放或短时交互启用；
- 不尝试关闭自动亮度、Extra Dim、护眼或色彩校正；
- 不承诺零功耗、零残影或零烧屏；
- 来电、闹钟、通知和系统亮色浮层属于平台行为，应用只保证自身不制造非必要的常驻亮色表面。

## 6. 会话、用户数据与缓存

### 6.1 会话模型

```kotlin
data class PlaybackSession(
    val id: String,
    val media: MediaSourceRef,
    val subtitleSources: List<SubtitleSourceRef>,
    val activeSubtitleId: String?,
    val positionMs: Long,
    val playbackSpeed: Float,
    val subtitleOffsetMs: Long,
    val displayProfile: DisplayProfile,
)
```

产品状态至少包含：

- `SessionDraft`：尚未应用的来源配置；
- `ActiveSession`：服务当前播放的会话；
- `LastSession`：服务结束后可以恢复的最后会话；
- 用户默认设置。

领域模型不绑定具体存储技术，以便以后从单会话扩展到有限历史记录。

### 6.2 持久化边界

M1 使用 DataStore 级别的轻量存储保存：

- 配置草稿；
- 最后会话；
- 媒体和字幕 URI、显示名、MIME、可用的大小与更新时间；
- 播放位置、倍速、字幕偏移和活动字幕；
- 显示预设与应用设置。

播放位置在暂停、seek、进入后台、会话切换和受限频率的播放检查点写入，不能跟随 50ms UI 刷新频率持续写盘。

### 6.3 字幕副本与派生缓存

- 大型媒体永不因普通播放被复制到应用目录；
- 外部字幕体积较小，在持久 URI 不可靠或需要稳定恢复时，可以保存应用私有的规范化副本；
- 规范化字幕副本属于用户会话数据，不等同于随时可删除的缓存；
- 解析索引、格式探测结果和可重新生成的媒体元数据放入 cache，允许清理；
- 日志、崩溃报告和诊断信息默认不记录字幕全文或本机路径；
- Home 设置提供清理派生缓存和移除最后会话的入口。

只有出现多个历史会话、查询、筛选或媒体库需求时才评估 Room；M1 不为了“以后可能需要”提前引入数据库。

### 6.4 权限与恢复

- 对支持的 Provider 获取持久读取权限；
- Provider 只授予进程期权限时，应用必须记录这一限制；
- 恢复时先校验媒体，再校验字幕；
- URI 失效时保留其余会话设置并请求重新定位；
- 重新定位成功后继续使用原位置、偏移、倍速与显示预设；
- 恢复失败是普通业务状态，不以崩溃或短暂 Toast 处理。

## 7. 播放架构

### 7.1 结构

```text
PlaybackService : MediaSessionService
├─ ExoPlayer
├─ MediaSession
└─ ActiveSession

MainActivity
├─ Home
├─ Player
│  ├─ Normal Player
│  └─ Glow
└─ MediaController

SessionRepository
├─ SessionDraft
├─ LastSession
└─ UserPreferences

SubtitleRepository
├─ SrtParser
├─ LrcParser
├─ WebVttParser
├─ AssParser
└─ SubtitleTimeline
```

Player 与 MediaSession 由 `MediaSessionService` 持有。Activity 通过 `MediaController` 展示状态和发出命令，不拥有第二个播放器。

### 7.2 后台与系统控制

- 离开 Activity、返回 Home 或关闭屏幕后，正在播放的音频继续；
- 通知、锁屏、耳机和蓝牙控制与同一个 MediaSession 同步；
- 音频焦点丢失遵循平台行为；
- 耳机或蓝牙断开时暂停，避免扬声器意外外放；
- 系统杀死服务后，可以使用 LastSession 恢复媒体、位置和必要参数；
- 媒体通知显示本地可用的标题等元数据，不把当前字幕伪装成不断变化的歌曲标题。

### 7.3 保持简单

- 继续使用单一 `app` 模块；
- 使用 Kotlin、Compose 和 Media3；
- 不引入 DI framework、多模块、Room、FFmpeg 或自建媒体库；
- 先建立完整会话路径，再按真实复杂度抽取边界；
- UI 状态、服务状态和持久化状态必须有明确单向转换，不能各自保存一份互相猜测的播放器状态。

## 8. 阶段与实施顺序

### 8.1 M0A：完成

首轮 Vivo OLED 暗室测试已经证明“纯黑背景视觉消失、低灰字幕可读”的基本物理可行性，记录见 [`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)。

### 8.2 M0B：原型基线形成

现有代码已经形成本地音频、LRC/SRT、播放器时间轴、seek、字幕偏移和基础 Glow 的可运行原型，满足继续进入产品形态重构的工程前提。

仓库尚未记录一次完整的 M0B 真实媒体真机播放。该验证不再阻塞 M1A 开始，但必须并入下一次播放器真机 smoke test，并明确记录发现的问题。

### 8.3 M1A：产品形态与真实播放基础

实现状态：代码路径已完成；后台、锁屏、通知、耳机控制和真实媒体兼容性并入 M1 集中真机验收。

目标：从单页验证器转为 Home + Player，并建立真正的后台播放器。

范围：

1. 定义 `SessionDraft`、`ActiveSession`、`LastSession` 与来源状态；
2. 将 ExoPlayer 迁入 `MediaSessionService`；
3. Activity 通过 `MediaController` 控制播放；
4. 建立 Home、Normal Player 与 Glow 导航骨架；
5. 接入后台、通知、锁屏和外部媒体按键；
6. 显示完整播放状态和可恢复错误。

完成条件：

- 应用冷启动进入 Home；
- 可以建立一个播放会话并进入 Normal Player；
- 返回 Home、切到其他应用或关闭屏幕后播放继续；
- 通知、锁屏与耳机控制状态正确；
- Glow 与 Normal Player 是明确模式，不再承载来源配置。

### 8.4 M1B：健壮来源与会话恢复

实现状态：代码路径已完成；冷启动、进程重建、Provider 授权失效和重新定位保留参数仍需真机验收。

目标：让来源选择顺序、进程重建和文件失效都成为可预测状态。

范围：

1. 实现草稿原子应用；
2. 支持先字幕后媒体、先媒体后字幕和取消 picker；
3. 保存并恢复一个 LastSession；
4. 实现 URI 权限检查与重新定位；
5. 新增 WebVTT 与 ASS/SSA parser；
6. 使用 TransVortex 输出建立兼容性 fixtures；
7. 建立字幕规范化副本与派生缓存边界。

完成条件：

- 更换媒体不会无条件清空外部字幕；
- 失败草稿不会破坏当前活动播放；
- SRT、LRC、WebVTT、ASS/SSA 都能进入统一时间轴；
- 再次打开应用可以恢复媒体、字幕、位置、偏移、倍速和显示预设；
- 文件授权失效时可以重新定位，而不是崩溃。

### 8.5 M1C：播放与微光体验完成

目标：让 Normal Player 可用于定位和校准，让 Glow 可持续阅读。

范围：

1. 完成当前 cue 与相邻语境；
2. 完成进度、±10 秒、倍速、偏移和字幕选择；
3. 分离工作层、Glow 窗口、Glow 字幕和 Glow 控制亮度；
4. 提供显示预设、字号与垂直位置；
5. 完成控制自动隐藏、Back、无障碍和系统栏行为；
6. 使用真实音频、视频容器和四种字幕格式做真机 smoke test。

### 8.6 M1 完成条件

- 用户可以从 Home 打开本地音频或视频容器；
- 用户可以按任意顺序选择媒体与外部字幕；
- 普通播放、seek、倍速和 offset 后 cue 正确；
- 屏幕关闭后音频继续，系统媒体状态准确；
- 再次打开可以继续最后会话；
- SRT、LRC、WebVTT 与 TransVortex ASS 输出均可读取；
- 文件权限失效时有完整恢复路径；
- Normal Player 与 Glow 使用不同亮度职责；
- Glow 隐藏状态没有非字幕常驻 UI。

### 8.7 M1 明确不做

- 视频 Surface 和视频观看模式；
- 完整 ASS / WebVTT 视觉样式复刻；
- 把内嵌字幕轨或 FLAC 标签歌词作为发布阻断项；
- 媒体扫描、完整最近列表和媒体库；
- 队列、随机播放、推荐、收藏和社交；
- 均衡器和音效系统；
- ASR、翻译、字幕编辑和在线字幕搜索；
- `.vtxsub`、TransVortex 自动配对和云任务；
- 账号系统；
- FFmpeg、Room、DI framework 和多模块架构。

## 9. 验证原则

默认运行与改动直接相关、范围最小的检查，再按风险扩大：

- 字幕 parser 和统一时间轴使用纯 Kotlin 单元测试；
- SessionDraft 状态转换覆盖选择顺序、取消、失败和原子应用测试；
- 服务、Manifest 或 MediaController 改动运行 `gradlew.bat assembleDebug`；
- 共享 UI 与资源改动按需运行 `gradlew.bat lint`；
- 后台播放、锁屏、媒体按键、耳机断开和 URI 恢复必须使用真机 smoke test；
- Glow 验证必须覆盖进入/退出亮度恢复、字幕 gap、播放结束和系统浮层；
- 至少用一个音频文件、一个含音轨的视频容器，以及 SRT、LRC、WebVTT、ASS 各一个真实样本；
- 无法运行的检查要记录原因和未覆盖风险。

多品牌 OLED、LCD 对照、长时间功耗和残影矩阵放到公开发布前或发现对应问题时处理，不阻塞当前切片。

## 10. 参考资料

- M0A 记录：[`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)
- 产品与交互研究：[`reference/research.md`](reference/research.md)
- 项目设计种子：[`reference/PROJECT_SEED.md`](reference/PROJECT_SEED.md)
- 品牌探索：[`brand/README.md`](brand/README.md)

参考资料中的长期设想、竞品观察和实验建议不会自动进入范围。需要采用时，先把结论收敛后写入本文。
