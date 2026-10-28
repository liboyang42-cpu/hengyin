# 活动整单撤销与逐席位事实保护

代码已实现；Java/JUnit/MySQL/CI **未运行**。父为冻结 `f31a3745a31a8cad71997b228b731c34212a117a`，独立分支 `codex/architecture-day-a-seat-reverse-guard`。root已组合的 fd350 兄弟创建首锁不在此父中，须按本独立delta合成，不能整旧文件覆盖。B reader/sidecar当前读由B统一处理，本片不再改B或只应用旧reader注解建议。

实际读取 C `f1cfe74d58b766e385e639d40385e208790644a8` 的 `activity-reverse-owner.review.patch`、RegistrationSeatService/Mapper/XML。等价应用其required Mapper注入+5行guard：当前锁定多人报名存在status=1席位时，稳定拒绝整单清除并指向席位售后核验。status=0仅为准备身份，沿原合法整单撤销；原已结算/探索票/主题票/按章流水守卫、权限/金额/退款算法均不动。C服务只允许orderNum>1创建这些席位，guard同样使用当前锁定orderNum，未引入可空Mapper fallback。

真实入口为后台 CmsRegistrationController.reverseWholeOrderVerification → reverseWholeOrderVerification（3参委托4参）。当前activity→registration锁之后以C原Mapper按seat_no锁读席位，再进入原clearVerification/审计/台账/成长逻辑；C核销topic(如有)→activity→registration→seat，原注册锁串行化两条写链，本片没有在后方追取topic、没有改C任何源/SQL。只读源码分析不是并发实测。

原直接调用夹具只有 WholeOrderVerificationReverseTest 和 StoreVerifyGrowthDeliveryTransactionTest，均显式注入Mapper。前者原6方法正文逐字保留，新增3普通@Test：已核销席位+勾选冲回时拒绝且所有经济/审计/成长写为零；当前席位终态优先于聚合未核销投影；仅准备席位保留原撤销与原副作用。增长真库类仅增加4行fixture，全部原断言不变，不把mock席位读取称真实XML验收。新增9例单测源码、原增长真库4例均待root同头统一执行，未新派CI。

必要依赖：root先合入C真实RegistrationSeat领域/Mapper/SQL；A未复制C文件、新增DDL或默认成功实现。G逐席位原分项反冲仍由G真实实现，本guard仅阻止旧整单路径毁坏已发生事实，不宣称完整席位售后/部分退款已闭合。静态输出与文件hash见static-result.json。
