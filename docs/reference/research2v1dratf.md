# VortexCue / 荧荧产品再研究结论

**研究日期：2026-08-10**

我按委托书要求，把当前实现当作证据而不是答案，并重点寻找了三类反证：**直接锁屏是否已经足够、字幕是否根本难以取得、完整媒体库是否其实才是用户需要的东西**。委托书本身也明确要求允许项目被证伪，而不是为现有代码或早期惊艳体验寻找合理性。

先给结论：

> **荧荧应该回到“字幕是主内容”的方向。**
>
> 但我不会把它重新定义成早期那个纯粹的“单会话字幕播放器”。
>
> 更准确的产品形态应该是：
>
> **一个意图优先（intent-first）、字幕优先（text-first）的夜间听读器。**
>
> 用户带来一个明确想继续的内容，荧荧负责尽快建立可信的“媒体 + 同步文字”关系，然后逐渐把播放器界面退掉，只剩文字发光。
>
> **资源库应该成为支撑层，不再成为产品入口。**

这基本确认委托书中“方向 3：会话优先、资源库可选”，但研究后我会把它再往前推一步：**真正的一等对象不是“Session”，也不是“MediaItem”，而是一个可播放的阅读单元；Session 只是它的一次使用状态，Folder 是它的自然集合。**

---

# A. 一页决策摘要

| 决策                 | 研究结论                                                                                                                                 |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| **第一核心用户**     | 夜间消费**已有同步文字的本地、以语言内容为主的长视频**的人：访谈、课程、演讲、纪录内容、talking-head、部分剧集等。首批不要以音乐用户为主 |
| **第一 JTBD**        | **“夜里我还想继续听懂这段内容，但不想让整块视频/播放器继续发光；让我从上次那句话继续，只留下我需要看的文字。”**                          |
| **第一产品身份**     | **字幕优先的夜间听读器**，而不是“有微光模式的本地播放器”                                                                                 |
| **传播钩子**         | **“只让字幕发光。”** 仍然成立，而且比“OLED 本地播放器”更有产品辨识度                                                                     |
| **内部基本对象**     | `PlayableReadingUnit`：一个媒体来源 + 0..N 个同步文字轨 + 当前选择 + 进度 + offset + 来源/匹配依据 + 显示偏好                            |
| **用户看到的对象**   | “内容”“字幕”“文件夹”“继续”；不要让用户理解 Session / Unit / Track 等内部术语                                                             |
| **首页第一对象**     | **继续上次那一句**                                                                                                                       |
| **首次新内容优先级** | **打开一个媒体 > 打开一个文件夹 >>> 扫描设备**                                                                                           |
| **首次价值证明**     | 在任何全盘权限请求前用内置样片证明微光；校准融入体验，不要先做设置向导                                                                   |
| **字幕第一阶段**     | **内嵌字幕 → 已记忆关系 → 授权文件夹中的同目录外挂字幕 → 手动选择字幕**                                                                  |
| **全设备扫描**       | 降级到“更多来源/建立资源库”；不再默认，不在冷启动前台自动扫描                                                                            |
| **资源库**           | **保留底层能力，重构产品表面**。Folder/Recent/Association 可以保留；“待整理→收录→首页内容集”不应挡住高意图播放                           |
| **无字幕 Glow**      | 能力可以留，但退出主叙事和主 CTA；否则产品会重新滑向普通音频播放器                                                                       |
| **ASR**              | 暂停。只有验证出“喜欢 Glow，但大量内容因为没字幕而流失”后才升级到 P2/P1                                                                  |
| **下一轮唯一切片**   | **“打开一个内容 → 找到/选中可信字幕 → 字幕中心播放 → 微光 → 第二天一键回到同一句”**                                                      |

当前版本实际上已经从早期“字幕→微光”的极短路径，演化成“扫描→整理→收录→内容集→队列→播放器→微光”；委托书自己也记录了维护成本从低上升到中高，以及“独特价值后置”的风险。

---

# B. 为什么第一核心用户应该再缩窄

这里有个很重要的区别：

**公开资料无法证明“哪类人最常在晚上需要这种产品”。**

没有可靠公开统计告诉我们：

- 有多少 Android 用户拥有本地视频 + SRT；
- 其中多少人晚上只想听而不想看画面；
- 其中多少人愿意持续看同步字幕；
- 文件夹组织、内嵌字幕、外挂字幕各占多少比例。

因此，我不会虚构“市场最大的是 XX”。

我的“第一用户”选择是依据 **问题—产品匹配强度**，而不是市场规模。

### 第一核心用户：[H] 本地语言型视频 + 已有字幕

最契合的是：

**已经拥有视频和同步字幕，但视频画面对当下任务并非持续必要的人。**

比如：

- 外语采访；
- YouTube/课程下载内容；
- lecture / conference / talk；
- 纪录内容；
- 播客视频版；
- 以对白推动为主、用户已经在追的内容。

为什么它比“所有本地视频用户”更准确？

因为真正的 JTBD 不是：

> 我想播放一个视频。

而是：

> **画面此刻不是重点，但没有文字我又不能很好地理解声音。**

这才制造“音频继续 + 字幕继续 + 画面退出”的产品空间。

### 音乐 + LRC 不适合成为第一用户

不是音乐歌词没有需求，恰恰相反——**它太成熟了**。

