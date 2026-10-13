# A 包交付与验证边界

本包从交接分支独立完成原订单/票务确定修补，不合并、不部署，不新增付款政策、UI、玩法或App。免费原报名/玩法不追加付款门槛；校验 payment_status=2 只作用于已有订单的已成立状态，原零元支付仍走原流程。

## 已实现的确定修补

- A1 unpaid 固定本轮 now/MAX、200页/500轮、跨轮游标及单项失败继续；原 PAYING 查渠道、未知不释放和唯一资源CAS保留。
- A2 探店日 purchaseKind=3 统一纯日期闸：恰好结束拒新用、缺结束暂禁、补齐恢复。静态码先当前资格再返旧URL；动态码和扫码两入口接闸；整单/按章核销在原 activity→registration 锁序重读归属/支付现态；自动扫描/更新均排除探索票；历史核销不重写。C 玩法新使用接合同，hasPaidRegistration 保留历史读取语义。
- A3 退款/补码/候补任务使用固定MAX、稳定页及跨轮进度；保原状态机和唯一名额/库存资源。V1官方报名锁原活动后以当前读核原名额，V2原规则不改。
- A4 实际报名渠道回调接现有inbox owner/fence，原资金事实和DONE同事务；同键异业务拒绝，迟到资金冲突不假ACK。取消活动中的已确认渠道SUCCESS使用MANDATORY内部补记，保持原整体回滚。新漫游券原log与待交付意图同事务，原history/来源收据一次交付；订阅start/end现态及MySQL续订affected2修正。
- A5 原结算批次漏写/金额回填/商家归属失败回滚；原提现账与CW/CD原号准备同事务，明确FAIL余额回补及原单终态同事务，UNKNOWN原待核。报名首次建单事务记原站内通知意图，不重造原单回放。

逐原ID见 [item-ledger.json](./item-ledger.json)：A1–A5共21条均区分本次子范围、尚缺代码/新政策、未验链；另161条混合项保留索引，其中22条按名称标A关联候选，仍需实际consumer核对，不按UI整项排除。维护详细证据见 [maintenance-evidence.md](./maintenance-evidence.md)。

## 同源码实际验证

- Node 专项 47/47 通过：registration-payment-verifier、baoming-page-reliability、baoming-load-state、club-waitlist-checkout、baoming-submit-inline-error；包括真实Page付款回调取原 registrationId 与稳定原事件键的断言。不是后端资金/身份或真机通过证据。
- 修改 Mapper XML 语法解析、JS语法、git diff --check 通过。公开差异文件 gitleaks 扫描无新增命中（最终详见validation.json）。
- migration-safety首次第五门因缺新migration明确依赖声明失败，原始输出保留。协调者唯一owner提供必要声明先导4db987a，A原样挑入173b869后migration-safety脚本通过（125分句器/41台账用例、实际新SQL分句、task bean注册、依赖顺序）。本地非PR事件跳过的声明门另用确切PR8正文运行pr-declare通过，输出独立保留；未改gate/workflow压错误。
- Java编译、JUnit、H2、真实MySQL/Redis、渠道签名、真实身份、微信开发者工具与真机 **未执行**。新增Java用例数只按源码记录，不能视为运行通过。历史193个局部结果不转移至本分支。
- VPS当前TCP grant未应用；HTTP80拒连只说明该HTTP入口未监听，不能证明SSH22不可达。既有CI不自动匹配交接base，CLI dispatch通路未具备；未改CI或依赖来压错误。

## 跨包与迁移

- 原样挑入B单一owner的 ISystemNotificationIntentService 两个接口先导（1ce1f978/d52c7654）。A可编译其签名，但单独运行尚缺B实现和其通知migration；集成前不能声称已送达或独立应用可启动。
- C原样挑入A先导 e1c8984/f109fae 的奖励接口/纯日期策略。默认未接入方法明确抛错；A正式实现覆盖，不把默认throw算业务交付。C仅接自己的漫游/玩法触发，A未编辑C文件。
- 新migration为 `chengyinhub-admin/sql/migration_mini_foundation_coupon_source_grant_20261013.sql`。只允许专用测试库验证；**未执行、未授权生产DDL**。迁移只创建原奖励来源收据，不回填未知历史券。
- 新/改测试scratch库使用 UUID：`chengyin_a_selfplay_`、`chengyin_a_funds_`、`chengyin_official_capacity_`、`chengyin_growth_tx_`；仅CREATE成功后清理自己创建的库，不DROP固定旧库。连接仅沿原CHENGYIN_TEST_MYSQL_*测试配置；新增官方用例和免费自玩仅允许loopback scratch。其他未修改旧测试的隔离不能由此宣称通过。

## 尚缺代码与未验断点

1. 普通跨票种共池/统一普通限购额度、高峰准入/机器人、新银行核对与合作退出补偿规则未确定；保原政策，不自行添加。
2. 旧 reward_drop_log 没有原coupon_history关联且缺新意图，不能判断已发/未发，保持UNKNOWN，不盲补。其他通用券来源完整覆盖仍未完成。
3. 已付交付超过原7天修复窗的统一权益/码/账/外部通知状态、人工待办，迟到已关SUCCESS的持久资金冲突consumer，跨重启持久扫描进度/最老失败年龄及实际告警收据仍缺。
4. 全资金receiver/source/rule快照覆盖、两账统一差异投影、完整高危操作审计/敏感读取矩阵未完成；原既有账和审计保留，本次确定写入故障修补不代表整项完成。
5. 新/旧Java组、真实两连接竞争/回滚/重复worker、原库存/积分/券/余额/账独立回读未验；B/C依赖尚未合并。官方新MySQL用例覆盖旧RR快照最后名额，取消内支付用例覆盖原外层回滚，但均未运行。
6. 真身份购票到使用及到期拒绝、实际渠道UNKNOWN/乱序、通知送达与微信/真机未验；不得以HTTP200、任务repaired计数或结构存在替代结果。

最终修改白名单/源码SHA见source-manifest.json及file-ownership.json。共享ci/migration-dependencies.json仅原样挑入协调者声明先导，A未编辑该文件。首次先导提交曾用hooksPath覆盖命令，仓库没有激活hook；这不是编译证据，后续提交使用原hook。完成可用检查后创建draft PR，仅供review。
