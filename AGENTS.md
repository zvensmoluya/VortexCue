# Repository working agreement

## General

- 默认使用中文沟通；代码、包名和标识符使用英文；提交标题使用英文，正文使用中文。
- 使用仓库内的 Gradle Wrapper，不依赖全局 Gradle。
- 不提交密钥、凭据、本机绝对路径、`local.properties`、构建产物或缓存。

## Product context

- 涉及产品定位、用户流程、信息架构、范围取舍或产品相关架构调整时，先阅读 `docs/DOMAIN_CONTEXT.md`，再以 `docs/GUIDE.md` 确认当前行为和实施边界。
- `docs/DOMAIN_CONTEXT.md` 记录长期领域背景和产品设计 DNA，不自动产生实现要求；与当前规格冲突时以 `docs/GUIDE.md` 为准。

## Verification

- 默认运行与改动直接相关、范围最小的验证；只有改动影响共享行为、构建或发布时才扩大范围。
- 按需使用 `gradlew.bat testDebugUnitTest`、`gradlew.bat lint` 或 `gradlew.bat assembleDebug`，不要求每次全部运行。
- 涉及设备、媒体或系统生命周期的行为，应记录所需的真机验证。
- 无法运行某项检查时，明确说明原因和未覆盖的风险。

## Commits

- 只有在用户明确要求时才创建提交。
- 待提交内容包含多个独立逻辑变更时，拆成多个提交；相关的小改动可以保留在同一提交中。
- 提交信息使用 Conventional Commits：`type(scope): summary`，无合适 scope 时省略。
- 常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`build`、`ci`、`chore`。
- summary 使用英文祈使语气，简短明确，不加句号。
- 简单提交可以只写一行。简单提交仅指不改变代码行为、构建结果或公共约定的单一机械变更，例如：修正拼写、更新失效链接、调整纯格式、补充不改变含义的注释。
- 代码行为、用户界面、构建配置、依赖版本、数据格式或架构发生变化时，无论改动行数多少，都不视为简单提交。
- 非简单提交默认添加简短中文正文，说明目的、关键内容和已执行的验证；没有相关内容时不添加空模板字段。
- 非简单提交正文至少包含两部分：变更目的，以及关键行为/兼容性影响；涉及测试、构建或发布时，单独列出实际执行的验证命令。
- 正文应使用真实换行，禁止提交字面量 `\\n`、HTML 换行或把多项变更压缩成一句笼统描述。验证命令逐项使用反引号包裹，便于复制和审阅。
- PowerShell 下不要把 `\\n` 当作换行转义；创建多段提交说明时使用多个 `git commit -m` 参数，或先生成临时提交说明文件后使用 `git commit -F`。临时文件不得提交。
- 仓库没有 PR 流程，不依赖外部 PR 描述保存提交所需的上下文。

简单提交示例：

```text
docs: fix broken TransVortex link
docs: correct README typo
chore: normalize line endings
```

非简单提交示例：

```text
build: bootstrap Android project

建立单模块 Kotlin 与 Compose 工程，并加入 Gradle Wrapper。
固定 Android 工具链版本，同时排除本机 SDK 路径和构建产物。

验证：`gradlew.bat clean lint assembleDebug`
```