Apple Music 已经把时间同步歌词放进 Now Playing，可直接点某一句跳转，并支持翻译/发音；Spotify 在 2026 年继续把歌词前置到播放界面并全球扩展翻译；Musixmatch 有逐行、逐词、翻译、浮动歌词和歌词 seek；Poweramp 早已支持 SYLT/LRC，Symfonium 则同时支持同步、嵌入和 provider lyrics。([苹果支持][1])

所以：

> **“一个更漂亮、更黑的本地歌词页”不是足够独立的产品。**

音乐应该兼容，但不是 MVP 的产品锚。

### 播客/有声课程则是第二阶段潜在大方向

它们与荧荧的**使用状态**高度吻合——长时间、睡前、恢复、睡眠定时——但很多内容没有现成 timed text。

因此它们非常有价值，但更像是在告诉我们：

> 如果 Glow 被验证成立，而字幕取得成为主要瓶颈，**ASR 才真正获得产品理由。**

这与委托书原来的判断一致。

---

# C. 最重要的外部研究结论

## 1. Android 并没有逼我们建立全设备媒体库

这是本轮最明确的技术结论之一。

Android SAF 原生就区分：

- `ACTION_OPEN_DOCUMENT`：用户选择一个明确文件；
- `ACTION_OPEN_DOCUMENT_TREE`：用户明确授权一个目录及其子目录。

目录授权的核心价值恰恰就是：**用户决定哪棵树交给应用，而不是应用先扫描一切。** ([Android Developers][2])

单文件授权不能可靠地拿来“顺手遍历父目录”；`DocumentFile` 的父子关系只在用户授权的 tree 语境里有可靠定义。这支持委托书当前的技术判断。([Android Developers][3])

而 Just (Video) Player 已经采用了非常接近我们需要的现实策略：

> 普通情况下直接打开视频；如果用户希望自动加载外挂字幕，再让用户授权视频根目录。

也就是说：

**“即时单文件 + 按需文件夹授权”已经是 scoped-storage 世界中的真实产品模式。** ([GitHub][4])

所以，没有平台理由让“扫描本机”成为第一 CTA。

---

## 2. MediaStore 字幕能力值得保留，但不值得成为产品架构中心

Android API 30 起的确定义了：

`MEDIA_TYPE_SUBTITLE`

表示字幕或歌词文件，MediaStore 也有 parent 信息。([Android Developers][5])

所以当前“全机扫描完全找不到字幕”的实现不是 Android 的绝对上限。

但这项能力只能得出：

> **MediaStore 可以成为额外的发现索引。**

不能得出：

> MediaStore 应该成为荧荧所有内容关系的真相来源。

它依赖系统是否索引、OEM、文件位置、Android 版本、provider 等现实条件。

因此我的优先级是：

**明确 URI / 内嵌轨 > 用户授权 Folder > MediaStore opportunistic discovery。**

---

## 3. 内嵌字幕应该明显提前

这也是当前委托书低估了一点的地方。

Media3 本身已经把字幕当作正式 text track。字幕配置可以包含：

- language
- label
- role flags
- selection flags
- MIME type
- URI

而 TrackSelectionParameters 甚至原生支持 preferred text languages、labels、roles 和系统 caption preferences。([Android Developers][6])

Media3 当前也支持 MP4、WebM、Matroska 等大量 progressive container，以及 WebVTT、TTML、SubRip、SSA/ASS 外挂字幕。([Android Developers][7])

因此如果用户打开一个 MKV：

> **第一件事不应该是让他再找 SRT。**

而应该是：

> **先看这个媒体自己已经带了哪些文字轨。**

这具有三个产品优势：

1. 零额外权限；
2. 零文件名匹配；
3. 有天然的 language / role / default 元数据。

所以我会把 **内嵌字幕接入提升到 P0/P1 边界，而不是放在遥远未来。**

---

# D. 产品基本对象：我选择模型 C

当前：

```text
MediaItem
└── optional SubtitleSourceRef
```

已经不够用了。

委托书指出当前 `SubtitleCue` 本质只是：

```text
startMs + endMs + text
```

因此语言、来源、多轨、primary/secondary、匹配依据都丢失了。

我建议产品概念变成：

```text
Playable Reading Unit
│
├── Media
│
├── Timed Text Tracks [0..N]
│   ├── Embedded
│   ├── Sidecar
│   ├── Lyrics
│   └── Generated      ← future
│
├── Active Text
│   ├── Primary
│   └── Secondary?     ← optional
│
├── Reading State
│   ├── playback position
│   ├── current cue
│   ├── offset
│   └── last surface: Normal / Glow
│
└── Relationship Memory
    ├── language
    ├── provenance
    ├── match confidence
    ├── manually selected?
    └── rejected candidates
```

**但这个名词绝不能出现在 UI。**

用户看到的是：

```text
内容
字幕
继续
文件夹
```

### Folder 是 Unit 的集合

```text
Folder
├── Unit A: episode01.mkv + zh + en
├── Unit B: episode02.mkv + zh
└── Unit C: episode03.mkv + no subtitle
```

于是：

- Queue 是 Unit 的顺序；
- History 是 Unit 的使用历史；
- Continue 恢复 Unit；
- Folder 包含 Unit；
- Playlist 若未来存在，也是 Unit 集合。

这样字幕终于不是 `MediaItem` 旁边一个小图标。

---

# E. 字幕发现闭环

这里我建议引入一个非常重要的规则：

## **自动发现 ≠ 自动启用**

这是整个字幕系统降低错误成本的关键。

