# C04-backend 交付报告

Issue: https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5  
Lane: C04 多俱乐部、赛事与真正的普通城市定向（后端独占）  
Repo: `github.com/liboyang42-cpu/hengyin`  
Base: `2ec68899099a1e250df0949a8383bf85b5d3ffe0` (`origin/master`)

## 1. 签收与库存

已读、未改（非缺口）：

| 文件 | 结论 |
|---|---|
| `OrienteeringParticipation.java` | 未成年场次事实记录，不是赛事权威 |
| `TopicCityOrienteeringClubGateTest.java` | 发布「不绑俱乐部」门禁，不是 TEAM 报名权威 |
| `TopicRouteSession.java` / Mapper / Scope | 12/35 生命周期，只读 |

远端 `service/orienteering/**` 在 base SHA 上不存在。`OrientSessionServiceImpl` 对 `game:` 实例抛 `ORIENT_TEAM_UNSUPPORTED`。现有 `IOrientScoringService.decorateLeaderboard` 是成长/主题榜装饰，不能替代赛事版本成绩。

## 2. 本车道写入（白名单）

生产：

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/orienteering/PublishedRuleSelector.java`
- `.../TeamClubEligibilityAuthority.java`
- `.../OrdinaryCityTimezoneQualifier.java`
- `.../EventScoreVersioning.java`
- `.../EventResultDispute.java`
- `.../OrienteeringEventException.java`（包内错误码）

测试：`chengyinhub-admin/src/test/java/com/chengyinhub/business/service/orienteering/*`

未写：公共 Controller / Mapper / migration / TopicRouteSession 写路径 / 小程序 / 旧 chengyin。

## 3. 首个可跑旅程

普通已发布城市定向（`productType=1`，上架，`publishedConfigVersion=7`，草稿 `configVersion=9`）  
→ TEAM 三成员，资格来自真实俱乐部权威（主理人无成员行 + 成员 status=1 + 第二俱乐部）  
→ 城市时区 Asia/Shanghai 逐项安全确认  
→ start 冻结规则版本 7  
→ SCAN/PHOTO/QUIZ 证据（拒绝 GPS）+ 有符号计分  
→ finish 写入 version=1  
→ `readback(fin-journey1)` 返回同一条结果；未知键 `EVENT_NOT_FOUND`，不新造请求  
→ 争议恢复指向原完赛 requestId 与 `originalRewardCenti`，`rewardKind=EVENT_SCORE`

## 4. 明确未做

- 未接公共 Controller / 未改 session writer（hunk 交 dot）
- 未跑生产 DDL、支付、发奖、真实 GPS
- 未恢复随机匹配
- 未用成长个人榜充当赛事榜
- 真实 MySQL / Boot 启动 / HTTP 为 NOT_RUN（本车道只做隔离单测）

## 5. 验证（已跑）

命令：

```
mvn -pl chengyinhub-admin -am -Dtest=com.chengyinhub.business.service.orienteering.*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`。chengyinhub-admin 隔离单测 **15 run, 0 fail, 0 error, 0 skip**。

| 测试类 | run |
|---|---|
| OrdinaryCityTeamEventJourneyTest | 1 |
| EventScoreVersioningTest | 3 |
| PublishedRuleSelectorTest | 3 |
| TeamClubEligibilityAuthorityTest | 4 |
| EventResultDisputeTest | 1 |
| OrdinaryCityTimezoneQualifierTest | 3 |

静态检查/编译：通过（test-compile + 上述 surefire）。DB/HTTP/App/CI：NOT_RUN。

Head（本车道）：`58113ef7c3818671ac31aa2c394562715b3c1087`（报告本次提交会再前进一格）。

## 6. 文件 SHA256（提交前快照）

| SHA256 | bytes | path |
|---|---|---|
| b20c62935a54aef6be24ef19478933296c87dc120793ef42a39f45e46332b263 | 2189 | OrienteeringEventException.java |
| ac2502818e0d264934f03f1e6cd75efe2bb547bd45668b683b712a96b6238ff8 | 5023 | PublishedRuleSelector.java |
| 30544c404e0bf6ff7626eca0806583558c54e52217e9cf7d5e85c208c6df1d2e | 6501 | OrdinaryCityTimezoneQualifier.java |
| 78921f6b32b4df8b8fa0bbedcb74e11cb1afd9acfb152c12ba37c5348c5442de | 9333 | TeamClubEligibilityAuthority.java |
| fab552aa09d16086fffbd33be181e574f713f6ffc35fa1bdcafc0d34118af728 | 17341 | EventScoreVersioning.java |
| 62abc03dab0b4b6ec097c93b65ee56e8164552664db7a3f517323c548c09b5c2 | 6941 | EventResultDispute.java |
