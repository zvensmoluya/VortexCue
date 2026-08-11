# M3.2d / M3.3a 内容进入与 Home 真机验收

> 日期：2026-08-11
>
> 设备：Xiaomi M2012K11AC，Android 13 / API 33，1080 × 2400
>
> 代码基线：`a64e617 feat(home): establish home and my content surfaces`；本轮未提交的修复包含扫描范围、冷启动 locator 恢复与文件夹名称清洗
>
> 状态：真机验收通过（M3.2d / M3.3a）

## 验收范围

本轮验证 M3.2d 的内容目录收口与 M3.3a 的 Home / 我的内容表面，重点覆盖：

- Library version 5 与当前 version 7 不兼容时，只重置应用自有状态，不修改用户原始媒体；
- 单文件确认媒体后立即建立内容，primary / secondary 外挂字幕可以独立选择、移除和同时读取；
- 同一字幕不能同时占用两个显示槽，更换媒体不会继承上一内容的字幕关系；
- 选择字幕后立即返回、强制停止和冷启动，内容关系与当前选择仍从内容目录恢复；
- 同一媒体从单文件与 SAF Folder 进入时复用内容 ID、文字轨选择和 offset；
- 移除 Folder 授权只改变 locator 可用性，仍被 Recent、Collection 或当前会话引用的内容事实继续保留；
- Home 的空状态、继续、我的内容窗口、完整已收录 / 最近列表、搜索、收录与移出关系符合 M3.3a；
- 系统文件与文件夹选择器往返后，页面和草稿状态保持可解释且应用无崩溃。

## 验收夹具

本轮使用单独生成的短音频与两条外挂 SRT，放入设备 `Download/VortexCue-M32D-20260811`。夹具不包含个人内容；所有删除操作只针对本轮生成的夹具和应用自有状态。

| 文件 | SHA-256 |
| --- | --- |
| `VC-M32D-A.m4a` | `541A65F9E94A97D85716C77BAB0A446DC27E9FA51F67B87882F523AD5FF8EC9E` |
| `VC-M32D-B.m4a` | `D70B811F997604F7374129F871AFE44A05AE434247545EBB6249B89719606715` |
| `VC-M32D-primary.srt` | `02E54818B94CADE155B4344737C56312329981E3F7A6CC8F06829BA92B101825` |
| `VC-M32D-secondary.srt` | `30B36AE0DFE3AA693AB17CAD7D84D4392B7621CF3AE879693231975AE9DF1982` |

## 自动化与构建

在 Windows 环境使用仓库内 Gradle Wrapper 执行：

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果通过：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 均成功；另有扫描范围和文档树名称单元测试通过。

## 真机步骤与结果

1. 以旧 Library version 5 / Session version 2 状态启动，应用只重置自有状态；原始夹具文件未改变。
2. 空 Home 只显示“打开一个内容”和“添加常用文件夹”。单独打开 A 后立即建立内容，Home 显示 1 项最近打开。
3. 在导入界面分别选择 primary / secondary SRT；两条均读取 3 条 cue，选择模式均为手动、ID 不同。再次把同一 SRT 放入另一槽时，显示互斥提示且目录选择不变。
4. 更换媒体为 B 后字幕槽清空；A 的两条轨仍留在 A 内容单元，B 没有字幕关系。
5. 选择后立即关闭导入界面、强制停止并冷启动；A、两条字幕、`+0.5 秒` offset 均恢复。播放页字幕轨面板显示两条外挂 SRT 均“可用”，主/次选择保持。
6. 从 Home / 我的内容进入播放，确认同一内容关系和主次字幕可用；调整 offset 后保存值为 `500 ms`。
7. 通过“我的内容”查看全部、搜索 `M32D`、收录再移出；移出后仍保留最近记录，A 原始文件 SHA-256 不变。
8. 通过系统文件夹选择器重新授权 `VortexCue-M32D-20260811`。Folder 只发现 A、B 两项，Home 显示 `2 项尚未决定的内容`；设备媒体库保持“等待手动刷新”，没有把系统媒体库的 260 项混入结果。来源详情与移除确认均显示友好的 `VortexCue-M32D-20260811`，不再暴露 `primary:...` 原始 document ID。
9. 移除 Folder 授权后，A 保留 loose 与 folder 两个 locator（loose 可用、folder 不可用），B 因无 Recent / Collection / Active 引用被清理；A 的内容 ID、双字幕、选择和 offset 保留。
10. 再次强制停止并冷启动，Home 仍可继续 A，没有“需要重新定位”；进入播放后两条字幕仍为外挂、可用，主/次选择和 offset 不变。

## 本轮修复

- Folder 增量扫描改为显式 `LibraryScanScope.folder(...)`，不会触发全设备 MediaStore 扫描；完整刷新仍使用 `LibraryScanScope.All`。
- 启动恢复先通过内容目录解析 stored media / queue 到当前 active locator，再执行可读性、文字轨和 offset 恢复，并回写修复后的 Last Session。
- SAF tree display name 在 provider 返回 document ID 时回退到最后一级目录名，来源与确认文案可读。

## 结论与边界

M3.2d 和 M3.3a 的代码、自动化检查与 Android 13 真机验收已完成。M3.3 后续切片仍未完成：新发现条目的直接播放、逐项 / 批量忽略，以及单个 Source 的刷新与状态细节继续按 `docs/GUIDE.md` 留在后续范围。夹具保留在设备的独立验收目录，便于复查；未删除任何用户原始媒体。
