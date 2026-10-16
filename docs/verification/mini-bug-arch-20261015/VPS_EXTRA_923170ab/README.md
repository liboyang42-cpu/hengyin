# E02/E04组合候选 VPS 准入拒绝回执

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
