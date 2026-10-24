# E：开局与伏笔进度闭包

候选 parent：`2fa7f983f865fe3bc343e60ed092adae7172e1dc`。独立工作树 `/workspace/hengyin-e-route-narrative`，分支 `codex/architecture-day-e-route-narrative`；不重写 publication、IM、Photo 等冻结候选。

仅改 Runtime、新增同域 `TopicRouteNarrativeProgress` 和本报告。Runtime 3890→3683 行；327 行 owner 持有原五个明确类型的 collaborator，不是 Spring 服务，不回调 Runtime，不持有 session mapper、授权服务、任意依赖 Map 或新事务。原 constructor 签名不变，最深 constructor 只多装配此 owner。

真实替换：`completeOpening/requireOpeningAccess/advance` 使用原开局读取和节点门禁；`syncThoughts` 使用原可认领章节证明、步数基线和进度转换；`writeSettled` 仍在原时点先结算伏笔再 `syncTheme`；全部状态投影使用原开局/伏笔字段和顺序。授权、报名、发奖、锁、CAS、session 版本、动作身份及 receipt 保存仍留 Runtime 原位置。

迁移 15 个既有方法正文：14 个逐字相同，`sessionRules` 仅将原 `journeyRules(topic)` 调用明确指向已存在的 `TopicRoutePublicationCompiler.journeyRules(topic)`；冻结优先、显式 null 拒绝、旧局读取实时主题规则的时点均保留。认领/步数块逐字迁移。其余 216 个 overload 保持原正文，除既定的 constructor owner 装配及 `syncThoughts` 块委托。root 三个 static facade 与 projection leaf、publication compiler、`queryState`、共享异常传播均未改。

实际执行：`git diff --check`；轻量 Python 声明/括号解析、上述正文及块逐字比较、owner 单向依赖检查；读取 74 个相关测试路径中本地可用的 62 个，15 个包含 Runtime，未发现需迁移的 Runtime private reflection 接线。其余 12 个测试文件未在此局部 checkout 读取，未称全仓测试审计。测试源码、场景、断言均未修改。只对三个候选文件做 secret-file-gate 与有限内容模式检查；未安装固定 gitleaks，完整扫描由 root 集成执行。

Java 编译、JUnit、MySQL、Redis、CI 均未执行；需与 A 的只读共享端口联合编译。无 migration、依赖、配置、DDL 或部署；回退需同时回退 Runtime 委托及新 owner，不能单删 owner。

只读交叉核对：A `062c6cb` 的共享端保留活动可用/取消、当前付费玩家/队伍及冻结队伍 owner 闸；直接读取 RUN/TEAM，不初始化、不刷新零 HP。H-AE-01 已由 root 指派 A：已确认共享却缺 RUN 须抛明确不可用，不能返回 null 后落到个人旧局。E 不 catch 此异常。

另报待裁决的既有投影反例：冻结快照有 `openingChapter`、尚无 `sys.opening.done` 时，原 `themeAction(END)` 可保存 `state.status=ENDED`；新 `queryState→toView→appendOpening` 仍可将投影视为 ACTIVE。该旧算法本次逐字迁移，未改开局可见性策略，也未称 query 状态边界已闭合。G 两个题组 static 调用仍保留，待 G 冻结 leaf 签名后单独最小替换，绝不 new Advanced God。
