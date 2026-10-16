# 当前恢复入口

## 已完成的新源码交付

E01-19（EXTRA旧packet，不另造bugID）已由正式移交的CORE/C03 writer完成。公共源码 **`e0e3f517b8e6deb26ed3697a00d38ae14f015e76` / PR44**，直接父`0d63043ad06775bc3dc52fb925620ef36d1081a7`。生产仅ApiTopicController.topicLike一处条件：显式type0取消DELETE0也成功；legacy空type toggle、负数失败、异常传播、member/topic过滤及选中ID不变。新增5个有界读屏障/负控回归源码，旧测试/Service/Mapper不改。

状态 **源码已交；本地普通2类10实际PASS，[公开报告已核读](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md)，真实数据库/HTTP/业务未验**；作者已停写、锁保留，实际源码writer0，无新已证实READY项，不凑50/6线。根因、精确3路径与正式旧作者停写/新作者移交见[E01_19_SOURCE_CLAIM](E01_19_SOURCE_CLAIM.json)。

## 三队新候选与后续派工

最新公共源码分别CORE e0e3f517（E01-19，普通10实际PASS）、EXTRA faefbaf8（E02-24评论重放父目标绑定，新普通60声明）、第三bde356f3（STAT02年份/GROW02午夜时点，新普通28声明）；CORE已获本地新10项定向PASS；EXTRA/第三仍NOT_RUN，旧PASS只覆盖表中旧SHA。EXTRA/第三新交付只在原owner范围，未向CORE整枝灌入。

E02-25真实RR重复键恢复packet现由C00精确追加授权给原EXTRA/E02：仅新Mapper方法与catch当前读、原SQL projection/WHERE、两唯一测试及说明，见[E02_25_SOURCE_GRANT](E02_25_SOURCE_GRANT.json)。不改初始读/全局隔离/权限/DDL，保E02-24父目标绑定；待原owner实际ACK，不假称已运行。真实VPS是否RR未知，不把条件触发说成现场已复现。

本地要求的真库/Redis现有入口映射已在[REAL_STORAGE_ENTRY_MAP](REAL_STORAGE_ENTRY_MAP.md)：固定CI名单只覆盖明确列类；其余缺批准真存储入口，212不能替代。Cloud不运行/改CI/增网例外。本地另已发布[入口缺口与候选清单](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_DB_ENTRY_GAP_20261016/README.md)，独立真库入口仍待用户对该具体资源方案答复，未自行创建。

## 新候选本地验证进行中（开发是否有READY单独记录）

本地最新已回CORE e0e3f517普通10项PASS；EXTRA faef首次03:40:30Z锁拒绝0项已公开保留；锁释放后重试启动，第三bde随后串行，未接结果。旧源码任务EXHAUSTED不代表本地验证停止，也不关闭未验层级。

## 本地下一项验证

唯一实际验证者：本地`01a13eed-e4d9-77f3-b875-c72d26ea7d59`。新候选普通套件TopicLikeExplicitCancelConcurrencyTest5＋ApiTopicLikeDirectionTest5，已由本地实际执行10/10通过，0失败/错误/跳过，fresh精确2 XML、212guard0及清理已回报；C00已核读PR24@5a90a1e81db8274960c3d5dee9d6646aa73311be的公开README和结构化summary；原始XML/日志由本地保留，未声称重新执行。命令/前提见[移交JSON](BACKEND_VALIDATION_VPS_HANDOFF.json)的core_latest_delta_validation；只按已批准212准入，Cloud不执行，不重跑全483/157。无新DB/Redis/DDL前提；真实SQL交错与运行HTTP另验。

## 各候选结果边界

| 公共源SHA | 已签收范围 | 未覆盖 |
|---|---|---|
| 1e403b13 | 原冻结231项VPS自动化PASS | 后续任何源码与运行业务 |
| 0d63043a | 原8失败类157项针对性PASS | 新E01-19、其他类新SHA实跑、真库/HTTP |
| e72eff7e | EXTRA原2失败类56项针对性PASS | 后续源码、新305全跑 |
| b45664de | EXTRA E06单类5项针对性PASS | 新76全跑、真库/HTTP/业务 |
| 2babbe28 | 第三队编译＋普通2类24项PASS | 11真MySQL仍NOT_RUN、真实并发/HTTP |
| e0e3f517 | 本地普通2类10实际PASS、[公开报告已核读](https://github.com/liboyang42-cpu/hengyin/blob/5a90a1e81db8274960c3d5dee9d6646aa73311be/docs/verification/mini-bug-arch-20261015/VPS_CORE_e0e3f517/README.md) | 真SQL/HTTP/业务均未验 |

原69c初始化失败、f7的483失败、474的305失败、ed598的76项1失败，以及两次212锁拒绝均保留独立证据；对应失败已按各自针对性复测关闭，不改写原失败为PASS。[VPS_REPAIR_ROUND_1](VPS_REPAIR_ROUND_1.json)及PR24保留原报告/哈希。

## 当前真实阻塞

- E04-07：consumer已EXTRA/E04持有，旧BLOCKED_OWNERSHIP已纠正。通关producer未持久原historyId，时间窗同模板猜券不可用；已有receipt helper不等于已获持久/旧日志未知身份合同。BLOCKED_CONTRACT，不猜ASC/DESC或奖励政策。
- E02-23：一次离线legacy迁移不提供后续旧帖的权威新帖映射；仅数值ID会错收藏，client_request_id字符串本身亦可由新客户端提供。完整切源/桥投影及旧内容更新审核删除责任须原owner冻结；不擅自双写/DDL，BLOCKED_CONTRACT。
- 其他已登记政策/审核版本依赖继续保留，不因“继续”猜权限/收费/产品决定。两项只读结论见[EXISTING_BLOCKERS_REFRESH](EXISTING_BLOCKERS_REFRESH.json)，未增加确认bug数。
- 裸SQL疑点仍NEEDS_REACHABILITY/NO_SOURCE_CHANGE；无真实业务可达证据不改共享脱敏器，不计新已确认bug。

## 协作与未验收

最多6名gpt-6.1-sol；每队反馈失败只占一原作者修复槽，其他真实独立源码继续。C00唯一全局源账，EXTRA/第三队维护自己的交付账；Claude UI/新功能、iOS各保留原锁，Flutter排除。未转交文件只读，换writer必须有原作者停写和精确基线/路径证据。

Cloud只写源码/必要回归并push，NOT_RUN如实记录；不编译/Java/Maven/Node/lint/CI触发/数据库/VPS或替代环境。真实HTTP/身份业务、真MySQL/Redis、设备、合并、部署/业务迁移仍未验收，TEST_DEPLOYED=NO。E06分享HTTP503合同与本次取消ACK兼容口径见[BACKEND_CHANGES](BACKEND_CHANGES.md)/[IOS_HANDOFF](IOS_HANDOFF.md)，未收到对端ACK不假定已通知个人。

[源表](LONGRUN_SOURCE_OWNERS.json) / [CORE队列](LONGRUN_CORE_50.json) / [余量](LONGRUN_REMAINING.json) / [旧checkpoint历史归档](LONGRUN_CURRENT_HISTORY_PRE_E01_19.md)。恢复核公共head与WIP，不覆盖工作树；平台终止时依此公共记录恢复，不承诺永久运行。
