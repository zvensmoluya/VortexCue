# MIUI 真机验收记录

> 这是一份设备观察记录，不是 Android 或 MIUI 全版本行为规范。
>
> 日期：2026-08-11
>
> 设备：Redmi K40（M2012K11AC），Android 13 / API 33，MIUI `V816`，1080 × 2400
>
> Launcher：`com.miui.home`，`RELEASE-4.39.12.6889-03141130`

## 观察到的阻塞

### ADB 安装授权

设备初始拒绝通过 ADB 安装 Debug APK，返回 `INSTALL_FAILED_USER_RESTRICTED`。在设备上允许 USB 安装后，覆盖安装和后续测试才可继续。

结论：这是设备前置条件，记录为 `BLOCKED`，不归因于应用安装逻辑。后续步骤固定使用目标 serial，并在测试开始前确认设备仍在线。

### UIAutomator 系统异常

MIUI 的 `uiautomator dump` 可能输出 `/data/system/theme_config/theme_compatibility.xml` 不存在的异常。异常来自系统资源初始化，应用进程仍可正常运行。

处理：用截图、`dumpsys activity`、应用 PID 日志和脱敏 DataStore 摘要替代单一 hierarchy 结果；不把这条异常记为应用崩溃。

### Launcher themed icon 能力

MIUI Launcher 的“图标样式”页面只提供“经典/无界”，没有 Android themed-icons 开关。本机可以确认 adaptive icon 的实际遮罩、桌面缩放、Splash 和缓存刷新，但不能触发系统单色着色预览。

结论：单色视觉着色在本机标记为 `NOT APPLICABLE`，资源表、adaptive icon 入口和 Manifest 仍做静态核对；需要支持 themed-icons 的 AOSP / Pixel Launcher 设备补做视觉验收。[图标记录](../brand/platform/android/README.md)

## 边界行为

- 本轮 provider 返回的 SAF Tree 名称包含 `primary:Download/...` 原始 document ID。应用在 `DocumentAccess` 边界通过 `DocumentsContract` 查询并回退到最后一级目录名；这条实现处理的是 provider 返回格式，不把它当作所有设备的固定格式。
- 添加 Folder 后出现的系统媒体污染是应用扫描范围问题，不是 MIUI 的产品行为。MIUI 的媒体授权状态只是让问题在真机上暴露；修复和业务结果记录在 [M3_IMPORT_HOME_VALIDATION.md](M3_IMPORT_HOME_VALIDATION.md)。
- 设备连接切换会使默认 `adb` 目标改变；一旦设备列表变化，必须停止当前验收并重新确认型号、包名和 Launcher。

## 复查入口

- 业务流程、夹具哈希和真机步骤：[M3_IMPORT_HOME_VALIDATION.md](M3_IMPORT_HOME_VALIDATION.md)
- 通用真机协议：[ANDROID_DEVICE_VALIDATION.md](ANDROID_DEVICE_VALIDATION.md)
- Android 图标、Splash 与 Launcher 验收：[android/README.md](../brand/platform/android/README.md)
