# E02/E04组合候选 VPS 实际普通回归：28通过、7异常

固定源码 `923170abd8e468d62eb8404b16b990609a4da42a`，2026-10-16 13:54:12.303208Z—13:57:02.825513Z经原212实际执行 **六类35项：28PASS / 0FAIL / 7ERROR / 0SKIP**。fresh六份XML精确匹配固定类集和实际testcase数，guard exit1 / child_command_failed；不是全通过。

| 类 | 实际 | 通过 | 异常 |
|---|---:|---:|---:|
| E02_23Test | 6 | 1 | 5 |
| ApiCreativeSquareCommunityPolicyTest | 14 | 14 | 0 |
| ViewCreativeSquareCommunityPolicyMyBatisTest | 2 | 0 | 2 |
| ViewCreativeSquareCommunityPolicySqlTest | 2 | 2 | 0 |
| E04_07Test | 7 | 7 | 0 |
| TopicCompletionCouponRewardServiceImplTest | 4 | 4 | 0 |

7异常均发生在H2 fixture创建JSON_TYPE别名：`CREATE ALIAS JSON_TYPE FOR 'com.chengyinhub.business.longrun.E02_23Test.jsonType'` 被实际H2 1.4.200以expected identifier /42001-200拒绝；位置为E02_23Test.fixture:177及旧MyBatisTest.createSchema:106。因此相关SQL业务断言尚未执行，不能当生产MySQL错误或7个新BUG。已交原E02作者唯一1修复槽，只修此前已授权两个测试的H2别名语法，保全部8方法/业务断言/负控/生产XML，不升级依赖或弱化身份验证。新源交付后只复测这两个原异常类，不重跑已通过4类或全35。

源包/依赖/守卫/精确六类未变；正式准入可用内存2,824,240KiB。守卫容器 `c2bb12cb40d9b43ef08847f80ebd8cf9adef6fa37f58a2ffb08639ad13c707dd` 已经显式chengyin-aws精确ID过滤确认清理，当前无活动Java。source/cache/完整日志/六XML/guard与targeted-summary均保留。两次先前0项拒绝回执已另存130903Z和132231Z后缀，哈希逐字核对，summary保留全部历史。

E04两类11普通项通过只证明本次普通回归；真实MySQL新5+旧3、Redis/运行HTTP/设备业务/集成仍未验，迁移源码未执行，未合并部署。新history_id依赖代码不能部署到未迁移数据库。详细逐XML SHA256及guard/源包见[summary.json](summary.json)。

# 历史准入拒绝回执

固定公开源码 `923170abd8e468d62eb8404b16b990609a4da42a`，直接父E02交付 `13be997646c88b9eea282cc8c8225b42820dc3b3`，含此前A `dedb44d54a4a277273943c0cbbca9f6e7528482a`。已按C00补充授权 `225b59efa72bf54423fc3a5f6069bff6cc72353e` 核差异：E02五个生产文件/两旧fixture/新回归，E04精确producer/mapper/consumer/回归和一份新迁移源码；未改依赖/CI/入口。

2026-10-16 13:09:03 UTC，现有212返回 **exit75 / insufficient_start_memory / containerId=null**。MemAvailable 2,192,124 KiB，约2140 MiB，低于2560 MiB门槛。**实际执行0项、fresh XML0份、target目录0、Maven日志未生成，NOT_RUN**；不是源码测试失败、不占作者修复槽，也不继承A或旧PASS。

## 已固定普通类选择（声明35项，全部未执行）

- `com.chengyinhub.business.longrun.E02_23Test`：6项声明
- `com.chengyinhub.web.controller.api.ApiCreativeSquareCommunityPolicyTest`：14项声明
- `com.chengyinhub.business.mapper.ViewCreativeSquareCommunityPolicyMyBatisTest`：2项声明
- `com.chengyinhub.business.mapper.ViewCreativeSquareCommunityPolicySqlTest`：2项声明
- `com.chengyinhub.business.longrun.E04_07Test`：7项声明
- `com.chengyinhub.business.service.impl.TopicCompletionCouponRewardServiceImplTest`：4项声明

ApiCreativeSquareCommunityPolicyTest含8个普通方法和1个六行参数化方法，因此声明14项。选择仅覆盖直接受影响的六类，不是全量回归。

E02真实SQL/H2别名只能验证有限查询合同，不能当真实MySQL JSON行为；act/unact mock委托边界不能当真实写库验收。E04新增E04_07MySqlIT5方法及旧RedemptionRewardClawbackMyBatisTest3方法均未运行，真实MySQL/Redis/HTTP、设备业务、集成合并部署均未验。

新迁移 `chengyinhub-admin/sql/migration_topic_completion_coupon_history_receipt_20261016.sql` **仅源码供审查，未执行**。旧行保持NULL、不推断回填；依赖history_id的新源码不能部署到尚未加列的数据库。

源包 SHA256 `f53588eea5481ea40d9f80513e43b3d038ef5ce48e46ec2510028f4beeabd2ec`；守卫SHA256 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba`。任务目录 `/home/ubuntu/previews/cy-vps-extra-923170ab-20261016` 保留source/cache/源包/selection/原run/result；显式chengyin-aws精确容器名过滤已核无容器。详细回执及哈希见 [summary.json](summary.json)。

只在资源自然满足现有门槛时考虑必要重试，原拒绝不覆盖。不新增入口/网络/CI、不停止别人、不升级加费/加swap、不执行迁移/合并/部署。

## 13:22 UTC 自然内存恢复后的第二次准入

13:21 UTC只读预检为2,838,712 KiB，超过2560 MiB门槛；固定源包逐文件比对一致、守卫hash不变，随后调用原212。正式调用时MemAvailable已回落到2,460,648 KiB（约2403 MiB），2026-10-16 13:22:31.317566Z—13:22:31.372736Z再次返回exit75 / insufficient_start_memory / containerId=null。实际仍0项、XML0、target0、无Maven日志，不是测试失败。

首次13:09原run/result已逐字保留为unit-run-admission-refused-130903Z.json和unit-guard-result-admission-refused-130903Z.json；当前unit-run/result是第二次回执，summary同时保留两次hash。显式chengyin-aws精确name过滤重试后为空/exit0，确认没有遗留容器。没有动其他任务、资源门槛、服务或CI；等待自然资源恢复再由原守卫准入，35项仍待验。