mpv 当前默认 `--sub-auto=exact`，更宽松的 fuzzy/all 需要用户主动选择；Kodi/Jellyfin 也长期利用语言、forced、SDH、CC 等 suffix 表达多个文字轨，而不是强行把字幕压成一个布尔状态。([mpv][8])

因此：

```text
发现
 ↓
候选
 ↓
判断“是不是这个媒体的字幕”
 ↓
建立关联
 ↓
判断“用户现在应该看哪一轨”
 ↓
激活
```

这是两个问题。

### 推荐规则

| 来源                             |           是否自动关联 |                      是否自动激活 |
| -------------------------------- | ---------------------: | --------------------------------: |
| 容器内嵌字幕                     |                     是 | 有明确 default / 已知语言偏好时是 |
| 用户此前手选过                   |                     是 |                **是，最高优先级** |
| 同目录完全同 stem                |                     是 |                    唯一候选可自动 |
| `movie.zh.srt / movie.en.srt`    |               全部关联 |        根据语言偏好，否则让用户选 |
| `.forced/.sdh/.cc`               |         关联并标记角色 |                      根据用户偏好 |
| 可解释的 S01E03 / release suffix |               建议关联 |                                否 |
| 模糊文件名                       |                 仅候选 |                                否 |
| 跨目录模糊候选                   | 默认不出现或进入“其他” |                              绝不 |

这样即使：

```text
movie.en.srt
movie.zh.srt
movie.zh-Hans.srt
movie.en.sdh.srt
```

也完全没必要选出“最短文件名”然后把其他三个扔掉。

---

# F. 字幕 UI 应该呈现什么

正常用户其实不需要看到“置信度 97%”。

他真正需要回答的是：

> **这是哪一个字幕？**

因此字幕选择器建议类似：

```text
字幕

✓ 中文
  外挂 · movie.zh.srt

  English
  内嵌 · English

  English · SDH
  内嵌 · Hearing Impaired

  日本語
  外挂 · movie.ja.srt

────────────
+ 选择其他字幕
```

只有出现可疑匹配时再说：

> “在同一文件夹发现”
>
> “文件名与视频匹配”

而不是：

> Confidence = 0.88

### 手动选择必须永久压过自动规则

这是非常重要的信任原则。

用户说过一次：

> “用这个。”

下一次就不要自作聪明。

只有用户主动：

> “恢复自动选择”

才重新参与算法。

同样：

> **被用户明确拒绝过的候选要记住。**

---

# G. 正常播放器不应该复制 Apple Music

Apple Music 证明了三件事：

- 当前文字跟播放联动有效；
- 上下文可以弱化而非消失；
- 点某句话 seek 是成熟交互。([苹果支持][1])

但长内容给我们更重要的参考反而是 Apple Podcasts 和 YouTube。

Apple Podcasts 的 transcript 可以：

- 随播放推进；
- 浏览上下文；
- 搜索；
- 点某段跳转播放。

YouTube 的 Transcript 同样随视频滚动，并允许点击任意字幕行跳到相应时间。([苹果支持][9])

所以荧荧 Normal Player 更像：

```text
        上一句
        ↓ 弱

    当前这一句
      ← 主视觉 →

        下一句
        ↓ 弱

────────────────

      -10   ▶   +10

        进入微光
```

而不是传统：

```text
封面
标题
歌手
进度条
播放列表
歌词 Tab
```

### P0

- 上一句；
- 当前句；
- 下一句；
- 点句跳转；
- 一句重听；
- offset；
- Primary / Secondary；
- 进入 Glow。

### P1

再加入：

- 可滚动 transcript；
- 用户手动滚动后“回到当前句”；
- transcript 搜索；
- 更丰富的语言切换。

### 暂停逐词高亮

因为它需要更细粒度时间数据，而 SRT/ASS 等目标素材通常只有 cue 级时间。

在歌词领域逐词高亮很成熟，但这并不意味着长字幕需要它。

---

# H. Glow 应该重新成为“结果”，而不是一个模式

当前版本里：

> 无字幕也突出“无字幕 · 进入微光”。

这是工程上合理、产品上危险的。

因为此时：

```text
没有字幕
+
整个屏幕纯黑
```

与：

> **锁屏**

之间的价值差异急剧缩小。

委托书自己也已经识别这一点。

所以应该改成：

### 有同步文字

**主 CTA：**

> 进入微光

### 没有同步文字

主 CTA 应该变成：

> 添加字幕

次级：

> 继续播放

无字幕 Glow 可以存在于更多菜单、快捷手势或兼容能力中。

**不要删除能力，只是不要让它定义产品。**

---

# I. Home：资源库降级以后长什么样

我建议最终接近：

```text
┌──────────────────────────────┐
│ 荧荧                         │
│                              │
│  继续上次那一句              │
│  ─────────────────────────   │
│  The Interview               │
│  42:18 · 中文 + English      │
│                              │
│       [ 继续微光 ]           │
│                              │
│                              │
│  ＋ 打开一个内容             │
│  ▣ 打开一个文件夹            │
│                              │
│  最近                        │
│  Course 03       中文        │
│  Episode 7       中 / 英     │
│  Lecture         待选字幕    │
│                              │
│  更多来源                    │
└──────────────────────────────┘
```

### “更多来源”里面才是：

```text
扫描设备媒体
已授权文件夹
管理来源
```

而不是首页第一屏。

---

