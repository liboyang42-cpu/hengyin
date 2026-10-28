# H：f1fe GamePlay 原 A/B 无现金身份接缝

源码包已收齐，**测试/parse/编译/Python/Java/DB/Docker/外呼/CI 全部 NOT_RUN**。由 root 集成后同源统一验收；本包不是 mixed、容量或资金通过结论。

## 身份与交付

- Parent：`f1fe75d646f8ae57864ee5603c68e70e7fabc854`
- Parent tree：`e293cc5976c22de88731ee792e75d16e6544fc7b`
- 公共仓库：`liboyang42-cpu/hengyin`
- 独立快照：`/workspace/hengyin-h-gameplay-actors-f1fe/{base,work}`，未切换/回退他人 checkout。
- 只改两 probe、两直接 Python 控制及本 H 文档。完整四文件 delta：[owned.review.patch.base64](owned.review.patch.base64)，逐文件后像：[POSTIMAGE.sha256](POSTIMAGE.sha256)。
- 保留 parent 已集成的 5189 严格入口及 root 的 synthetic `Path(directory).resolve()` overlay；没有修改 Cmd、资源预算、profile 入口准入或 provider/业务代码。
- 没有修改 B restore_business、C CrossDomain、E restore_application、auth helper/政策、Java child、properties、root CI、SQL/DDL 或其它 owner 源。

## 实际证据边界

root 已核 source f1fe 的 mixed40237888671/job121303274475/artifact12615537900：严格 GAMEPLAY_PROCESS_RECOVERY profile 已实际跨过；三物理恢复边界及 coreBusinessCurve 的 144 HTTP 原局部断言成立，整体仍在九保护表全空断言处 FAIL_PARTIAL，curves 尚空。

已读取协调 docs-only 提交 `192a122f3f0716d132eb7075fc7dba303feb7f13` 的原 readback/purpose package，**没有将 docs SHA 改成代码 parent 或运行 source**，未重复下载 artifact/log 或重跑。原标准 CI 的 15146 pass + 633 skip 亦不继承本增量。

原报告只证明 ums_member 两行及全行 hash、其余八表为空，**没有逐列余额/积分/归属/权限证据**。下述零值/合法状态是从真实 source/DDL 推导并加到新运行 guard 的必要检查，不声称原 run 已执行或通过了这些新检查。

## 原身份来自哪里，哪些事实必须真实查证

真实 `GameplayProcessRecoveryChild.seed`：

1. 原 child 通过实际 `UmsMemberMapper.insertUmsMember` 连续创建两名会员，ID 取真实 generated key，不硬编码 990005/990006。phone/nickname 是 source 的固定 A/B 值，wx_open_id 是 source 固定 recovery-UUID 形状，status=1、delFlag=0。
2. 真实 Mapper insert 固定 user_type=1、role=player；canonical baseline 的 balance=0.00、point=0、level_id=0、空 password、未冻结及 NORMAL 注销状态为原默认，未设置身份链接默认 NULL。没有充值/计费/授权 grant。
3. 原 `AppLoginService.loginByMember` 再取真实会员、检查身份及账号状态，创建原会话，并经 `recordLoginInfo` 写正常 login_time。不能把该正常登录时间误要求为空，也不造 mock principal。
4. 原 0600 私有 `identities.json` 由 child 写入真实 generated memberId 和生产登录生成的 token。只接受此原文件，固定 schema/server UUID、首 boot、source/child/config/JAR 均沿既有严格 binding。
5. `MerchantCommerceService` 的真实权益写落 merchant_subscription；报名权益落 registration_entitlement，个体能力落 member_capability。因此本 profile 对这些既有权益表、实际订单/券/提现表必须为空；不以零余额推定没有其它权益。

所有实际行字段仍须由 root 下一次同源执行读回。未知字段不被伪造；完整 SELECT * 的列/值/type 指纹全部冻结，不能删列或只比余额。已有全局 player 权限配置可能合法非零，**不改角色政策、不清空权限表**，而是冻结首阶段真实权限/凭据事实并要求守恒。

## 最小代码闭环

### 先捕获原事实，再允许后续观察

GamePlay 在首次 ready、任何 kill/命令之前：

- 保存原 identities 文件 SHA/首 bootId；
- 先落原九表 `financialBefore` rows/hash；原 `finance()` 编码及每一旧 case 的九表比较完全保留；
- 查询固定的既有订单/权益/提现表并要求为空；
- 从同一已校验 server UUID/schema 的真实连接 SELECT * 原两会员；
- `gameplay_actor_proof` 逐列检查身份、Decimal balance=0、int point=0、role/user_type/status/del_flag/level_id、未冻结/NORMAL 注销和正常 login_time；NULL/字符串余额、额外/重复/外来会员、错误账号或新增链接均拒绝；
- 冻结每原 actor 的完整列/值/type 行 hash、原 tokens/identities 指纹，以及原五权限表 + accountCredentials 的真实 rows/hash/DDL hash。

