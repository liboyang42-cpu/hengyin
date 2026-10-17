# E01 候补准入与 E03 冻结节点门槛：60 项普通回归通过

固定源码 `530e72281a9bd1a443f58a21119367d55eca3623`，提交链为 `acbd5f5411c6176db604d6297852c7772bdaa28d` → E03 `0ee9a998ff04f033c9518e7c7139a62a0f6dc585` → E01 `530e72281a9bd1a443f58a21119367d55eca3623`。实际 diff 仅两处 Service、两个新回归类及两份原说明，依赖未变。后续交付账 `541d36b29d4f1db5ef977a6ffc3fa6eaa77f317b` 仅改原团队三份交付文档。

E01 仅限制新增 WAITING：复用活动发布/未结束的锁读检查及票报名截止检查，不使用 sale_* 展示字段、不清理已有 WAITING、不改 OFFERED、取消、到期释放或共享策略。E03 仅在当前章节仍存在且同主题、冻结章节匹配唯一且节点证据有效非空时使用本局节点门槛；删章/换绑仍拒绝，缺失、损坏或空快照继续原实时检查，不改运行时推进及公开字段。

原 212 于 **2026-10-17 11:36:28.685565Z—11:39:15.680563Z（UTC）**完成：**4 类 60 实际 PASS / 0 FAIL / 0 ERROR / 0 SKIP**；4 份 fresh XML 精确匹配类集，源码测试方法名与 XML testcase 名逐项相等，guard 0/completed，BUILD SUCCESS。

| 类 | 实际通过 | 失败/异常/跳过 |
|---|---:|---:|
| com.chengyinhub.business.longrun.E01_13Test | 6 | 0/0/0 |
| com.chengyinhub.business.longrun.E03_04Test | 4 | 0/0/0 |
| com.chengyinhub.business.service.ClubWaitlistServiceImplTest | 24 | 0/0/0 |
| com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImplTest | 26 | 0/0/0 |

原计划 59 项保留：首次静态计数只识别短注解，漏记 PlayJourneyStoryServiceImplTest 中 `@org.junit.jupiter.api.Test` 修饰的既有档案方法。初次收集器因此将计数完整性记为 NOT_PASS，原 summary 另存 `targeted-summary-before-count-audit.json`；核对源码完整方法名集合与 fresh XML 后审定实际 60 项。没有改源码、断言或选择器，没有重跑测试。本次只验证新候选的直接影响类，不重跑旧 8/35/64 批次、不继承其他 SHA 成绩。

任务 `/home/ubuntu/previews/cy-vps-extra-530e7228-20261017`。源包 SHA256 `742495efc683e0c544aec8152ec1e7debdaa366a1199d4ca279d6fff3e99f9ad`；4327 个源文件逐项与源包一致。守卫 SHA256 `12374f742dcf1547c8eabf28c28403c94c8307fe20cba0a3a63d7f2b49764fba`；原 1536 MiB/1 CPU/256 PID/断网/无额外 swap 限额未变。精确容器 `829eb2b3519753b39ef712810b96201fe7039b21214283582f00f2d7764a9822` 已由守卫清理，显式 chengyin-aws 按完整 ID 查询为空、退出 0。source/cache/源包/完整日志/4 XML/原 run、guard、计数审计及初次收集回执均保留。

这些是普通服务回归与模拟持久化边界证据；实际 MySQL SQL、事务和锁交错、Redis、运行 HTTP、设备、业务及集成未验。保持现有入口，不新增；迁移未执行、未合并部署。逐报告、日志及计数审计见 [summary.json](summary.json)。
