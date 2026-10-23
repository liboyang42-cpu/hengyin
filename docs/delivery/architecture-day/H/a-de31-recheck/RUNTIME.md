# H 独立证据补记：A de31 的真库回归

结论：原 H-A136-01 的 PUT 经济字段保护已有真实积分/取消读回证据；原 H-A136-02 的整批删除锁读、当前scope及失败回滚已有15项真库回归证据。**H-ADE31-01 后台裸 POST 的 P1仍未闭合**；共同资金fence、B原attempt支付P1也不因这些局部通过而关闭。

这是对原 `REVIEW.md` 中“运行未知”的后续补记，原报告保留为当时事实。H仍未在云VM运行Java/数据库、未改生产、未派发CI。H工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，本轮起始 HEAD `a27e811d37a08a9b21bc0d110827cdb54419aeef`，干净；独占本H目录。

## 实际独立读取的证据

H下载三个GitHub Actions原artifact ZIP，用Python解析原始JUnit XML，并逐项核对receipt的source、attempt、testcase数量、failure/error/skip、Maven退出码及cleanup。各原XML/ZIP/receipt SHA256、完整测试名见 `runtime-evidence.json`，不复制XML内环境参数。

| 实际source / run | 类 | 结果 | 清理 |
| --- | --- | --- | --- |
| `66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329` / `40036839154` | Funds | 94通过，0failure/error/skip | CLEAN |
| 同source / 同run | Merchant cancellation | 14通过、1failure，0error/skip | CLEAN |
| `9b92fb5738244c677fea7470232f67d3b97b7a98` / `40037252877` | Merchant cancellation | 15通过，0failure/error/skip | CLEAN |

真库为批准GitHub-hosted路径的MySQL 8.0.45，REPEATABLE-READ。原商家失败精确为 `currentLockReadSeesWinnerCommittedAfterAnOlderRepeatableReadSnapshot`：`Long(1)` 与 JDBC `Integer(1)` 不相等，并非数据库状态读到别的数值。H读取9b92提交，parent正是66e9；仅把该例219行 `1L` 改 `1`，另新增保留原失败的协调报告；没有SQL/生产修改或删减业务断言。复跑workflow已completed/success，原失败XML哈希仍保留。

不合并计数：不能把不同source的94和15称作“同候选109全通过”。66e9原batch本身failure记录继续保留。三份receipt的 `registered_source_match=false`（登记的旧source为 `0de20ce8398a00b9bca6b83ccaccea270da66de7`）也原样记录；实际执行source由workflow、receipt.source、precheck.candidate和workflow_sha一致绑定，不把登记指针误称一致或自行豁免门禁。

## 源码身份与闭合范围

H取得66e9未截断完整tree，原de31审查涉及的两个Controller、商家Service/接口/Mapper/XML、报名Service/XML及Funds94 **blob全部一致**，见 `runtime-source.json`。9b92只有上述测试/报告差异，因此没有替换被审生产代码。

- Funds94中 `adminEditOfZeroPointOrderCannotMintPointsOrMoveInventoryOnMemberCancellation` 已实际通过；结合原完整caller/白名单审查，原PUT漏口的具体复现已闭合。
- Merchant15已实际覆盖旧快照后选中、旧RR下current锁读、全部ID有序加锁、当前topic迁移再授权、并发取消/选中、部分DELETE后计数不符真实回滚。结合原事务代理/锁序源码，关闭原H-A136-02的具体竞争。
- 这些是直接handler/Service/Mapper受控真库证据，不证明HTTP真实token/过滤链/全域RBAC/动态撤权严格in-flight语义。

## 仍阻断：H-ADE31-01，不在94项内

66e9的 `CmsRegistrationController` blob仍为 `29806316c297d6f74766788173adb9b16d0ae26d`。后台 `POST :214–220` 仍把整份实体直接交 `insertCmsRegistration`；Service `:243–245` 只补createTime；XML `:311` 可插入任意pointUsed/member/ticket/qty及可取消状态。原会员取消 `:2667–2702` 会按该无扣分依据的行返积分/释放库存。Service/XML也与de31逐blob相同，9b92未修改生产。

原报告的确定性反例及 `unbacked-post.review.patch` 继续有效：先真实占1席形成remaining9/total10，再由有add权限管理员给会员8裸建pointUsed1000且未扣分/预占的行，原会员取消后可凭空返1000积分并恢复那1席。库存上限存在，本反例在上限内，不声称无限库存。该H新增第95例仍未应用、未编译、未执行；94通过不包含它，不冒称已经实际观测资金损失。修复需封住裸建入口或明确合法代报名合同并走原建单命令，保留内部合法Mapper写。

root补报的IM两项/C158/静态门禁进度本轮未重复下载，不扩展为H独立运行结论。C1eb的原H12与158已在原报告独立实际执行，本轮无新源码无需重跑；它们也不替代C后端APPLIED/回执事务验收。

## 交付边界

仅新增本目录 `RUNTIME.md`、`runtime-evidence.json`、`runtime-source.json`。无schema/依赖/权限变更、无DDL、无真实供应商/付款、无合并部署。原PUT/删除保护不可因其他P1待修而回退；所有原资金/库存/账本/审计记录必须保留。本补记可改善原两个具体缺陷的证据状态，不构成整个系统上线裁决。
