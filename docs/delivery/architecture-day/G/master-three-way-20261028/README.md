# G 固定三方源码交付（NOT_RUN）

父提交：`62d4e0bbf47efa2f73aa7e604f1dfe092518d923`；治理供体：`1b71ae555a3141da948fa4e36ea68cb48a7b9b39`；共同祖先：`51f05499c1523648a328f57bcd7927f6d9546003`。独立工作树 `/workspace/hengyin-g-master-merge-20261028`，未切换或回退其他工作区。本提交不是 root 的双父合并，不继承旧供体验收。

## 已写源码与必要接线

- official-detail / club-detail / team-join 保留 master 的持久恢复、当前账号与目标校验，并迁入治理版登录继续动作的 owner/target/离页代次保护。
- coop-invite 保留 master 原 requestId、回执发现/精确回读、UNKNOWN 不重发；合入治理版新订单据点选择/确认。确认前冻结选择集合，串行发送消费逐目标冻结 payload；自己的成功出队不会使后续目标失效。未知结果保存原 payload/key，据点不同不能共用写身份。服务端请求指纹与回执的实际组合缺口由独立 review.patch 运输，**未写入本 donor 的公共服务/DTO/helper 路径**。
- simple 保留完整 V2 prepare/execute/read/recovery 日志，按 C 提供的 `isAuthenticatedContinuationCurrent()` 证明续登，不接受一般同账号 token 变化。所有请求保存各自 transport；quota 只消费一次且旧额度不能覆盖新动作。sessionChanged 只清退内存回调，不清原日志、不自动再执行。依赖 root 已审装 C 的 transport hunk；不重复修改该路径。
- fabu 保留 master adoption-source/sourceVersion、原采用 requestId/receipt、本人稿回读与失去 ACK 恢复；迁入治理版目标/代次校验。公共库列表复用真实 `/api/template/list` 完整分页，不把 adoption-home 的 recommend/hot/latest 摘要误当分页整库。实际采用仍走原 author/adoption 接口，精确 ID 与完整 advancedConfigJson 校验保留。
- master 已把旧 template 页拆成 shelf Component 和 topic-preview。原页仅迁 owner 生命周期，未复活旧列表/采用入口。治理版完整分页、旧回包隔离迁入真实新模块的独立 review.patch，保留 master 原 25 玩法策展、视觉与所有导航。分页无固定页数截断；total 未收齐/提前空页/重复或不精确 ID 均不发布子集。
- build-action-ledger 合并 master invoice 有限调用解析与治理版 deregister 精确闭包、verification-scan 常量纯性检查。恢复实例先解析，再按真实具名导入引用过滤；不是扩大 package 白名单。

## 测试源码迁移（全部 NOT_RUN）

保留原 master 用例及治理版额外负控；club-gather-arrival、roam-revocation 的冲突两边已含同一真实账号捕获方法，最终字节与 master 相同。

- 原退役 template-tab 11 类场景迁到真实 shelf/topic-preview：主题成功后游戏失败、缓存切回、异步游戏成功/失败不改主题骨架、当前 tab 加载隔离、单主题不假空（含真实变体负控）、空/错不注入假数据、完整分页、失败/早空不发布子集、账号/作用域/离页失效、完整主题采用不混小游戏 ID。原 200 页容量边界改钉实际新 shelf 单页 50+1，不取消分页断言；保留 25 玩法策展导致填充行不上架的真实含义。
- template-adopt 保留 master 原 ACK 丢失重进/来源改变/晚回执用例，并把治理版 4 个完整 interactionType/媒体/空语义/据点路由用例迁原 author/adoption-detail、原 requestId/sourceVersion/typed receipt，未沿用旧裸 ID 假回执。
- coop-invite 保留原串行、失败、幂等、存储、登录用例；源码断言改钉 master 真正的逐目标 payload 冻结，加两个据点键/回执与 UNKNOWN 原 payload 源码场景。
- shop-day-game-sheet / get-auth 保留治理版完整方法、真实委托链和负控，适配 master 拆出的后端 owners，未以截短窗口或邻方法代替 guard。
- batch4 将治理版站内成功/渠道隔离断言迁至 master 真实 delivery.summary/INAPP_STORED，保留 owner 闸与 UNKNOWN 独立计数。原 clicked SQL 缺该状态条件的跨 owner 最小 review 单列，不能删除此断言换绿。

## 集成前仍须完成的精确源接缝

1. root 审装 `template-shelf.review.patch.b64`、`invite-station.review.patch.b64` 与 `broadcast-click.review.patch.b64`（文件/前后像见摘要清单）。它们只改 master62d4→建议后像；不得整类覆盖 root 已组合源码。邀请服务的 `getSupplyStationId()` 依赖治理实体/mapper 同源合并，不能拿仅 master donor 单独编译。
2. `get-auth` 登记新增 supply-stations、AI recovery、club lead unlock 原回执，声明数 109 direct /40 req /5 own-media 是固定 master 上三处真实新增调用的**源码账目**，不是扫描结果。两处 action-receipt marker 当前保留 master 精确对象参数形式；若 H 合并实际改成治理版 `data/body`，root 必须据 H 最终原调用精确迁锚，不新增重复条目或放宽窗口。
3. **生成物不能伪造已重建。** `action-ledger.json` 保留 master62d4 完整字节；action-ledger.test 保留原冻结数 2417/2697/5534（以文件原断言为准），没有算术相加伪称新实扫。ui-integrity-baseline 保留已能自动合并的理由与 master 冻结摘要/冲突数值，未伪算最终各 owner 行位；ui-integrity 棘轮未扩免。root 全部源齐后须用原生成器在获准统一阶段重建最终台账、迁移精确 baseline 锚/摘要并登记实值。这四个交付后像均可重建，但此项标 **GENERATED_METADATA_PENDING**；当前 donor 不能声称 20 路全部验收完成。两个冻结台账供体的完整原字节仍可按上述公共 SHA 取回，未把任何一边的业务断言删除。

本次未运行项目 parse/import、Node/Python/Java/JUnit/MySQL/HTTP/Redis/Docker、CI 或微信操作。只做源码读取/编辑、Git 差分与摘要；`git diff --check` 的实际结果单列，不等于测试。未修改旧 SQL、CI、权限、依赖、分包、登录协议或生产资源。root 唯一正式集成与验证。
