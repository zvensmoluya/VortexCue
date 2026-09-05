# 微光显示回弹拨杆首轮

五条滑杆改为参数切换与共用回弹拨杆，左右偏移控制方向和速率，松手停止；加减按钮支持单步调整。顶部提供纯净预览，轻触画面恢复工具；预设按需展开，保留即时保存和完整屏幕预览坐标。

验证：

- `gradlew.bat assembleDebug` 通过。
- `gradlew.bat testDebugUnitTest --tests '*GlowDisplayEditorComposeTest'` 四项通过，覆盖预设、预览往返、首次校准入口、持续调节与松手停止。
- `git diff --check` 通过。
- Android 模拟器截图检查标题、预览入口、紧凑参数面板和字幕区域。
- vivo 手机覆盖安装成功；实际 OLED 亮度与拨杆手感待用户体验。未覆盖小屏、大字号与后台切换期间的手势取消。
