# Android 应用图标适配

> 状态：资源接入与 Android 13 MIUI Launcher 真机确认完成；单色 themed icon 受设备 Launcher 能力限制
>
> 记录日期：2026-08-11
>
> 视觉母版：[`003-prototype-3-master-body/01-default-smile.svg`](../../explorations/003-prototype-3-master-body/01-default-smile.svg)

Android 平台资源直接使用 003 的原型 3 号身体与原型 2 号默认脸，不使用 004 的低节点重建路径。平台适配只改变画布内的缩放、光学位置、分层和单色表达，不改变角色轮廓。

## 画布与安全区

- Adaptive Icon 三层画布均为 `108 × 108 dp`；
- 角色完整剪影约为 `60 × 58 dp`，位于官方 `66 × 66 dp` 安全区内；
- 定位使用完整剪影的圆形包围中心，而不是外接矩形中心；
- 角色相对几何中心轻微右移、上移，确保圆形遮罩不切掉左下摆角；
- 外侧区域只由纯黑背景层填充，供系统遮罩、视差和缩放效果使用。

## 资源分层

| 资源 | 用途 |
| --- | --- |
| `drawable/ic_launcher_foreground.xml` | 003 彩色角色前景 |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ themed icon 单色层 |
| `mipmap-anydpi-v26/ic_launcher.xml` | API 26+ 自适应图标与单色入口 |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | API 26+ 圆形图标与单色入口 |
| `values/colors.xml` | 黑色背景与三种品牌色 |

Manifest 同时声明 `android:icon` 与 `android:roundIcon`。两个 adaptive icon 入口都包含 `monochrome` 层；API 26-32 会忽略该扩展层，Android 13+ 用它生成 themed icon。Android 12+ 系统 Splash 复用彩色前景矢量与黑色图标背景，不再使用原来的黑色方块占位图。

虽然项目的 `minSdk` 已经是 26，adaptive icon 仍必须保留在 `mipmap-anydpi-v26`：移入无版本目录后 AAPT 不会注册该资源。根目录 `lint.xml` 仅对这个目录忽略相冲突的 `ObsoleteSdkInt` 提示。

## 单色层

单色图标保留外轮廓、默认脸和两道内部褶皱。系统负责最终着色；褶皱使用较低 alpha，避免与主轮廓争夺层级。单色层不包含自绘背景、阴影、滤镜或预设主题色。

## 已验证

- 圆形、圆角方形与 squircle 遮罩均保留完整掀角和底部摆角；
- 彩色与单色预览在 `48 px` 和 `32 px` 下仍可辨认；
- `gradlew.bat assembleDebug lint` 通过；
- APK 资源表包含普通、圆形与单色图标资源；
- 打包后的 Manifest 正确引用普通与圆形 mipmap。

## 真机确认（2026-08-11）

设备：Redmi K40（M2012K11AC），Android 13 / API 33，MIUI `V816`，分辨率 `1080 × 2400`；Launcher 为 MIUI `RELEASE-4.39.12.6889-03141130`。

- APK 重新安装后，MIUI Launcher 可见应用入口 `荧荧`；当前圆角遮罩下黑色背景、黄色轮廓和默认脸完整显示，未见掀角或底部摆角被裁切。抓帧保存在本地验收目录 `build/validation/m3-import-home/launcher-final.png`。
- `android:icon` 与 `android:roundIcon` 均已解析到对应 adaptive icon；普通、圆形入口使用相同的背景、彩色前景和 monochrome 层。MIUI 当前桌面实际使用的遮罩显示正常。
- Android 12+ 冷启动连续抓取 `0/20/50/100/200/400 ms` 帧，均显示黑底居中品牌图标，随后进入 Home；未见白屏、占位方块或错误图标。抓帧保存在 `build/validation/m3-import-home/splash-*.png`。
- 强制停止、重新启动并返回桌面后，Launcher 仍显示同一图标与 `荧荧` 标签，未出现旧缓存或入口丢失。
- 该 MIUI Launcher 的“图标样式”页面仅提供“经典/无界”，没有 Android themed-icons 开关，因此无法在本机触发系统单色着色预览。`ic_launcher_monochrome.xml` 已通过资源表、adaptive icon 入口和 APK Manifest 核对；需要支持 themed icons 的 AOSP/Pixel Launcher 设备再补一次视觉层级确认。
