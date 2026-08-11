# M3.2c 文字轨播放与恢复真机验收

> 历史记录：本文中的旧目录和旧字幕关联保留结果只描述当时的验收夹具，不构成当前兼容承诺。当前 Library version 7 与 Session version 2 采用首个正式 release 前的破坏性 schema 重置策略。

> 日期：2026-08-10 至 2026-08-11
>
> 设备：Xiaomi M2012K11AC，Android 13 / API 33，1080 × 2400
>
> 代码：`1f0b429 feat(player): complete text track playback and recovery`、`e367d9f fix(player): preserve automatic text track playback`
>
> 状态：M3.2c 自动化与真机验收通过

## 结论

本轮确认：Media3 内嵌文本轨可以进入稳定文字轨目录并自动选择明确 default；Normal Player 和 Glow 可以显示一条内嵌轨与一条外挂轨组成的 primary / secondary；手动切换、关闭和恢复自动选择会持久化；冷启动可以恢复两轨、位置和表面；媒体 URI 失效时会显示修复入口，重新定位到等价文件后内容关系和文字轨选择不会丢失。

这仍是 M3.2 的实现切片，不是 Golden Session，也不替代真实长内容的夜间连续使用。当前播放器同一时间只启用一条内嵌文本轨，因为 Media3 的合并 cue 回调不提供可可靠区分来源轨的身份；一条内嵌轨仍可与一条应用时间轴驱动的外挂轨组合。

## 验收夹具

为避免用个人媒体做删除和重定位测试，本轮在应用构建目录生成并推送一组 30 秒合成夹具：

- AAC 音频加两条内嵌 SubRip 文本轨；
- `English default` 带 `en` 语言和 default 标记；
- `Alternate` 带 `zh` 语言且非 default；
- 独立外挂 SRT 作为 secondary；
- A、B 两个媒体文件内容相同，用于模拟原 URI 失效后重新定位。

验收结束时已删除设备上的 A 文件；B 文件和外挂 SRT 保留在 `Download`，便于复查最终可恢复状态。夹具不包含个人内容，文件总量不足 1 MB。

## 自动化与构建

在 Windows 环境使用仓库内 Gradle Wrapper 执行：

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果全部通过。新增回归测试覆盖：同媒体、同一已选内嵌轨发生运行时刷新时保留当前 cue；媒体变化、轨道变化或没有已选轨时清空旧 cue。

## 真机发现与修复

### 首次打开误写为手动关闭

首次打开一个没有手动选择外挂字幕的媒体时，应用仍调用旧的单字幕关联写入方法，并把空字幕解释为“手动关闭 primary”。结果是 Media3 已发现两条内嵌轨，但明确 default 也不会自动激活。

修复后，只有用户实际带入外挂字幕时才写入手动关联；没有外挂字幕时恢复内容单元已有选择，让自动规则选择唯一的 `English default`。轨道对话框中的“恢复自动”也在真机上验证可用。

### seek 后当前 cue 被状态刷新清空

媒体播放结束后 seek 回一个正在生效的 cue，Media3 可能先发送 cue，再发送 `STATE_READY` 或轨道刷新。旧实现的后一个回调会重建运行时状态并清空刚收到的 cue，导致内嵌字幕必须等到下一个时间边界才重新出现；外挂字幕不受影响。

修复后，只有媒体 URI 和已选内嵌轨都未变化时才跨运行时刷新保留 cue；换媒体、换轨或关闭内嵌轨仍清空旧文字。原失败步骤在最终 APK 上从 30 秒 seek 回 2 秒并保持暂停，主副两行立即同时出现。

## 已验证

### 内嵌发现与自动选择

1. Media3 在真机上发现两条内嵌 `MEDIA3-CUES` 文字轨；
2. `English default` 保存语言、名称和 default 角色，`Alternate` 保存语言和名称；
3. 没有手动外挂 primary 时，唯一 default 自动成为 primary；
4. Normal Player 在 0–8 秒显示第一条内嵌 cue，播放到 8 秒后切换为第二条 cue；
5. 手动关闭后可以通过“恢复自动”重新得到 default 轨。

### primary / secondary 与 Glow

1. 将外挂 SRT 设为 secondary 后，Normal Player 同时显示内嵌 primary 和外挂 secondary；
2. 暂停、seek 和继续播放时两条时间轴保持同步；字幕 offset 控件只作用于外挂轨；
3. Glow 在 OLED 纯黑背景上同时显示主副两行，并可长按打开临时控制和文字轨管理；
4. 将 `Alternate` 设为 secondary 时，原内嵌 primary 自动关闭，真机没有出现两条内嵌轨同时激活；
5. 恢复最终配置后，primary 为自动 `English default`，secondary 为手动外挂 SRT。

### 冷启动与 URI 恢复

1. 强制停止后冷启动，Home 不自动播放；点击继续后恢复 2 秒位置、Normal 表面和两条文字轨；
2. 主副两行在冷启动后的当前 cue 立即显示；
3. 删除合成 A 文件后再次冷启动，Home 明确显示“需要重新定位”和修复动作；
4. 选择等价 B 文件后直接返回播放器，标题和 URI 更新，位置、外挂轨和主次选择保留；
5. B 的两条内嵌轨重新发现，自动 primary 仍解析为 default，外挂 secondary 仍为 Ready；
6. 应用进程保持存活，`AndroidRuntime` 没有崩溃记录。

### 数据保全

检查脚本只输出计数、枚举和唯一性结果，不输出个人媒体名、路径或 URI。最终结构为：

- version：5；
- `catalogItems`：253，原基线不变；
- `recentItems`：2，新增 1 条合成验收内容；
- `includedUris`：95，原基线不变；
- `contentUnits`：254，内容 ID 和媒体 URI 均唯一；
- 文字轨：4，包括原有 1 条 Lyrics，以及夹具的 2 条 Embedded 和 1 条 Sidecar；
- primary 选择：2；secondary 选择：1；
- Folder 和 Playlist：0；旧 `subtitleAssociations`：2，均保持不变；
- 重定位后的夹具只对应 1 个内容单元，没有同时遗留 A、B 两份关系；
- 夹具最终选择为 `Automatic / Embedded` primary 和 `Manual / Sidecar` secondary。

## 边界与后续

- 图形字幕仍不会伪装成 Glow 文本轨；本轮只验证 SubRip 内嵌文本；
- 同一时间只消费一条内嵌文本轨，双轨显示需由“一条内嵌 + 一条外挂”或“两条外挂”组成；
- 本轮没有覆盖 Android 14+、部分媒体授权、超大容器、多音频组或异常编码内嵌字幕；
- 本轮没有重新执行后台通知、锁屏、耳机、蓝牙和 Sleep Timer 的系统生命周期验收；
- 合成 30 秒夹具证明状态机和恢复关系，不替代真实两小时内容、字幕间隙、OLED 距离与亮度的夜间体验；
- MIUI 的 `uiautomator` 因缺失系统主题配置会输出与应用无关的异常，验收改用设备截图、应用 PID 日志和结构化私有状态计数完成。