# J. 首次使用

我赞成委托书的内置样片，但会进一步砍短。

### 当前推荐

```text
打开荧荧
   ↓
一句话解释
“夜里继续听懂，只让字幕发光。”
   ↓
[体验 1 分钟样片]
   ↓
正常字幕页
   ↓
界面退去
   ↓
Glow
   ↓
“打开你自己的内容”
```

**在用户看到 Glow 前：**

- 不扫媒体；
- 不建库；
- 不问歌手/播放列表；
- 不要大权限；
- 不做五步 onboarding。

亮度校准应该嵌在样片里。

例如第一次 Glow 时：

```text
文字看起来舒服吗？

暗  ─────●──  亮
```

而不是先把用户送进一个“屏幕校准向导”。

产品首先要证明：

> **为什么值得给你文件。**

之后才解释文件权限。

---

# K. 回访：这里应该大胆一点

当前从 Continue 恢复只进入 Normal Player，而不会恢复之前的 Glow 表面。

我认为应该改。

如果上一次：

- 同一个 Unit 仍可读取；
- 字幕关系仍有效；
- 用户退出时处于 Glow；

那么首页：

> **继续微光**

应该直接回到：

```text
媒体
+ 字幕
+ position
+ offset
+ primary/secondary
+ Glow brightness
+ Glow surface
```

**一次操作。**

失败才退回 Normal：

> 字幕文件已移动，请重新选择。

“第二天继续”是长内容产品极成熟的需求。AntennaPod 首页第一部分就是 Continue Listening；Voice Audiobook Player 把“准确从停止处继续”、folder import 和 sleep timer 都作为核心功能。([AntennaPod][10])

甚至 AntennaPod 在长暂停后会自动稍微倒退几秒，帮助重新建立听觉上下文——这至少证明“恢复不仅是恢复 timestamp，还包括重新获得语境”是长内容的真实设计问题。([AntennaPod][11])

荧荧则可以利用自己的优势：

> **恢复到那句话。**

---

# L. 四个方向比较

这是产品判断，不是市场统计。

|                | 方向 1 单会话 | 方向 2 文件夹优先 | **方向 3 会话优先/库可选** | 方向 4 深化资源库 |
| -------------- | ------------: | ----------------: | -------------------------: | ----------------: |
| 首次价值速度   |         ★★★★★ |               ★★★ |                  **★★★★★** |                ★★ |
| 字幕中心性     |         ★★★★★ |              ★★★★ |                  **★★★★★** |                ★★ |
| 单次文件       |         ★★★★★ |                ★★ |                  **★★★★★** |               ★★★ |
| 系列内容       |            ★★ |             ★★★★★ |                   **★★★★** |             ★★★★★ |
| 隐私可解释性   |         ★★★★★ |             ★★★★★ |                  **★★★★★** |               ★★★ |
| 长期恢复       |           ★★★ |              ★★★★ |                  **★★★★★** |             ★★★★★ |
| 产品差异化     |         ★★★★★ |              ★★★★ |                  **★★★★★** |                ★★ |
| 与现有代码复用 |           ★★★ |              ★★★★ |                  **★★★★★** |             ★★★★★ |
| 最大风险       |          太轻 |     Folder 不普适 |             双入口概念混乱 |    变成普通播放器 |
| 结论           |      验证原型 |        重要子路径 |                   **推荐** |              暂停 |

### 为什么方向 3 赢

不是因为它折中。

而是它可以建立一个非常清楚的优先级：

```text
用户当前意图
    >
历史
    >
用户明确授权的 Folder
    >
完整 Library
```

方向 3 最大的问题原本是“两套入口”。

解决方式不是减少入口，而是：

> **不要把“会话”和“资源库”暴露成两个产品概念。**

用户只理解：

- 我现在打开这个；
- 我以后还可以继续；
- 这个文件夹以后也认识。

内部自己保存关系。

---

# M. 当前四条服务蓝图

## 当前：设备扫描

```text
启动
 ↓
恢复来源
 ↓
扫描
 ↓                 ← 等待 / 权限
253 项候选
 ↓
待整理
 ↓
收录/忽略          ← 维护工作
 ↓
首页内容集
 ↓
选内容
 ↓
Normal
 ↓
Glow               ← ★ 独特价值第一次出现
```

真机测试一次发现 95 音频 + 158 视频，而最后真正希望首页使用的是一个只有 7 项音频的文件夹。这个测试证明的是“大范围结果可以被安全整理”，并没有证明“大范围扫描是用户想先做的事”。

## 当前：文件夹

```text
选择 Folder
 ↓
SAF 权限
 ↓
递归扫描
 ↓
待整理
 ↓
选择
 ↓
收录
 ↓
设为首页内容集
 ↓
播放
 ↓
Normal
 ↓
Glow
```

这里最大的问题是：

> 用户已经明确把这个文件夹交给你了，却还需要证明一次“是的，我真的想用里面这些东西”。

委托书已经记录这一额外税负。

## 当前：单文件

```text
发现内容
 ↓
打开单文件
 ↓
选媒体
 ↓
（再次选字幕）
 ↓
播放
 ↓
Normal
 ↓
Glow
```

反而最接近核心价值，但被 IA 藏在了管理层里。

## 当前：继续

```text
Home
 ↓
Continue
 ↓
Normal
 ↓
再点 Glow
```

回访路径同样多了一层。

---

# N. 建议的新服务蓝图

## 单文件

