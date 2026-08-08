# Android 应用图标适配

> 状态：已接入应用资源，待真机 Launcher 最终确认
>
> 记录日期：2026-08-08
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

## 真机待确认

连接的 Android 13 小米设备拒绝通过 ADB 安装 debug APK，返回 `INSTALL_FAILED_USER_RESTRICTED`，设备上没有留下安装结果。允许 USB 安装后仍需确认：

1. MIUI Launcher 的实际遮罩与桌面缩放；
2. 开启 themed icons 后的单色层层级；
3. Android 12+ 冷启动 Splash 的尺寸与黑底衔接；
4. Launcher 图标缓存更新后，普通与圆形入口是否一致。
