# M3.2a 内容目录与收录关系真机验收

> 日期：2026-08-10
>
> 设备：Xiaomi M2012K11AC，Android 13 / API 33，1080 × 2400
>
> 代码：`c1874b4 feat(library): separate catalog and recent media`、`db05a2f feat(library): expose collection action in player`
>
> 状态：M3.2a 自动化与首轮原位升级真机验收通过

## 结论

首轮验收确认：Library DataStore 可以从 v3 非破坏性升级到 v4；旧收录决定不会丢失；冷启动只恢复缓存和会话，不自动扫描设备；新打开内容只进入 Recent，不自动进入 Collection；用户可以在 Normal Player 明确加入或移出“我的内容”，移出后 Recent 和原文件都保留。

本轮是内容目录与迁移的工程验收，不是 Golden Session，也不替代连续七晚真实内容使用。

## 自动化与构建

在 Windows 环境使用仓库内 Gradle Wrapper 执行：

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果全部通过。定向单元测试覆盖：

- 打开内容只改变 Recent，不改变 Collection 或 Ignored；
- 重复打开会移动到 Recent 首位并遵守数量上限；
- 收录保留 Recent，并清除同 URI 的旧 Ignored 决定；
- 移出收录保留 Recent 和来源决定；
- 旧 `manualItems` 同时迁移为 Recent 与已收录目录快照；
- 旧 Playlist 内容继续被视为已收录；
- 没有旧字段时，当前目录决定保持不变。

## 升级前基线

设备保留 M2 验收版本与 DataStore，没有清除应用数据：

- Library Store version：3；
- `includedUris`：95；
- `ignoredUris`：0；
- `manualItems`、Folder 和 Playlist：0；
- v3 尚未保存 `catalogItems` 或 `recentItems`；
- 设备保留一条可继续播放的旧会话。

这组数据覆盖了 M3.2a 中“只有旧 `includedUris`、暂时没有媒体快照”的兼容分支。由于基线没有旧 `manualItems` 和 Playlist，这两条原位升级路径本轮无法在真机上重复，只由单元测试覆盖。

## 已验证

### v3→v4 原位升级

1. 使用 `adb install -r` 覆盖安装 Debug APK，保留应用数据；
2. 冷启动后 Library Store 自动写回 version 4，旧 `manualItems` 字段退出存储；
3. 95 条 `includedUris` 全部保留，未产生已忽略内容；
4. 因旧状态没有媒体快照，首次恢复时 `catalogItems` 仍为 0；应用没有为补全目录自动扫描；
5. Home 正常显示继续、打开内容、选择文件夹和更多来源，进程保持存活；
6. 用户明确执行“重新扫描本机”后，目录补全为 253 项，95 条旧决定重新解析为已收录内容；
7. AndroidRuntime 没有应用崩溃记录。

### Recent 与 Collection 分离

使用一份临时创建的 1 秒静音 WAV，通过系统文件选择器打开：

1. 首次开始播放后，`recentItems` 从 0 变为 1；
2. `catalogItems` 保持 253、`includedUris` 保持 95，播放器显示“加入我的内容”；
3. 点击“加入我的内容”后，`catalogItems` 变为 254、`includedUris` 变为 96，按钮切换为“移出我的内容”；
4. 点击“移出我的内容”后，`includedUris` 回到 95，`recentItems` 仍为 1，目录快照继续保留，按钮恢复为“加入我的内容”；
5. 收录和移出过程中，临时 WAV 原文件始终存在；
6. 强制停止并冷启动后，Recent、Continue 和未收录状态均可恢复，冷启动没有触发扫描。

### 测试清理与设备最终状态

- 删除本轮创建的临时 WAV，并确认 MediaStore 不再保留对应条目；
- 通过应用内“清空单独打开记录”移除测试目录快照和 Recent；
- 清理后目录恢复为 253 项、已收录恢复为 95 项；
- 从已有已收录内容重新建立有效会话；最终冷启动可以继续，不显示“需要重新定位”；
- 最终 Recent 为 1，指向设备上仍可用的已收录内容；
- 删除了本轮生成的设备端 UI dump 文件。

## 尚未覆盖

- 含旧 `manualItems` 或 Playlist 的真实 v3 设备数据原位升级；
- 带持久 SAF 授权的 Folder Source 在 v4 中恢复快照、刷新和撤销授权；
- 媒体外部移动或删除后的 availability 与重新定位；
- 多设备、Android 14+ 部分视频授权和大型 Folder 压力；
- M3 Golden Session 与连续七晚真实内容行为验证。

这些未覆盖项不阻塞 M3.2a 目录事实收口，但对应能力进入后续实现时仍需真机补测。