```text
打开媒体
 ↓
检查已记忆关系
 ↓
检查内嵌字幕
 ↓
有字幕 ───────────→ Normal → Glow

无字幕
 ↓
[选择字幕]
[选择包含字幕的文件夹]
 ↓
建立关系
 ↓
Normal → Glow
```

## 文件夹

```text
选择 Folder
 ↓
明确授权
 ↓
扫描这个范围
 ↓
建立 Unit + 多字幕候选
 ↓
高置信自动关联
 ↓
Folder 可立即播放
```

没有：

> 待整理 → 收录 → 选择首页内容集

除非它是**完整设备扫描**。

## 回访

```text
Home
 ↓
继续上次那一句
 ↓
Glow
```

## 下一项

```text
当前 Unit 完成
 ↓
下一个 Unit
 ↓
加载其已关联 text tracks
 ↓
恢复该 Unit 的语言/布局/offset
 ↓
继续
```

---

# O. 系统“用荧荧打开”值得做

Android `ACTION_SEND` 可以把二进制内容以 content URI 交给另一个应用。([Android Developers][12])

因此长期来看一个非常自然的入口是：

```text
下载管理器 / 文件管理器
         ↓
       分享 / 打开
         ↓
        荧荧
         ↓
      临时 Unit
```

这特别符合：

> “我今晚只想看这个。”

它不应该要求：

> “请先构建您的荧荧媒体资料库。”

它甚至可能成为一个很漂亮的增长入口。

---

# P. 三个最重要的反证

## 反证 1：直接锁屏

这是最危险的竞争对手，不是 VLC。

如果用户看完 Glow 后仍觉得：

> “还不如锁屏听。”

那产品核心失败。

而且针对**完全不需要文字理解的音频用户**，我现在就认为锁屏大概率更合理。

因此这类用户不应该是荧荧主线。

---

## 反证 2：Live Caption

Android Live Caption 当前可以本地为视频、播客、通话、音频消息等语音生成字幕；Pixel 6+ 与部分 Android 设备支持包括英语、日语、中简繁、韩语等多种语言。它本地处理、不保存字幕、无需持续联网，但效果依赖设备和清晰语音、不支持音乐，并会增加电量消耗。([Google 帮助][13])

所以：

> **“我可以把没有字幕的语音变成文字”不是荧荧的独特价值。**

荧荧真正可以比它好的地方是：

- 已校准的时间轴；
- 正确文本；
- 多语言；
- 上下文；
- seek；
- 记忆；
- 第二天继续；
- 微光表面。

---

## 反证 3：VLC/mpv 已经足够

VLC Android 已经支持：

- 内嵌/外挂字幕；
- subtitle track；
- 外部字幕；
- 字幕延迟；
- 字幕历史；
- 在线字幕搜索等。

甚至同步 offset 有非常巧妙的：

> 听到声音 → 看到文字

两步语义校准，而不只是让用户输入毫秒。([VideoLAN 文档][14])

所以荧荧**绝对不能靠字幕功能数量打 VLC。**

它只能靠：

> **VLC 的终点是播放器；荧荧的终点是播放器消失。**

这就是产品边界。

---

# Q. 用户 / JTBD 矩阵

这里的“痛点强度”是**产品适配假设**，不是市场调查结果；“频率”目前公开研究无法回答。

| 用户                   | 内容     | 字幕来源          | 当前替代                 | 适配     | 频率证据               | 复用     |
| ---------------------- | -------- | ----------------- | ------------------------ | -------- | ---------------------- | -------- |
| 外语访谈/课程/演讲用户 | 本地视频 | 内嵌/SRT/ASS      | VLC/视频暗色             | **极高** | 未知                   | 高       |
| 剧集 Folder 用户       | 视频系列 | 多语言外挂/内嵌   | VLC/mpv/Kodi             | 高       | 未知                   | **很高** |
| 一次性下载用户         | 一个媒体 | 一个 SRT          | 文件管理器→播放器        | **高**   | 未知                   | 中       |
| 本地音乐 LRC           | 音乐     | LRC/SYLT          | Poweramp/Symfonium       | 中       | 已有成熟需求但竞争强   | 高       |
| 播客/课程音频          | 长音频   | 通常无现成字幕    | Podcast app/Live Caption | 潜在高   | 未知                   | 很高     |
| 有声书                 | 长音频   | 通常无 timed text | Voice/Smart ABP          | 低到中   | 成熟 resume 需求       | 很高     |
| 无字幕音乐用户         | 音频     | 无                | **锁屏**                 | **低**   | 无需验证即可看出替代强 | 高       |

---

# R. 需求优先级

## P0 — 让核心体验**可获得**

1. 内置样片；
2. 首页：继续 / 打开媒体 / 打开 Folder；
3. PlayableReadingUnit / 多 text track 产品模型；
4. 媒体内嵌字幕发现；
5. 手选外挂字幕；
6. Folder 同目录高置信发现；
7. 字幕语言/来源选择；
8. Normal Subtitle Player；
9. Glow；
10. 保存 media + track + position + offset + layout + last surface；
11. 失效关系有明确恢复动作。

### P0 的验收句

> **一个第一次使用荧荧的人，可以不建立资源库，拿一个有字幕的内容进入微光，并在第二天从同一句继续。**

---

## P1 — 让核心体验**可重复**

