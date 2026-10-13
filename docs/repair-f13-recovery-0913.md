# F13 同设备漫游恢复（本地候选）

基线 `5534654ac`，分支 `codex/repair-roam-recovery-0913`。仅本地实现和隔离测试；未生产迁移、发布或微信上传。原生 UI 待完整集成后由总控复用开发者工具验证，不把 Node handler 当作原生点击证据。

## 接口与持久化合同

- `POST /api/roam/reveal` 增加可选 `clientSessionKey`：32 位小写十六进制，由客户端在首次写入前使用 `wx.getRandomValues({length:16})` 生成并确认本机持久化成功。它只是关联键，权限仍由登录 owner 校验。
- 首次 `sessionId=0` 且带 key 时，在原事务中按 `(member_id,client_session_key)` 唯一键插入/取回同一会话并锁行，再执行原格子写入。并发、超时后重放仍只建立同一会话。旧无 key 路径兼容，不限制同一 owner 建立不同 key 的会话。
- 带 key 的 reveal 回执 `sessionId` 为十进制字符串。客户端只接受正 Long 范围内的精确字符串或安全整数，拒绝已经舍入的 Number；后续 reveal/finish/lookup 均用字符串。
- 新增 `GET /api/roam/session`：传 `clientSessionKey` 或 `sessionId`，始终 owner 范围查询。双参必须匹配。缺参、空参、非法参、冲突报业务错误，不能冒充 `NOT_FOUND`。跨 owner 单参查询与不存在返回同样的 `NOT_FOUND`，不泄露或接管其他玩家会话。
- 返回 `{state:'ACTIVE'|'FINISHED'|'NOT_FOUND', sessionId?, clientSessionKey?, result?, resultComplete?}`。`NOT_FOUND` 仅表示本次读取未找到，不证明先前事务未提交；客户端后续只使用同 key 幂等写覆盖在途竞态。
- `FINISHED` 新会话的 `result` 是原 finish 成功数据的 JSON 快照，包括实际 XP、勋章、商店勋章和可选回顾；与原结算 CAS、奖励执行同事务落库。保存失败整体回滚，不能先发奖励后丢回执。旧已结束会话没有快照时 `resultComplete=false`，仅返回已落库 XP/格子/地点计数；不查询当前徽章来猜当次奖励。
- GET 只执行 owner 限定 SELECT，无清理/过期/奖励写入。现有 `production-write-gate.js:isWriteRequest` 对 GET 自然放行，无新增白名单或写闸改动。

## 客户端恢复与可见动作

- 每个账号独立本机桶，首次写前保存 key、精确 sid（若有）、待确认格子、本次格子集合、结束意图、原始米数、存档关联和足迹/照片/已完成地点事实。存储异常及静默空写通过返回值与读回比对识别，失败不先发首笔写。
- 首批 HTTP 502、wx timeout、成功壳缺 sid 都保留队列并进入精确查询。查询失败保留未知状态，不当作未提交。`ACTIVE` 排空原队列；没有结束意图则继续本次探索，有结束意图才继续原 finish。
- finish 回包丢失后先 GET：`FINISHED` 展示原快照，不重复 finish；`ACTIVE` 在原队列确认后继续原 finish。已知 sid 的普通 reveal 失败保留原有人工重试。
- 冷重进自动读取当前账号的恢复记录。常驻页面切号时停止旧跟踪并作废旧异步世代，切换存储桶；切回原账号仍读取原记录。旧回包不得改新账号队列。
- “核对漫游记录”“核对结算结果”使用现有错误组件和 map 提示动作；恢复到 ACTIVE 后仍由用户点击 GO 启动定位，不自动开启跟踪。正常结算后再次主动 GO 创建新的关联，不复用已完成会话。
- 完全未产生格子且尚无服务端会话时，不提交注定失败的 sid=0 finish，也不虚报零奖励；精确查询确认 `NOT_FOUND` 后展示“继续本次探索”，用户可保留本机记录继续后再结束。
- 历史存档失败不能清除恢复记录。存储恢复后精确回读原结算并重建该次存档，成功读写后才移除恢复记录；不重复结算。重复结束/核对共享在途 Promise。

## 迁移

`chengyinhub-admin/sql/migration_roam_session_recovery_20260913.sql` 在现有 roam_session 增加 nullable `client_session_key`（ascii_bin VARCHAR(32)）、nullable `finish_result_json`（MEDIUMTEXT）和 owner/key 唯一键。旧 NULL 行不回填，不猜历史孤儿归属。

