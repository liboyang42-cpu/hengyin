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

## 5. 验证

```
mvn -q -pl chengyinhub-admin -Dtest=com.chengyinhub.business.service.orienteering.** test
```

隔离 JUnit only。编译 + 上述测试必须绿。