- Folder 批量建立 Unit；
- 多候选字幕；
- manual override / rejected candidate；
- primary + secondary；
- next Unit 自动恢复字幕；
- transcript；
- 点句跳转；
- 一句重听；
- VLC 式感知同步；
- 最近内容/Folder；
- “用荧荧打开”；
- sleep timer 保留；
- Folder 内有限顺序播放。

---

## P2 — 扩大内容覆盖

- MediaStore 字幕索引；
- LRC/SYLT/embedded lyrics 深化；
- ASR；
- TransVortex 一键接力；
- 在线字幕；
- 翻译；
- 自动双语；
- fuzzy matching；
- 云来源。

---

# S. 获得新证据前应暂停

这些不是“永远不做”，而是**现在继续做不会回答核心产品问题**。

暂停：

- 更完整的艺术家/专辑/分类；
- 首页内容集管理继续深化；
- 全设备自动扫描 UX；
- 冷启动全量重新扫描优化之外的功能扩展；
- 播放列表产品化；
- shuffle 功能深化；
- EQ；
- 封面生态；
- 在线音乐目录；
- 视频画面播放；
- 社交；
- 账号系统；
- 云同步；
- 在线字幕市场；
- ASR 自动生成；
- AI 翻译；
- 逐词动画；
- subtitle editor；
- 更多“播放器应该有的功能”。

现有 queue / shuffle / playlist 代码**不必删除**。

沉到底层即可。

委托书已经明确提醒：当前已经实现不是继续占据首页的理由。

---

# T. 两轮最小用户研究

## 第一轮：核心体验到底是不是产品

### 人

建议 **8–10 名严格筛选的目标用户**：

- Android OLED；
- 夜间会在床上/宿舍/共享空间使用手机；
- 实际消费长语言内容；
- 至少一部分人已经拥有本地视频 + 字幕。

这里不是为了统计市场比例，而是为了尽快找到强反证。

### 对照

每人比较：

1. 荧荧 Normal → Glow；
2. 自己平常的播放器最低可接受亮度；
3. 锁屏只听；
4. 如果设备支持，可额外记录 Live Caption。

不要让四种状态都变成超长实验。

最好随机安排，对同类型内容完成 10–20 分钟真实任务。

### 看什么

核心不是满意度 1–5。

而是：

- 是否主动选 Glow；
- 理解是否受影响；
- 是否频繁需要看画面；
- 是否觉得文字本身成为内容；
- 是否愿意拿自己的第二份内容进来；
- 是否会为了它准备字幕；
- 室友/伴侣环境干扰是否明显不同；
- 控制消失后是否焦虑；
- 次日首先寻找什么。

委托书原本的指标已经非常接近正确，包括“进入目标状态动作数、持续阅读、理解、环境干扰、是否愿意导入自己内容和第二天想继续什么”。

### 建议 Go 门槛

这是**项目内部决策门槛，不是市场比例估计**：

- 明显多数目标参与者在体验对照后主动选择 Glow；
- 大多数能够解释它与锁屏/普通播放器的区别；
- 至少一半主动愿意尝试自己的第二份内容；
- “继续上次”成为明显回访行为；
- 没有系统性舒适度/控制问题。

如果只能得到：

> “好酷。”

而没有：

> “我今晚想用这个。”

不算 Go。

---

# U. 第二轮：内容入口和字幕关系

建议 **10–12 人**，这轮比人数更重要的是：

> **带真实文件结构来。**

分别完成委托书定义的三个任务：

1. 一个媒体 + 手选字幕；
2. 一个真实 Folder；
3. 完整设备扫描。

记录：

- 权限是否理解；
- 到可播放状态多久；
- 无关候选数量；
- 真实字幕命名；
- 多语言情况；
- 内嵌 vs 外挂；
- 自动关联错配；
- 用户是否愿意授予 Folder；
- 第二次能不能自己找到。

### 自动规则采用一个非常严格的门槛

在这类小样本验证中：

> **任何发生过错误自动激活的规则，都应该降级为“建议”。**

因为这里不是搜索推荐：

> 错字幕会让整个内容直接不可用。

而“多发现一个候选”几乎没有同等严重性。

---

# V. 七天研究比任何一次可用性测试都重要

委托书这部分我完全赞同。

一次体验无法区分：

> “惊艳”

和：

> “习惯”。

至少观察：

- 第一天是否建立自己的 Unit；
- 第二天是否回来；
- 是否第二次仍选 Glow；
- 一周主动启动几次；
- 是否新增第二/第三份内容；
- 单文件 / Folder / scan 到底哪个真实发生；
- 因为什么退出：没字幕、错字幕、权限、控制、视觉、还是干脆锁屏；
- 室友/伴侣是否感知差异。

这里真正的北极星不是 session length。

我会看：

> **Own-content repeat Glow sessions**

也就是：

> 用户在没有研究员推动的情况下，用自己的内容第二次、第三次主动进入 Glow。

---

# W. Go / Rework / No-Go

我基本保留委托书框架。

### Go

核心迹象：

> **“我知道它有什么用，而且第二晚我真的又打开了。”**

以及：

- 不需要建大库；
- 单文件/Folder 至少一个路径稳定；
- 字幕来源足够；
- Continue 真正有效。

### Rework

最值得注意的一种结果反而是：

> **大家喜欢 Glow，但没字幕。**

如果这成为主失败原因：

**不要回去继续建 Library。**

那是 ASR / embedded subtitle / TransVortex integration 的证据。

### No-Go

