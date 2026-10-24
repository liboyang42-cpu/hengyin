# F：C d2d09报名票应用精确技术owner（下一批）

结论：只登记实际新增Admin类型 `com.chengyinhub.web.service.verify.RegistrationTicketApplication`，owner ID C_REGISTRATION_TICKET_APPLICATION；parent `e9a9db4949e5bf8d51647ccb3d44ea5684960901`，独立codex/architecture-day-f-ticket-owner。root本轮e9/615冻结及CI40084734777不改，本片留C/d2和Bean后续批次。生产/Bean fixture/CI/registry不在本policy分支修改。

完整读取公共C源码 `d2d09a0deec78e298d0a814eca65bf80b8e121cb`（parent6d113bd0ec177ea6d2423fa2f76e7e50b44ed5ac）143行实际应用及完整迁移说明、生产caller。构造依赖仅ICmsRegistrationService/RegistrationSeatService/ICmsActivityService/IPerkLedgerService/QrcodeServiceImpl；有原短事务准备和receipt读、HMAC/PNG等原调用顺序。只观察原源码，不批准/改其归属、已付、prepare、nonce/TTL、UNKNOWN、权限或事务语义。证据/blob/hash/原始行及controller caller见source-evidence.json。

最小技术约束禁止应用反向依赖ApiVerifyController及BaseController（Common实际类型），不让新应用重新获得HTTP入口上下文。没有按包授权/禁止，没有把具体Qrcode公开实现误当God，也不禁止正常Application→domain公开命令。五个既有公开port尚未登记业务owner，保留调用并明确unscoped/UNKNOWN范围，不能把observed补成approved表/字段/command权。

本次没有新增allowed_direction，因为五个真实port都未属于现有精确role，硬编一个假owner会伪造边界。原59类型/48职责/60方向全部保留，现60类型/49职责/60方向；只有新1owner/1member/2精确forbidden targets。原3private Mapper/2whole-table facts/2ecbaseline/locking以及写owner、B/G事实、Query规模规则不变。已登记role之间的新未批准方向照旧红；未登记源/反射/XML/Bean/方法和具体权限仍未证明。

新增4项NOT_COMPILED协议fixture：实际五public ports和真实HTTP caller方向正控（unscoped>0/graph_complete=false）；反调HTTP controller/Base红且有hash；自行加依赖B事实存储role红；新应用全raw行缺失（即使同Admin其它类有输出）UNKNOWN。原89producer方法AST逐个不变；全Python209/209、0skip、19.046s，输出见python-tests.txt。它不是Java/Spring/实际QR或C九场景的回执；不借C的43Node或rootCI结果。

只改owners.json/test_collect_jdeps.py及本目录，无新parser/框架/依赖/job。其余五可信文件字节完全承接e9，六文件hash见trusted-files.sha256。此policy独立父树不含C生产应用，实际JAR没有新类型/原raw时必须UNKNOWN，root须与C/d2来源组合后才在下一批审定repin。不能把当前root56的无该类型当已覆盖，也不能本轮替换e9/615冻结。

Java/javac/jdeps/JAR/leaf/Spring/DB/CI均NOT_RUN。Bean fixture交付独立86fb（含4bb），使用root56d真实供体，不将其test或生产树混到policy。root唯一CI双ref和真实回读；下一版工具若审定采用本policy，应在独立工具增量中固定本完整SHA，不浮动/不候选自批准。不声明完整调用图、传递纯读、纯事务或全系统无环。
