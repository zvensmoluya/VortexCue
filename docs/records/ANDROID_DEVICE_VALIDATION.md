# Android 真机验收手册

这份手册记录跨厂商可复用的设备验收方法。它补充 `docs/GUIDE.md` 的产品行为清单；具体厂商现象单独记录，不把某个设备的限制写成 Android 通用行为，也不把工具不可观测误判为应用通过或失败。

## 状态分类

- `PASS`：在目标设备上观察到预期行为，并保留了可复查证据；
- `BLOCKED`：安装、授权、设备连接或其他前置条件未满足，测试没有开始；
- `NOT OBSERVABLE`：行为可能发生，但当前工具无法可靠观察，必须换截图、日志或结构化状态；
- `NOT APPLICABLE`：目标设备明确不提供该能力。

计划中的步骤不能提前写成“已验证”。每条真机结论都应带设备、系统版本、日期和证据位置。

## 设备前置检查

先确认只有一个目标设备，之后所有 ADB 命令都显式带 serial：

```text
adb devices -l
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

记录型号、API、厂商系统版本、分辨率、默认 Launcher、应用包名和 APK 构建版本。设备从 `adb devices` 消失或默认设备发生变化时，停止当前步骤并重新确认，不要继续沿用结果。

安装前确认设备允许 USB 安装；厂商安装限制应记录为 `BLOCKED`，不能当作应用安装逻辑通过。除非验收明确要求，不使用 `pm clear`，避免把用户状态清掉后误判冷启动恢复。

## 证据采集

每个关键状态至少保留一种可复查证据，优先组合使用：

- 页面截图和关键时刻的 `uiautomator` hierarchy；
- `dumpsys activity` 的 resumed package、应用 PID 和崩溃日志；
- 应用私有 DataStore 的脱敏计数、布尔值、内容 ID 和可用性摘要；
- 生成夹具的 SHA-256，以及验收前后相同文件的哈希对比。

如果 `uiautomator` 或其他系统工具输出异常，先确认异常来源属于系统工具还是应用进程，再用截图、当前 package、PID 和结构化应用状态完成判断；不要把工具异常写成应用崩溃。

Android 12+ Splash 要在应用启动后尽早抓帧，必要时按 `0/20/50/100/200/400 ms` 保存序列。Splash 抓不到不等于失败，最终应同时确认黑底、图标资源、无白屏和进入目标页面。

## 厂商差异记录

厂商差异只记录观察结果和适用设备，不写入上面的通用规则。至少说明：

- 权限页、USB 安装和系统设置的实际入口；
- Launcher 的形状、缓存刷新和可选能力；
- 系统工具异常是否来自厂商组件；
- 哪些行为是应用问题，哪些是设备限制，哪些只是无法观测。

本仓库的 MIUI 观察记录见 [`MIUI_VALIDATION_NOTES.md`](MIUI_VALIDATION_NOTES.md)。

## SAF、来源与扫描

- 用 `DocumentsContract` 解析 Tree URI 的 document ID；原始 document ID 只作为身份或回退值，不能直接当用户可读的文件夹名称。具体 provider 返回值应在设备记录中注明。
- 分别从系统文件管理器和下载应用执行“用荧荧打开”，覆盖应用未运行、前台运行和后台运行；每次确认只建立单项 Queue、进入 Recent，并复用已有内容 ID 与字幕关系。
- 对外部 `content:` URI 记录 grant flags 和 Provider；不能取得持久授权时先确认当前播放成立，再强制停止或撤销授权，确认应用保留内容事实并进入重新定位，而不是静默创建重复内容。
- 添加 Folder、重新进入、撤销授权和 URI 失效必须分开验证。Folder 授权应持久化，但授权撤销后只能更新 locator 可用性，不能删除仍被 Recent、Collection 或 Active Session 引用的内容事实。
- 添加或刷新单个 Folder 时，断言系统 MediaStore 条目数不变化；只有明确执行全设备扫描时才允许扫描系统媒体库。
- 同一内容从单文件和 Folder 进入时，检查稳定内容 ID、文字轨 ID、主次选择和 offset 是否复用，而不是只比较显示名称。

## 生命周期回归

对涉及来源、字幕或会话的步骤，至少按以下顺序回归：

1. 执行用户操作并立即返回 Home；
2. 强制停止进程后冷启动；
3. 进入播放页检查当前 active locator、字幕可用性、主次选择和 offset；
4. 移除或撤销来源后再次冷启动；
5. 确认仍可用的 locator 被选中，不出现虚假的“需要重新定位”；
6. 确认不可用内容仍保留可解释状态，且原始文件没有被应用删除。

## 夹具与清理

- 夹具放在独立目录，使用短、可重复生成的媒体和字幕，不混入个人文件；
- 测试前后记录哈希，测试只删除明确生成的夹具；
- 验收记录保留设备路径、状态摘要和证据文件位置，但不提交本机绝对路径、凭据或用户 URI；
- 结束时记录设备授权、应用最终状态和仍保留的夹具，方便复查或由下一轮测试接手。

业务验收实例见 [`M3_IMPORT_HOME_VALIDATION.md`](M3_IMPORT_HOME_VALIDATION.md)；厂商观察见 [`MIUI_VALIDATION_NOTES.md`](MIUI_VALIDATION_NOTES.md)。
