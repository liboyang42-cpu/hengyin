# F13 漫游会话写入归属与结算边界

基线：`84e6b288fdd060aa864aafd9088e8eaebb64acbe`（独立工作区已含F18/F19）。规格：桌面报告《商家漫游与内容制作_第三轮.md》F13；总控追加要求将同 Controller 的 POI 发现、探店写入一并纳入会话边界。

## 入口与修复

Serena 符号和引用检查确认 `batchInsertTile` 唯一生产调用为 `ApiRoamController.reveal`，`updateSessionFinish` 唯一生产调用为 `finish`。原业务在 Controller 中。本次仅新增24行 `RoamSessionWriteService`，把POI发现事实的会话检查和插入放在独立事务内，原因见下方事务负控。

- `/reveal`、`/shop/visit`：Controller事务内先以登录 `uid()` 与 sessionId 查询 `selectOwnedSessionForUpdate`，SQL带 `member_id` 和 `FOR UPDATE`；仅status=0继续写。sessionId不属于登录用户或不存在均拒绝；结束或无有效状态均拒绝。未引入请求body.memberId，仍只用登录身份。
- `/poi/discover`：保留原普通读取作为前置检查及GPS/来源校验；实际insert之前，由 `RoamSessionWriteService.recordPoiFound` 在事务内重新按登录uid与sessionId取得同一行锁并检查status=0，提交发现事实。可选掉落在该事务外，保留原来的异常日志与响应行为。
- `/reveal` 首次无sessionId：保留懒建会话，创建者为登录uid；建会话与写格子同一事务。重复格子继续由既有唯一键和INSERT IGNORE保持幂等。
- `/finish`：在统计格子、POI、奖励及探店数之前取得同一会话行锁；保留原status=0→1 CAS和发奖逻辑。
- `/presence`：既有单条UPDATE已同时约束id、member_id、status=0，使用同一会话行的排他锁；此次不改。它不增加结算格子/POI/探店行。

并发口径：最后上报先持锁，则其提交后finish读到并计入；finish先持锁，则其结算提交后上报读到status=1，明确拒绝且不追加数据。不是靠Java普通读取后判断，也不是结算完成后再补数。

POI发现及探店原有说明为“本次漫游”，两者正是finish的统计输入；未找到允许结束后补传的规则。GPS阈值、来源对象审核、掉落规则、XP上限、徽章定义未改动。

## RED与真实数据库GREEN

测试 `RoamSessionWriteMybatisIntegrationTest` 使用真实Controller逻辑、真实RoamMapper XML、Spring事务代理及本机MySQL **9.7.1**。每次创建独立UUID scratch schema，结束仅删除自己创建的schema，不碰原审查库或生产。断言通过独立连接回读已提交表，不用手工写入期望结果。

生产未改时先跑两个负控：外人向本人session写格子、本人finish成功后再写格子。两者均返回 `code=200, newlyRevealed=1`，预期拒绝断言变红，`2 tests / 2 failures / 0 errors`，命令exit 1；日志 `/tmp/f13-red.log`。

修复后16项真实MySQL用例通过：

1. tile、POI、shop各自本人有效会话写入及相同请求重试只有一行；finish快照及预期XP一致，重复finish被拒；增长服务调用一次（shop不发本次统计XP）。
2. 三入口各自拒绝外人、不存在及已结束会话，独立回读零新增。
3. 三入口分别竞争finish，两种胜出顺序共6项；断言第二连接在InnoDB行锁上等待，随后回读本次统计包含最后成功写入，或者结束后零新增。
4. 懒建session归属于登录身份；结束后新session重报同一格，累计版图仍一行，分页读取记忆一致。
5. 保留最初两个F13负控用例，修复后均绿。
6. 可选发券失败不撤销已确认的POI发现：真实Spring事务代理模拟原发券REQUIRED事务抛错，Controller仍返回发现成功且数据库保留一行。

执行：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am \
  -Dtest=RoamSessionWriteMybatisIntegrationTest,RoamFinishCasTest,RoamAiContentApiGateTest,RoamCheckinTest,RoamPresenceControllerTest,RoamXpTest,RoamTileMemoryPageTest,RoamShopBadgeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

相关回归53项（包含上述16项），Failures/Errors/Skipped均0，命令exit 0，相关模块编译通过；最终日志 `/tmp/f13-final-v2.log`。增长服务“只调用一次”断言也包含在内。Surefire报告位于 `chengyinhub-admin/target/surefire-reports/`。

## 事务传播自审负控

直接给discoverPoi整方法加事务虽然通过初始52项，但会改变可选发券失败语义。`grantCouponById` 的REQUIRED事务抛错会将外层标记rollbackOnly；新增用例得到 `UnexpectedRollbackException`，1test/1failure，日志 `/tmp/f13-coupon-red.log`。因此撤掉discoverPoi整方法事务，改用仅负责受锁发现事实写入的短service事务，可选奖励保持事务外。此边界回归转绿后才交付，不将最初52项绿误当充分证据。

## 验证边界

增长服务和可选AI/徽章协作者在IT中为mock，因此不将调用一次或session.xp_awarded快照冒充真实member_growth入账、徽章发放或外部能力通过。未实跑源类型2商家完整探店业务，但owner/active检查位于分支前，相关已有回归通过。MySQL8指定版本、HTTP登录链、开发者工具/真机界面、生产均未验证；无真实资金、push、PR、merge或部署。

历史已经写坏的跨用户会话数据未自动清理；没有生产证据就不擅自修改历史账本。小程序pages/roam未改，由另一个任务处理F20。独立双轴审查交总控执行，当前保留未提交diff。