我会非常严格保留这几个：

- 充分体验后仍普遍锁屏；
- 字幕让内容更累；
- 用户没有字幕且不愿寻找/生成；
- 一次视觉新奇之后不再打开；
- 为了留住用户最终必须变成一个完整 VLC/Poweramp clone。

这些正是委托书定义的 No-Go。

---

# X. 健康和“低发光”叙事

现阶段文案边界是对的：

**不要说“护眼”。**

OLED 真黑在物理意义上确实可以做到不发光；但长时间 OLED 阅读仍涉及干眼、近距离视觉任务等问题，而低亮度下某些 OLED 的 PWM 也可能产生可感知闪烁。2025 年一项 OLED PWM 研究发现，具体可见性与 PWM 频率、亮度和占空比强相关，不能把“OLED 低亮度”简单等同“更健康”。([PubMed][15])

所以继续坚持委托书的：

- 低发光；
- 减少发光面积；
- 暗室可读；
- 低环境干扰；

而不是：

- 护眼；
- 零蓝光；
- 防近视；
- 对睡眠更好。

这一边界本来就写得很准确。

---

# Y. 证据台账

| 结论                         | 类型 | 证据                                        | 置信度 | 改变的决策       |
| ---------------------------- | ---- | ------------------------------------------- | ------ | ---------------- |
| 当前独特价值被资源库路径后置 | F/I  | 仓库路径、253→7 真机记录                    | 高     | Library 降级     |
| 单文件不要求全库权限         | E    | Android SAF                                 | 高     | 单文件一级入口   |
| Folder 是明确授权范围        | E    | SAF tree                                    | 高     | Folder 二级入口  |
| 全库不是 Android 强制        | E    | SAF + Just Player                           | 高     | Scan 降级        |
| 内嵌字幕应优先               | E/I  | Media3 text tracks                          | 高     | Embedded 提升    |
| 字幕应该是多轨集合           | E/I  | Media3/Kodi/Jellyfin                        | 高     | 废弃单附件心智   |
| exact 自动、fuzzy 谨慎       | E/I  | mpv                                         | 高     | Matching policy  |
| 点句跳转是成熟模式           | E    | Apple Music/Podcasts/YouTube                | 高     | Normal P0/P1     |
| 音乐歌词竞争已经非常成熟     | E/I  | Apple/Spotify/Musixmatch/Poweramp/Symfonium | 高     | 不 music-first   |
| Continue 对长内容重要        | E    | AntennaPod/Voice                            | 高     | Home 第一对象    |
| Live Caption 是强替代        | E    | Google                                      | 高     | ASR 不构成护城河 |
| Glow 能否形成复用习惯        | H    | 只有创作者一小时体验                        | **低** | 必须七日研究     |
| 用户是否普遍拥有字幕         | H    | 无可靠公开比例                              | **低** | 核心市场风险     |
| Folder 是否符合真实组织      | H    | 相邻产品支持，但目标样本未知                | 中低   | Round 2          |
| “只让字幕发光”是否优于锁屏   | H    | 尚无用户比较                                | **低** | 最大 Go/No-Go    |

---

# Z. 最终回答：下一轮只能做一个产品切片，做什么？

我会做：

> ## **“打开今晚这一份内容，然后明晚回到这一句。”**

完整切片是：

```text
首次样片
   ↓
Home
   ↓
打开一个媒体
   ↓
自动读取内嵌字幕
或
选择一个外挂字幕
   ↓
形成 Playable Reading Unit
   ↓
字幕中心 Normal Player
   ↓
Glow
   ↓
退出
   ↓

第二天打开 App
   ↓
继续上次那一句
   ↓
直接 Glow
```

同时只实现**最小的 Folder 支援**：

> 没找到字幕 → “选择包含字幕的文件夹” → exact / language-suffix discovery。

不要把 Folder 又发展成 Library。

这个切片很强，因为它一次能回答四个最危险的问题：

1. **核心 Glow 到底有没有独立价值？**
2. **用户是否真的有可用字幕？**
3. **建立媒体/字幕关系的摩擦是否可接受？**
4. **惊艳能否转化为第二次主动使用？**

反过来，再做 artist、album、playlist、scan、queue UI、搜索、封面，都回答不了这四个问题。

---

## 研究后的产品骨架

最终我会把委托书第 19 节的工作假设改写成这一版：

```text
             首次样片
                │
                ▼
       ┌── 证明“只让字幕发光” ──┐
       │                       │
       ▼                       ▼
继续上次那一句             打开新内容
                               │
                     ┌─────────┴─────────┐
                     ▼                   ▼
                  单媒体               Folder
                     │                   │
                     └─────────┬─────────┘
                               ▼
                       发现所有文字轨
                  embedded / sidecar / saved
                               │
                               ▼
                         建立可信关系
                               │
                               ▼
                       字幕中心 Normal
                               │
                               ▼
                         UI 逐渐退场
                               │
                               ▼
                       “只让字幕发光”
```

底下才是：

```text
──────────────────────────────────
支撑层

MediaSession
恢复
Folder index
字幕关系
MediaStore
Queue
Sleep Timer
Library
──────────────────────────────────
```

这就是我认为**当前代码最应该发生的变化**：

> **不是回滚最近几天做的工程。**
>
> **是把它们从舞台中央搬回后台。**

这一点与委托书里“重新编排已有资产，而不是继续扩充能力清单”的判断高度一致。