只存脱敏 hash/身份元数据，不存 raw 行、配置正文或 token。原 login/token 指纹仍保留；新增 actorProof 也含凭据派生指纹，root 的公共证据投影应沿原规则处理这些位置，私有 raw/hash 原证据不删。

### 同一严格 profile 中重读，不能拿任意 hash 当许可

`capacity_profile_binding` 将上述原 proof 与原 schema/UUID/seed boot、原 A/B、原 identities 文件 SHA 交叉核对，然后复制进现有固定 typed binding。

容量侧不自动认“有两行”或“hash 不变”为安全：

- 沿原固定路径、0600、只读挂载、源/class/config/boot/PID 核对，再核原 identities 文件字节；
- 对当前真实会员行重做全部无现金语义检查，再按完整 columns/values/types 指纹与首阶段原 proof 比较；
- 其余原八金融表和以下固定既有表必须为零：oms_order、oms_order_item、coop_order、registration_entitlement、sms_coupon_history、member_capability、merchant_subscription、ums_member_withdrawal、member_cash_withdraw；
- 原权限表/凭据 hash 与首阶段不一致拒绝，原会员任意字段变化也拒绝；
- refund_application 必须为空，随后仍走原 id=20 无现金手工 lease、原 ACK/replay/Redis 拒绝/恢复检查；其实现与断言未改。

`FINANCIAL_TABLES` 未改、ums_member 未移出。**默认原 auth/capacity 入口的原九表全空 require 及消息逐字保留**；该入口即使遇到合法 A/B 两行也拒绝。没有一般 nonempty baseline 参数、名单、registry 或任意 actor ID/config 路径入口。

### 早记首因，不能由清理次因覆盖

protected 读口在得到某表 rows/hash 时即保存脱敏采集进度，DDL hash 读完再补齐；完整 protectedBefore 在 admission 前保存。固定拒绝原因码及涉及的固定表名写入 firstFailure，不含 raw 行或异常数据正文。

两 finalizer 分别保留 businessResultBeforeCleanup、firstFailure 和原 failureType/reason；cleanup/finalization 写单独次因。不会因三次恢复、144 HTTP 或清理成功就把原业务失败升级成 PASS。

原完整 protectedBefore/After 全字段/DDL/权限守恒比较保留，并将新增固定经济表纳入 GamePlay 观察窗口。source、Cmd、class、网络、端口、预算、curves 并发、UNKNOWN 不重试、精确清理及原 shell exit 约束都未放宽。

## 控制源码与尚缺验收

原 31 个方法和全部原断言保留，新增 5 个方法，现 36 个声明方法（不把 subTest 数字当执行数）：

- 原 A/B 零现金正向及错误金额/类型/积分/角色/归属/生命周期/第三会员等拒绝；
- 当前完整行、schema/UUID、原 token/文件、权限和每一固定经济表的漂移拒绝；原退款表必须空；
- 默认入口仍拒绝两合法 GamePlay 会员，且先保留表 rows/hash 和固定首因；
- capacity finalizer 保原业务拒绝码与表证据，不被清理次因覆盖；
- GamePlay finalizer 同样保留原失败，不调用未知/缺少的下层 finalizer。

原两个 profile 控制只补新增真实 actorProof/identities 输入；没有删其负控、替换正确的 portability 修补或 mock admission 成功。新增 row/命令数据均为纯协议 synthetic doubles，不能代替真实 insert/login/MySQL 证明。

本轮只读源码、编辑、diff/hash 和公共 Git 对象交付。**Python 控制、真实新逐列证明、整个 mixed/cleanup/网络/预算结果均 NOT_RUN**；尚不能签 Q4/Q8、T13、whole capacity 或无缺陷。由 root 合成全部 owner 源后统一运行。

无生产/DDL/业务数据迁移影响；回退仅撤这四 Python 文件增量，不能回滚原 actor、资金数据或其它 owner 修补。

Root transport note: raw donor patch is losslessly base64 encoded to preserve literal blank-context lines. Decoded SHA256 3195fcb03bedd3fa56a746a170cc299af19b3e08e7f2f9d9f5e7aed32acfb717. Four source postimages are unchanged; the original author PATCH.sha256 names the decoded donor patch.
