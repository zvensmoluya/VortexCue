# 启动闪退修复

手机启动时，生成字幕展示流读取旧 Partial revision 抛出 `TextStorageIntegrityException: Timeline revision cue count does not match`，异常未被处理，导致主线程退出。

Partial 发布使用 seam 拼接、源文字正规化后的 cue 数量和 hash，却只引用原始 WorkUnit Artifact，读取时的通用拼接无法复现相同结果。现改为保存实际展示前缀的 Timeline 快照；工作分段仍作为任务 checkpoint 保留。旧结果读取失败时将相应轨道标记为不可用并返回空展示，保留原记录，不自动重新请求生成。

验证通过：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests '*SyncTextRepositoryTest' --tests '*TimelineStorageTest' --tests '*SyncTextTaskRunnerTest'
git diff --check
```

覆盖发布前后文本不同的 Partial 快照、全新 reader 重读、损坏资产展示降级，以及已有存储和任务回归。连接手机 V2458A 已先备份应用数据，再以 `adb install -r` 覆盖安装；多次冷启动成功，进程持续存活，未出现新增崩溃日志。未执行新的付费识别/翻译或完整媒体播放回归。旧异常字幕可能需要用户重新生成，未清空应用数据。
