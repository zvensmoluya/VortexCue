# Tonight 即当前 Queue（2026-08-13）

> 状态：已采纳并实现
>
> 取代：[`UX_AUDIT_2026-08-12.md`](UX_AUDIT_2026-08-12.md) C2 中“Tonight 是计划、Queue 是运行时播放顺序”的结论
>
> 实现提交：`0433590 refactor(playback): unify Tonight with current queue`
>
> 当前规格：[`GUIDE.md`](../GUIDE.md)

## 背景

2026-08-12 的体验审计把 Tonight 定义为一份可编辑计划，把 Queue 定义为 Player 的运行时播放顺序。这会要求产品同时维护两份高度相似的顺序状态，并解决二者何时同步、何者优先以及播放结束后如何回写等问题。

后续复核认为，这个区分不符合当前 sleep-first 使用场景：用户沉浸式播放时操作的就是当前播放顺序，并没有同时准备另一份尚未开始的“今晚”的稳定需求。可复用组合和长期未来安排属于 Playlist 的能力；过去听过什么可以由播放历史承担，不需要用独立 Tonight Draft 表达。

## 决策

- Tonight 是当前可恢复播放 Session / Queue 的产品表达，不是与 Player Queue 并列的数据模型。
- 用户在 Home 或 Player 中添加、移除、重排和清空 Tonight 时，直接操作 Media3 当前 Queue。
- 当前 Queue 的顺序、当前项和位置持久化为恢复快照；持久化不赋予它独立计划语义。
- Playlist 和 Queue 条目不保存各自的播放位置；Session 只恢复当前项及其位置，内容自己的文字轨和 offset 继续由内容目录持有。
- 打开或立即播放单个内容是 `replace`，“加入今晚”是 `append`，明确播放 Playlist、Folder 顺序或多选结果也是 `replace`；这些入口只操作唯一 Queue，不创建额外 Session 或播放完自动移除的临时条目。
- 自然播放结束或循环播放不自动清空当前 Queue，以便继续查看和恢复本次 Session。
- 当前不提供独立的未来 Tonight Draft。需要长期保存或复用一组内容时，使用 Playlist；需要回顾过去队列时，使用播放历史。

## 结果

- 删除独立 Tonight 状态、存储以及 Tonight 与运行时 Queue 之间的比对和同步逻辑。
- Home 与 Player 读取并编辑同一份当前 Queue，避免两套顺序出现分歧。
- `SessionStore` 只负责进程重建和次日恢复所需的快照，不成为另一份队列事实来源。
- 后续需求如果确实需要未来计划，应先判断它是否属于 Playlist；只有真实行为证据证明 Playlist 仍无法承载时，才重新评估独立 Draft。

## 文档优先级

本记录用于解释决策变化和保留追溯关系，不替代产品规格。若本记录、历史审计与当前行为存在冲突，以 [`GUIDE.md`](../GUIDE.md) 为准。
