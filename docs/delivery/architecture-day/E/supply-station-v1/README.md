# E：经典商家邀约冻结单据点合同（源码候选）

代码已实现；Java、JUnit、MySQL、HTTP、供应商及生产 DDL 均 NOT_RUN。公共 parent 为 `6fcd5ac009cec1dd82c438528d24da30c1f302f4`。本分支只交 E 六个生产 owner 及必要配套测试；不含 G claim/evidence、A 核销、D 页面、root SQL/DAG/CI。

## 实际接线

- POST `/api/coop/invite` 原 owner/scope/type/条款权限保持；classic productType=1/type0/merchant/TOPIC 新邀约要求真实 `supplyStationId`，不使用 nodeId。文本审核前预检，Service 在原 topic 行锁内重新校验：同 topic/member、audit_status=1、del_flag=0；不新增 station.status 或 validation_method 门禁。
- GET `/api/coop/supply-stations?topicId=&merchantMemberId=` 只复用原主题创建者/PROJECT_MANAGE owner 上下文；返回 `requiresSupplyStation:true,stations:[{supplyStationId,stationName}]`。ID 输出十进制字符串；合法空集为 true+[]，不适用/越权/故障为错误，商家接受页不调用此 creator-only 端口。
- POST `/api/coop/handle` 的接受实参为 `(id,null,body.supplyStationId)`。新增三参 Service 使用锁定原 station 比较确认；两参 wrapper 只能接受无 station 的原历史路径，不能接受新 V1 或补绑历史 NULL。拒绝/取消/official/CLUB/legacy NODE 维持原入口。
- 公共 createCoopOrder 必须持原已接受 invite：topic→invite current locking read→原单/站点/有效订单 current read→插入。校验原身份，精确原单重放返回原 ID；不换条款/权益。新原单同 topic+merchant 的有效 status=0/del_flag=0 station 唯一，旧无 V1 单不猜站。公共无 invite、pending、fake identity、另一个原单同 station 均拒绝。原金额/分享/角色/权益/证据规则保持，terms_snapshot 仅追加 server-owned `supplyBillingScope={version:'REGISTRATION_STATION_V1',stationId,topicId,merchantMemberId}`。
- station 在 insert 后不可由 update 改绑，已选 station 邀约的类型/身份也不能被一般 update 换掉。SQL UPDATE 从不写 supply_station_id；接受 rowcount!=1 或原单 insert!=1/无生成 ID 整体抛错回滚。
- 邀约原 list/detail Service 按 exact(stationId,topicId,merchantMemberId) 批读 response-only `supplyStationName`（1000 tuple/批，不截断），已失效站仍只展示原名称；确切原行不可读则 name=NULL，ID 不换，不取当前第一站。名称不参与金额/归属/冻结，无数据库 name 列。
- response-only `requiresSupplyStation`：existing list 的原 fillTopicFacts 用真实 topic/productType 判定，classic merchant/TOPIC=true（历史无站也 true）、CLUB/official/legacy NODE/明确非 classic=false。**无法读原主题或旧 productType=NULL 的 potential classic 不能证明不适用，输出 NULL，禁止客户端按 false 绕过；已持原 V1 station 则为 true。** 原 Service 单行读取无新 topic 查询，未补 topic 的旧潜在 classic 保留 NULL；没有新详情 API。JSON name/boolean 为 READ_ONLY，客户端回传不能影响合同。

## 锁与并发

唯一性由所有本域 create/accept 共用 topic 锁串行，后续 effective order/original invite locking statements 关闭二阶 cache 并 flush 一阶 cache，RR 旧 locator snapshot 不决定 station 占用。SQL 不全量加载其他主题/商家订单；不以 Redis/本地缓存代替唯一性。没有新增独立线程/服务/依赖。已接受原订单重放不因后来站点状态变化重新冻结。

## 必要 schema 接缝（root 提供，未执行）

依赖 root canonical `chengyinhub-admin/sql/migration_20261025_coop_order_station_fulfillment.sql`：`coop_invite.supply_station_id BIGINT NULL`（signed，Java Long），原站点 signed BIGINT FK，正 ID CHECK 和 `(topic_id,to_type,to_id,supply_station_id)` 索引。历史不回填。E 不重复写 canonical/历史迁移。测试库专用 coop-it-schema 和三份从旧 base 提取的 scratch fixture 只补此 nullable 列；未以这些 fixture 冒充 root canonical FK/CHECK/DAG 验收。回退须保留新增列和原 JSON 合同，不删除已冻事实、不用旧二参路径执行新 V1。

## 测试源码与原场景

新增 13 unit（CoopSupplyStationContractTest）、7 Controller unit（ApiCoopSupplyStationsTest）、7 真 MySQL IT（CoopSupplyStationMySqlIT），均未执行。真库源码使用实际 Service + Topic/Invite/Merchant/Order mapper/XML + 原导出 schema/原站点 base DDL + Spring TransactionTemplate；辅助 credit/perk、审核失效控制为 doubles，不冒充真业务全链。两连接测试用 topic 锁和 MyBatis query barrier 定序，没有 sleep；验证同 station 只能一个原单。还覆盖原确认、精确重放、跨 scope/审核拒绝、原名称不换站、事务尾部故障整体回滚、历史不补/mapper 不改绑。root 后续登记/集中执行，E 未 dispatch。

原 CoopInviteServiceImplTest 122、CoopPerksSnapshotTest 9、PartnerNode 17、OfferAccess 6 的 case 名称全部保留；保留原容量/权限/金额/legacy/快照断言，只补当前持久行、topic.del_flag、接受更新和生成 ID 的 unit 装配，2→3 参数真实 caller 校验同步。**唯一明确退休的旧预期**：CoopPerksSnapshotTest.createOrder_noInviteId_skipsSnapshot 原先允许无 invite 直接 insert；现保持 case 并改为拒绝、零 insert、无 snapshot，此入口否则会绕过用户批准的原单站点冻结。未伪称该预期逐字不变。原同 reg 重放用明确原 invite+matching identity/current read 装配，原不重复插入/返回原 ID 断言保持。

实际轻量检查仅 git diff --check、XML 格式及上述原 case 名称对照；没有声称运行通过。有限补丁 secret pattern 检查不代替 root 完整 gitleaks。真实 MySQL 7例、canonical 迁移预检/partial-DDL/回退、HTTP 鉴权和 D/G/A 联合业务验收仍 NOT_RUN。具体后像/patch 身份见 source-evidence.json；最终 commit/ref/parent/tree 在交付回包冻结。

完整 source/caller/test diff 以 owned.patch.b64 标准 base64 保存，解码后为可直接 git apply 的 full-index patch；metadata 中 patch_sha256 对解码字节计算，避免文档中 patch 上下文空格引起 diff whitespace 噪声。
