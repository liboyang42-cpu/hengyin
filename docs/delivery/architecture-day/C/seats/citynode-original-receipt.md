# 据点签发与原结果回读闭合

代码完成；相关 Node 344/344，零失败/跳过。Java 编译、JUnit、MySQL、HTTP/JWT、Spring 启动、真机/DevTools 和 CI 未运行，不能据此验收后端。无生产操作。

工作树 `/workspace/hengyin-c-seat-verification`，分支 `codex/architecture-day-c-seat-verification`。基线 322875cf84cce61161876e12fd37bfb68b568a1e；机械提交 dded84876a53bc8c61106c203c62a21304ab68d5；本行为片以 dded 为父。两片共同交付，不重写旧 322875。

## 调用与归属

|入口/调用者|实际 owner|合同与收益|
|---|---|---|
|subpackageRoam/citynode-code、scene-qr-citynode → POST /api/verify/citynode/issue|CityNodeRedemptionApplication.issueCityNodeCode(long,Long)|签发全闭包归既有 owner，复用原 Roam/密钥和已有 Qrcode bean；无签发事务；原 actor/可见性或 pending→HMAC→本地 PNG 顺序、type/五分钟 TTL/原 Ajax 完整保留。无第三服务。|
|merchant/citynode → POST /citynode/redeem|同应用 redeemCityNodeCode(long,String)|当前 actor/VERIFY→普通含 TTL 验码→claim 锁→当前 MMS row 归属→原 pending/CAS→冻结券配置→发券→审计顺序不变，REQUIRED/default isolation/rollbackFor Exception。原数据结构、文案与 CAS 失败响应不变。|
|同页 UNKNOWN/重建/跨页 → 新 POST /api/verify/citynode/receipt(code)|同应用 cityNodeReceipt(long,String)|当前 actor/VERIFY→原 HMAC 身份（允许旧传输已过期）→同一 claim 锁/缓存 flush fence→当前据点 merchant row 归属→既有精确审计 FOR UPDATE→完整原结果。REQUIRED 可写短事务仅用于锁；无 CAS/发券/插入审计/当前券配置读取。|
|既有四个席位 caller → verification-readback|原共享工具与 RegistrationSeatService|只加同一有效页面请求正在执行时的内存防双发；UNKNOWN 后仍只读原回执；失效旧 context 不阻塞新 context 授权回读。未动席位/报名/退款/费用算法。|

其余五个 Controller 方法体、Java 无参 seat 兼容重载保留；既有六条 HTTP 路径不变，新增上述一条精确读回路径（总 7）。Controller 仍只取 appUid/适配/委托，group 簇不迁移。

## 有触发证据的修复

原审计只有 status=1，缺少 couponGranted/实际冻结 couponId，不能从今天的停发配置或玩家券列表重建首次结果。现在把原 data/message、receiptVersion=1 **追加到同一原审计 after_json**（status 保留），与 CAS/券历史同事务提交；没有另建 receipt/票码系统、没有新增 schema/迁移/依赖。既有唯一原 requestId 仍 citynode-redeem-{poi}-{player}，不受 QR TTL/nonce 变化影响。

读回使用既有 selectByDomainActionRequestForUpdate，不改 B Mapper/reader。锁顺序沿原 permission→claim→据点归属，再查原审计；lockClaimOwner XML 已有 flushCache=true/useCache=false，先清 MyBatis L1 再进行当前锁读。scope/owner/target/relation、保存版本、整数身份/券 ID、严格 Boolean 与原成功文字必须全部一致。缺行、历史 status-only、损坏/不一致 JSON 返回 520 原结果待确认；数据库异常直接传播，不伪装未执行/权限拒绝。新核销仍普通 verify，过期依旧拒绝。

