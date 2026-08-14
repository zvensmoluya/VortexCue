# VortexCue 文档

这里按用途分层，日常开发只需要先读一份文档。

## 当前规范

- [`GUIDE.md`](GUIDE.md)：当前唯一的产品与工程指导；当前阶段为 M3 退场式播放与个人内容闭环，包含产品边界、目标路径、架构约束、实施顺序和验证门槛。

如果其他文档与 `GUIDE.md` 冲突，以 `GUIDE.md` 为准。仓库协作、验证和提交规则仍以根目录 [`AGENTS.md`](../AGENTS.md) 为准。

## 长期产品背景

- [`DOMAIN_CONTEXT.md`](DOMAIN_CONTEXT.md)：解释荧荧为什么存在，以及 sleep-first、界面退场、同步文字与本地内容生态等需要长期保留的产品设计 DNA。

它不是当前规格。涉及产品定位、信息架构、功能范围或相关架构调整时应先理解这份背景，再以 `GUIDE.md` 确认当前实施要求。

## 架构与产品决策

- [`records/DECISION_2026-08-13_TONIGHT_IS_CURRENT_QUEUE.md`](records/DECISION_2026-08-13_TONIGHT_IS_CURRENT_QUEUE.md)：Tonight 收敛为当前可恢复播放 Session / Queue，不维护平行未来计划。
- [`records/DECISION_2026-08-14_USE_ROOM_FOR_DURABLE_DOMAIN_DATA.md`](records/DECISION_2026-08-14_USE_ROOM_FOR_DURABLE_DOMAIN_DATA.md)：关系型持久领域数据升级为 Room，DataStore 收缩为轻量设置，Queue 恢复改用稳定内容 ID。

决策记录保留变化原因和取舍，当前实施要求仍须同步进入 `GUIDE.md`；两者冲突时以 `GUIDE.md` 为准。

## 验证记录

- [`records/M0A_VALIDATION.md`](records/M0A_VALIDATION.md)：M0A OLED 首轮真机记录。
- [`records/M1_SMOKE_VALIDATION.md`](records/M1_SMOKE_VALIDATION.md)：M1 播放器首轮集中真机 smoke test，记录已通过路径、真机修复和剩余覆盖。
- [`records/M2_SELECTIVE_LIBRARY_VALIDATION.md`](records/M2_SELECTIVE_LIBRARY_VALIDATION.md)：M2 选择性音视频发现、收录、首页内容集和持久化真机验收。
- [`records/M3_CATALOG_VALIDATION.md`](records/M3_CATALOG_VALIDATION.md)：M3.2a 内容目录、Recent / Collection 分离和 v3→v4 原位升级真机验收。
- [`records/M3_CONTENT_UNIT_VALIDATION.md`](records/M3_CONTENT_UNIT_VALIDATION.md)：M3.2b 稳定内容单元、`0..N` 文字轨目录和 v4→v5 原位升级真机验收。
- [`records/UX_AUDIT_2026-08-12.md`](records/UX_AUDIT_2026-08-12.md)：体验问题审计，区分代码事实、真机待验证风险、已采纳结论和剩余产品议题。

记录只说明当时做了什么、观察到了什么，不自动产生新的开发要求。

## 品牌探索

- [`brand/README.md`](brand/README.md)：品牌方向、视觉草案与形成过程的归档入口。

品牌探索记录设计上下文和候选资产，不自动成为正式发布资源，也不改变 `GUIDE.md` 中的产品与工程约束。

## 参考资料

- [`reference/research.md`](reference/research.md)：M0B–M1 产品与交互研究报告；保留原文，供需要时查阅。
- [`reference/PRODUCT_RESEARCH_BRIEF.md`](reference/PRODUCT_RESEARCH_BRIEF.md)：围绕字幕发现、内容入口和产品再定位的研究委托书；用于交给研究型模型继续探索，不是当前规格。
- [`reference/research2v1dratf.md`](reference/research2v1dratf.md)：第一轮深度研究结论；保留完整证据台账、字幕模型、服务蓝图和验证设计。
- [`reference/research2.md`](reference/research2.md)：在补入 ASMR、本地付费内容分发和跨语言消费背景后形成的 V2 结论；修正产品定义为 sleep-first 的退场式本地内容播放器。
- [`reference/PROJECT_SEED.md`](reference/PROJECT_SEED.md)：项目建立时的完整设计种子；用于理解背景和长期可能性。

参考资料不是当前规格。只有被吸收到 `GUIDE.md` 的内容才约束当前实现。