而“字幕优先、媒体提供声音与时间、最后只剩文字发光”这条最早的产品直觉，经这一轮外部核对之后，**没有被推翻；反而是它周围越来越完整的播放器外壳正在成为最大的产品风险。**

---

## 主要一手来源

**Android / Media3**

[Android Storage Access Framework — 单文件与目录授权边界](https://developer.android.com/training/data-storage/shared/documents-files?utm_source=chatgpt.com)
[MediaStore.Files.FileColumns — MEDIA_TYPE_SUBTITLE](https://developer.android.com/reference/android/provider/MediaStore.Files.FileColumns?utm_source=chatgpt.com)
[Media3 Supported Formats — 容器与外挂字幕格式](https://developer.android.com/media/media3/exoplayer/supported-formats?utm_source=chatgpt.com)
[Media3 Media Items — 外挂字幕配置](https://developer.android.com/media/media3/exoplayer/media-items?utm_source=chatgpt.com)
[Android Live Caption — 系统级零成本替代方案](https://support.google.com/accessibility/android/answer/9350862?utm_source=chatgpt.com)

**字幕 / 播放器**

[mpv Manual — conservative subtitle auto-loading](https://mpv.io/manual/stable/?utm_source=chatgpt.com)
[Just (Video) Player — Android 外挂字幕与目录授权模式](https://github.com/moneytoo/Player?utm_source=chatgpt.com)
[VLC Android Video Player — subtitle tracks / delay / external subtitle](https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html?utm_source=chatgpt.com)

**同步文字**

[Apple Music iPhone — 时间同步歌词、点行跳转、翻译](https://support.apple.com/guide/iphone/iphb9bf483aa/ios?utm_source=chatgpt.com)
[Spotify 2026 Lyrics Update — 翻译、离线、歌词前置](https://newsroom.spotify.com/2026-02-04/lyric-translations-offline-previews/?utm_source=chatgpt.com)
[Symfonium — 本地音乐、同步/嵌入歌词](https://www.symfonium.app/?utm_source=chatgpt.com)
[YouTube Transcript — transcript 跟随与点行 seek](https://support.google.com/youtube/answer/15930243?utm_source=chatgpt.com)

**长内容**

[AntennaPod Home — Continue Listening](https://antennapod.org/documentation/getting-started/home?utm_source=chatgpt.com)
[Voice Audiobook Player — Folder、准确恢复、睡眠定时](https://play.google.com/store/apps/details?id=de.ph1b.audiobook&utm_source=chatgpt.com)

如果把整个研究压缩成一句产品决策，就是：

> **先停止把荧荧做成“一个越来越完整的本地播放器”，用下一轮开发证明一件更小也更难的事：一个人能不能在夜里把一份需要文字才能听懂的内容交给荧荧，然后真正愿意只看那几行光，并在第二天回来。**

这件事如果成立，后面的资源库、ASR、TransVortex 接力、Folder、双语才都有意义；如果它不成立，现在继续完善播放器只会让失败变得更昂贵。

[1]: https://support.apple.com/en-mide/guide/iphone/iphb9bf483aa/ios?utm_source=chatgpt.com "Show song credits and lyrics on iPhone - Apple Support"
[2]: https://developer.android.com/training/data-storage/shared/documents-files?utm_source=chatgpt.com "Access documents and other files from shared storage  |  App data and files  |  Android Developers"
[3]: https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile?utm_source=chatgpt.com "DocumentFile  |  API reference  |  Android Developers"
[4]: https://github.com/moneytoo/Player "GitHub - moneytoo/Player: ▶ Simple and lightweight, yet polished and powerful Android video player based on ExoPlayer · GitHub"
[5]: https://developer.android.com/reference/kotlin/android/provider/MediaStore.Files.FileColumns?utm_source=chatgpt.com "MediaStore.Files.FileColumns  |  API reference  |  Android Developers"
[6]: https://developer.android.com/reference/androidx/media3/common/MediaItem.SubtitleConfiguration?utm_source=chatgpt.com "MediaItem.SubtitleConfiguration  |  API reference  |  Android Developers"
[7]: https://developer.android.com/media/media3/exoplayer/supported-formats?utm_source=chatgpt.com "Supported formats  |  Android media  |  Android Developers"
[8]: https://mpv.io/manual/stable/ "mpv.io"
[9]: https://support.apple.com/en-gb/118668?utm_source=chatgpt.com "Follow and play shows in Apple Podcasts on iPhone and iPad – Apple Support (UK)"
[10]: https://antennapod.org/documentation/getting-started/home?utm_source=chatgpt.com "Using the Home screen – AntennaPod"
[11]: https://antennapod.org/documentation/playback/auto-rewind?utm_source=chatgpt.com "Automatic rewind – AntennaPod"
[12]: https://developer.android.com/training/sharing/send?utm_source=chatgpt.com "Send simple data to other apps  |  App data and files  |  Android Developers"
[13]: https://support.google.com/accessibility/android/answer/9350862?hl=en&utm_source=chatgpt.com "Live Caption: Caption media & calls on your device - Android Accessibility Help"
[14]: https://docs.videolan.me/vlc-user/android/3.X/en/video/video_player.html "Video Player — VLC for Android Documentation 3.X documentation"
[15]: https://pubmed.ncbi.nlm.nih.gov/41411543/?utm_source=chatgpt.com "Characterization of the stroboscopic effect for OLED displays using the PWM dimming method - PubMed"
