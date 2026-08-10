# M3.2b 稳定内容单元与文字轨目录真机验收

> 日期：2026-08-10
>
> 设备：Xiaomi M2012K11AC，Android 13 / API 33，1080 × 2400
>
> 代码：`cda9e1e feat(library): add stable content and text tracks`
>
> 状态：M3.2b 自动化与 v4→v5 原位升级真机验收通过

## 结论

本轮确认：v4 的 URI 兼容目录可以非破坏性升级为 version 5 的稳定内容单元；每个已知媒体快照都得到唯一内容 ID；可定位的旧字幕关联成为稳定 Legacy 文字轨和手动 primary；没有媒体快照的旧关联继续保留；冷启动不扫描，重复启动也不会重复迁移或改写状态。

这仍是 M3.2 的中间实现切片。播放器暂时只消费一个外挂 primary，内嵌文字轨、secondary 显示、轨道选择 UI、解析失败提示和重新定位尚未完成。本轮也不是 Golden Session，不替代真实夜间内容验证。

## 实现边界

- `PlayableContentUnit` 保存稳定 ID、媒体快照、来源、可用性、`0..N` 文字轨和激活选择；
- 文字轨可以表达内嵌、外挂、歌词和未来生成来源，以及格式、语言、名称、角色、匹配依据、可读状态和缓存定位；
- Folder 匹配保留完全同 stem、语言与角色后缀的全部候选；只有唯一完全同 stem 候选可以自动激活；
- 手动 primary、手动移除和拒绝决定优先于后续自动候选；
- v4 `subtitleAssociations` 继续作为当前播放器和缺少媒体快照数据的兼容路径，不在迁移时破坏性删除；
- Recent、Collection 和现有播放器仍通过 URI 兼容字段工作，本切片没有重做 Home、Tonight 或 Active Session 存储。

## 自动化与构建

在 Windows 环境使用仓库内 Gradle Wrapper 执行：

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果全部通过。定向单元测试覆盖：

- 内容 ID 与文字轨 ID 确定、分命名空间且不同 URI 不冲突；
- 语言、forced、SDH 和 CC 后缀保留为轨道元数据；
- 多语言候选全部保留，且没有明确偏好时不隐式选择；
- 唯一 exact 候选和唯一 embedded default 的自动 primary 规则；
- 手动 primary 在新候选出现后保持优先，并按当前选择排在首位；
- 手动移除 primary 后不会被后续自动候选恢复；
- 被拒绝候选暂时消失后，拒绝决定仍保留且不会重新自动激活；
- 旧字幕来源迁移后保留稳定轨道 ID、缓存定位和手动选择。

## 升级前基线

设备保留 M3.2a 验收后的 version 4 DataStore，没有清除应用数据：

- `catalogItems`：253，且 URI 无重复；
- `recentItems`：1；
- `includedUris`：95；
- Folder 和 Playlist：0；
- `subtitleAssociations`：2，均带应用内字幕缓存；
- 其中 1 条字幕关联有已知媒体快照，另 1 条暂时没有目录或 Recent 快照；
- version 4 尚无 `contentUnits` 字段；
- 设备保留一条可继续的有效会话。

这组数据同时覆盖“旧关联可以直接迁移”和“旧关联缺少媒体快照时不得丢失”两条路径。

## 已验证

### v4→v5 原位升级

1. 使用 `adb install -r` 覆盖安装 Debug APK，保留应用数据；
2. 冷启动后 Library Store 自动写回 version 5；
3. `catalogItems` 仍为 253、`recentItems` 仍为 1、`includedUris` 仍为 95、旧字幕关联仍为 2；
4. 生成 253 个内容单元，内容 ID 全部符合 `content-` 加 32 位小写十六进制格式，ID 和媒体 URI 均无重复；
5. Recent 项和 95 条收录决定都能解析到对应内容单元；
6. 有媒体快照的旧字幕关联迁移为 1 条 Legacy 轨，缓存定位保留，并成为可解析的手动 primary；
7. 没有媒体快照的另 1 条旧关联没有被伪造为空内容单元，也没有从兼容字段丢失；
8. 253 个内容单元初始 availability 均为 `Unchecked`，Home 没有显示扫描或来源错误；
9. Continue 操作存在且可用，应用进程保持存活，AndroidRuntime 没有崩溃记录。

### 重复冷启动与幂等性

1. 强制停止应用后再次冷启动，启动状态为 Cold；
2. version、253 个目录项、1 个 Recent、95 条收录决定、253 个内容单元和 2 条旧关联保持不变；
3. 在本地对完整结构规范化后计算 SHA-256，仅记录一致性结果；第二次启动前后摘要一致，个人字段没有输出；
4. 第二次启动仍没有 AndroidRuntime 崩溃。

### 数据与设备清理

- 本轮只做原位升级、冷启动和只读状态检查，没有创建、删除或修改个人媒体；
- 检查脚本只输出计数、布尔值和结构摘要，不记录媒体名、路径或 URI；
- 设备端临时 UI hierarchy 文件已删除；
- 应用最终保留在 version 5，原有有效会话和个人目录继续可用。

## 尚未覆盖

- 真机 Folder 中同时存在 exact、多语言和角色后缀字幕时的发现与选择；本轮设备没有已授权 Folder，这部分由纯逻辑测试覆盖；
- 暂时没有媒体快照的旧字幕关联在对应内容重新出现后的延迟迁移；
- Media3 内嵌文本轨发现、解析和 default / 语言偏好选择；
- primary / secondary 同时显示、轨道查看切换和恢复自动选择的 UI；
- 字幕 URI 失效、读取或解析失败的可见状态、重新定位和修复；
- 将 Recent、Collection、Tonight 和 Active Session 全部改为稳定内容 ID 关系；
- Android 14+ 部分媒体授权、大型 Folder 和多设备升级。

这些未覆盖项意味着 M3.2 尚未整体完成，但不阻塞本切片作为后续文字轨与内容关系实现的兼容基础。