只在现有 localhost:3317 MySQL **8.0.45** 容器的新 UUID scratch schema 执行实际迁移文件。未读写已有审查 schema、未重启/删除容器。后续上线必须先按迁移流程落库；本文不是落库回执。

## RED → GREEN 证据

- `/tmp/f13-recovery-bootstrap-red.log`：旧 bootstrap 同 key 返回不同 sid；`/tmp/f13-recovery-bootstrap-green.log` 转绿。
- `/tmp/f13-recovery-lookup-red.log`：旧 GET 404；`/tmp/f13-recovery-lookup-green.log` 转绿。
- `/tmp/f13-recovery-snapshot-red.log`：结束后缺完整结果；`/tmp/f13-recovery-backend-green.log` 转绿。
- `/tmp/f13-recovery-finish-client-red.log`：冷重进无法完成已有结束意图；对应 `-green.log` 转绿。
- `/tmp/f13-recovery-account-red.log`：常驻页切号仍残留旧队列；`/tmp/f13-recovery-account-green.log` 转绿。
- `/tmp/f13-recovery-new-session-red.log`：正常完成后 GO 复用旧 key；`/tmp/f13-recovery-client-green-11.log` 转绿。
- `/tmp/f13-recovery-empty-red.log`：零格子仍发送 finish；`/tmp/f13-recovery-empty-green.log` 转绿。
- `/tmp/f13-recovery-archive-red.log`：存档失败却移除恢复记录；`/tmp/f13-recovery-archive-green.log` 转绿。

