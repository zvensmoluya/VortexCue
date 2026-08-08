# VortexCue 文档

这里按用途分层，日常开发只需要先读一份文档。

## 当前规范

- [`GUIDE.md`](GUIDE.md)：当前唯一的产品与工程指导，包含产品边界、交互默认值、架构约束和实施顺序。

如果其他文档与 `GUIDE.md` 冲突，以 `GUIDE.md` 为准。仓库协作、验证和提交规则仍以根目录 [`AGENTS.md`](../AGENTS.md) 为准。

## 验证记录

- [`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)：M0A OLED 首轮真机记录。
- [`records/M1_SMOKE_VALIDATION.md`](records/M1_SMOKE_VALIDATION.md)：M1 播放器首轮集中真机 smoke test，记录已通过路径、真机修复和剩余覆盖。

记录只说明当时做了什么、观察到了什么，不自动产生新的开发要求。

## 品牌探索

- [`brand/README.md`](brand/README.md)：品牌方向、视觉草案与形成过程的归档入口。

品牌探索记录设计上下文和候选资产，不自动成为正式发布资源，也不改变 `GUIDE.md` 中的产品与工程约束。

## 参考资料

- [`reference/research.md`](reference/research.md)：M0B–M1 产品与交互研究报告；保留原文，供需要时查阅。
- [`reference/PROJECT_SEED.md`](reference/PROJECT_SEED.md)：项目建立时的完整设计种子；用于理解背景和长期可能性。

参考资料不是当前规格。只有被吸收到 `GUIDE.md` 的内容才约束当前实现。