原页面只比较会员 ID，真实 session.setUserRole 只改 revision 时迟到回包会清 UNKNOWN；账号 ABA/离页、相机迟回包同源。复用已有 captureLogin/loginCurrent（真实 revision/token/签发代次），增加原页面 epoch；相机 fulfilled/rejected、写回包、只读回包、清磁盘前均按同口径检查，失效保留原账号原行，不迁移/清他人行。页面 onHide/onUnload 使原上下文失效；未验证微信原生相机/后台事件真机时序。

原 isBusy 同时锁 UNKNOWN，导致同页无法回读。现在区分有效原写仍 in-flight 与 UNKNOWN：前者阻止双发，后者只走 receipt；跨页先查共享磁盘原业务 key。旧磁盘行不含敏感原码，继续沿 owner+poi/player 摘要定位，不改磁盘结构。恢复用重新扫描的同一 signed identity 读原事实，nonce/TTL 不释放身份。只有原规范回执且原 attemptId CAS 清理成功才释放。列表、空结果、403、520、超时、异玩家/异据点、畸形成功均不解锁、不重发。不同玩家的同页核销分别结算，迟到旧页不更新新页 UI。未声称跨设备减少网络请求；服务端原 claim 锁/CAS 继续防重复经济效果。

## 验证与文件

`citynode-receipt-node-results.json`：9 个相关文件独立 Node process，344 tests/pass、0 fail/skip；其中本页从新增 4 条失败（旧版 8 条中 4 pass/4 fail）修至 13/13，包含真实 session role revision、ABA、相机、同页 UNKNOWN、跨页回读、异响应、连续玩家、存储失败。257 条已有 UNKNOWN adapter 与 51 条席位 caller/contract/revision 全保留。

`citynode-receipt-original-cases.json`：55 个既有 Java case/原断言按原顺序全部保留；ApiVerify19、Staff2、RoamRetention21、Equity5、CityApp8。最终分别 19/2/21/6/17，新增 10（签发无事务 1、原结果/授权/类型/异常/TTL/停发结果 8、真实 MyBatis 链 1），均**未执行**。原 MySQL connection/latch/rollback/清理代码不动；RoamRetention 在独占 verifyController helper 之外逐字相等。Equity 既有 suite 计数 5→6，root 如需严格登记应精确更新，不沿用旧源 5 的 receipt。

`citynode-receipt-static-evidence.json`：签发经 actor/Ajax 适配后方法体逐字等价、其他五方法逐字相等、Java 词法括号检查（不是编译）。JS syntax、git diff --check 已过。真实 MyBatis 新场景用实际 claim/CAS/coupon/audit mapper，保留原五个经济/回滚/并发场景，并额外验证签发→核销→券配置改变/传输过期后原结果不变、没有第二次发券、权限撤销后拒读；当前只是已编写，未假称 MySQL 通过。

源码清单：ApiVerifyController、CityNodeRedemptionApplication、merchant/citynode/index.js、verification-readback.js。必要 fixtures/tests：ApiVerifyControllerTest、MerchantStaffRevocationAcceptanceTest、RoamLocationRetentionMySqlIT 的独占 helper、RegistrationEquityMybatisIntegrationTest、CityNodeRedemptionApplicationTest、citynode-redeem-original-request/citynode-redemption-application-source/registration-ticket-application-source/merchant-citynode-access Node 文件。本目录附真实轻量输出；未改 A/G/B 生产/其他 runtime、CI/registry/ledger/UI baseline。技能按已提供 miniprogram-development/Change Safety Protocol 执行；本聊天已有源码授权，不启动 IDE/上传/发布。

## 集成与回退

新增接口为已授权当前据点核销者读终态，不扩大新使用准入；保留当前 scope/owner 与原签名，不接受客户端自报成功。审计 JSON additive 无 DDL；历史缺完整原结果不可回填猜测，仍人工核对。新前端遇旧后端 404 继续 UNKNOWN，不自动 POST；回退后旧后端不能自动读新回执，须保留磁盘 UNKNOWN 与审计，不删除/重签另一业务目标。root 后续统一编译/原 MySQL 并发与新增链路/HTTP 权限、启动及 ledger 重建；没有把 Node/Mock JDBC 当真库证据。