后端命令（48 项，含真实 MySQL、旧奖励/AI/写序回归）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_PORT=3317 mvn -q -pl chengyinhub-admin -am -Dtest=RoamSessionWriteMybatisIntegrationTest,RoamFinishCasTest,RoamXpTest,RoamShopBadgeTest,RoamAiContentApiGateTest,RoamTileMemoryPageTest -Dsurefire.failIfNoSpecifiedTests=false test
```

包括真实并发同 key bootstrap、跨 owner 同 key 独立、旧 NULL 会话、超安全整数 sid 精确字符串、非法参数、finish 快照回读不重复奖励、注入快照失败后会话和同事务 growth 写入一并回滚。旧 AI 测试只补新增快照写的正常 Mock 回执，原断言保留。

客户端命令：`node --test tests/unit/roam-*.test.js`。旧距离/顺序测试补真实 owner、可读写 storage 与已知会话形态，保持原距离、队列及重复写断言；新测试使用真实 Page handler 和隔离的服务端事实模拟，无真实网络/身份/资金操作。

最终回归：`/tmp/f13-recovery-backend-final.log` 48/48，通过（22 项真实 MySQL IT）；`/tmp/f13-recovery-client-final.log` 267/267，通过。两个进程均实际回读 exit 0，无跳过。

UI-GATE-0 的 roam 旧登记点随源码位移，获总控授权只迁移 4 条 U4 / 10 条 U5 的行号及 key digest。`/tmp/f13-recovery-baseline-map.json` 逐条断言调用点前后 7 行与基线一致；`/tmp/f13-recovery-baseline-structure.json` 验证理由、总数（U4 54 / U5 25）、其它分区及棘轮不变，无新增 allowlist。

## 独立审查后的第二版修订

第一版 manifest SHA256 为 `cf09f9921bbdb82b3300f8de40d4fc858934548acc76d898f2eacf041ecd2486`。独立 Standards/Spec 指出四项 P2，均保留负控后修订；后端、迁移及奖励规则未修改。

1. ACTIVE 冷恢复后继续行走，以实时原始 `_dist` 保存距离；只有已发起结束时才使用冻结的 `_lastDistanceM`。恢复 10m→继续到 50m→再次冷进仍为 50m。
2. bootstrap 未知后发起结束，先确认持久化结束意图再进行可失败的 lookup；lookup timeout 后冷进仍继续原结束，不丢意图。
3. `writeSessions` 与恢复写入一样，序列化副本写入并精确读回比对。静默空写不能成为成功；存档未读回前保留恢复关联，存储恢复后重建足迹且不重结算。
4. 真实 GO 初始化及异步商家/POI刷新保留本会话已完成地点，按同一 id 合回现有列表；仅 done 条目参与合并，不改变其它条目的原拼接或奖励规则。切号及主动开启新一轮清除旧会话内存 done，防止跨账号或跨轮带入。

独立原封探针：`/tmp/f13-standards-revision-red.log` 13绿3红→`/tmp/f13-standards-revision-green.log` 16/16。新增真实 GO 与异步商家回调负控：`/tmp/f13-spec-go-red.log`；正向及三项独立判据已进入正式 Page 测试。第二版全漫游 Node 回归 `/tmp/f13-recovery-client-v2-final.log` 为 271/271。新轮/切号 done 隔离另有 `/tmp/f13-done-scope-red.log` 负控。后端仍是前述同字节的 48/48 候选。

第二版仅同步同一批 U4/U5 行号（前后7行相同），映射在 `/tmp/f13-recovery-baseline-v2-map.json`；不改变数量、理由、其它分区和棘轮。完整第一版快照在 `/tmp/f13-freeze-v1/`，与第一版 manifest 逐文件哈希一致；本轮差分另存 `/tmp/f13-review-revision-v2.diff`。

## 第三版：读取链路归属隔离

第二版独立 Standards 发现在途旧账号 `/api/roam/pois` 回包会将 `found=true` 写入新账号地图。原封复现 `/tmp/f13-v3-account-red.log` 为17绿1红，修订后 `/tmp/f13-v3-independent-green.log` 为18/18。

同初始化链的新增负控 `/tmp/f13-v3-scope-chain-red.log` 还确认等待 nearby 后发旧请求、旧商家回包覆盖新地图，以及旧 tiles/page 回包混入新账号格子。这一版在发起时捕获账号和 `_roamWriteGeneration`：nearby 等待结束先检查，再检查 POI/商家响应及图标生成后的地图刷新；tiles 响应解析和取当前 pending 前同样检查，过期结果不写任何账号存储桶。切号/新轮释放旧 nearby 等待链，旧回调不触发新请求。现有网络失败仍显示原离线状态并可重试，没有删除查询或隐藏当前请求错误。

正式测试覆盖旧账号回包、同账号主动新轮回包、nearby等待中的两类变化、商家与 tiles 同链污染负控、当前账号正常加载/失败重试，并保留前版 done 续接与结算测试。第三版全漫游回归 `/tmp/f13-recovery-client-v3-final.log` 277/277；UI及scope门禁 `/tmp/f13-recovery-lint-v3-final.log`。后端/迁移仍未修改。

第二版完整快照 `/tmp/f13-freeze-v2/` 已逐项匹配第二版 manifest。第三版差分 `/tmp/f13-review-revision-v3.diff`，本轮仍仅同步原14个 U4/U5 行号与 digest，结构与7行映射证据为 `/tmp/f13-recovery-baseline-v3-structure.json`、`/tmp/f13-recovery-baseline-v3-map.json`。

## 残余范围

- 历史版本已经丢失 sid、也没有 clientSessionKey 的孤儿，不能自动追补；不猜“最新会话”。跨设备没有关联键的接管不实现，需独立产品规则。
- 正常同设备结束先停定位，地图外的旧定位回调由已有 `_blocked` 拦截；确认队列持久化后才 finish。若外部操作已经结束会话而本机仍有合法未确认格子，则保留原队列并提示核对，不将格子丢弃、补算到其他会话或猜已计奖。此非正常同设备路径不自动改写归属。
- 新增恢复 UI 尚需集成后实际节点点击与截图。测试不代表迁移已在线上执行、生产已部署或小程序已上传。

## 第三版总控集成补验

第三版双轴独立审查0项：Standards 57/57；Spec三个旧版反例RED→第三版GREEN，相关40/40。18文件冻结hash核对后，source c9a62c838 集成root 8b0871d2e。root漫游Node 277/277，六组后端48/48（含22个真实MySQL8测试）；与反应轮数三组联合97/97，无失败/错误/跳过，证据 `/tmp/cy-f13-r903-root-summary.json`、`/tmp/cy-f13-r903-root-backend.log`。

`/tmp/f13-v3-native-root/receipt.json`、`first-write.json`、`run.cjs`：借现有9763，通过真实页面handler生成clientSessionKey，发首个reveal前读取存储确认pending已持久化；丢失响应后冷reLaunch，使用原key读回精确sid `9007199254740993` 并排空队列。真实goStart进入地图、真实结束handler发一次finish；丢失结束响应后呈现核对入口，再冷重进只GET恢复同sid结果并归档、清除恢复记录。旧账号POI迟回不污染新账号桶。

四张截图 cold-active、continued-map、finish-unknown、cold-finished 总控均已查看；最后显示的+7是受控服务端结果，不是实际发奖。定位与API均为替身，业务handler未替换；通过调用真实handler验证，不把它冒充真人触屏、真机GPS或现场迷雾效果。cleanup恢复替身和存储并disconnect。预置done/10m不属于本次原生主链证据，相关恢复回归保留独立测试范围。真实迁移、支付/奖励及生产发布仍未验证。
