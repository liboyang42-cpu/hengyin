# H：裸 POST 95项真库反例与 W19 独立复审

结论：**H-ADE31-01/P1 已由真实批准CI复现无依据返1000积分**，不再仅是源码预测。该失败停止在积分断言，不能宣称后续库存/流水已量测。入口政策仍待用户选择，H未改生产。W19 在以下准确源码和29项真库范围内未发现新的可触发 P1/P2；支持“原退出+IM意图同事务、原ID恢复、WX不随IM重发”这一窄结论，不代表全部通知/HTTP/调度/金融验收。

## 身份、所有权和取证

公共仓库仅 `liboyang42-cpu/hengyin`，基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。H开始 HEAD `0f4b9a235bda0a530e821a70d0181d21e6e2cd61`、工作树 `/workspace/hengyin-h-review`、分支 `codex/architecture-day-h-review`，开始干净。仅新增本H目录四个文档/证据文件；未改A/D生产、未新增Agent、未派CI、未启动JVM/数据库、未调用外部模型或WX。

H从GitHub公开固定source取源码，从原artifact下载ZIP，用Python解析原JUnit XML、receipt并核验source、run、attempt、用例数及cleanup。证据JSON只保留必要的哈希/名称/结果，不复制XML里的环境参数。原XML/ZIP/receipt SHA256见两个 `*-xml-evidence.json`，源码身份见 `source-evidence.json`。

## H-ADE31-01：真实确认积分增发，库存/流水仍未验证

- 独立evidence source：`2a2f37e8a7e7c357be390b586d889ec7535a1937`，run `40040570934`，artifact `12538832468`。不是生产部署或正式集成source。
- 原Funds类真实95项：**94pass、1failure、0error/skip，CLEAN**，MySQL残留schema为空。H将用例名与66e9原94项逐项比较，原94个精确名称全部通过；唯一新增失败为 `bareAdminPostCannotCreateUnbackedPointsOrInventoryToRelease`。
- 原XML SHA256 `8cb98b184d1bfeb03f06214dc2f285de7952f7c95aa14e6d3c45059eea75cefd`。失败精确位置 `RegistrationFundsMybatisIntegrationTest.java:407`，`expected37 actual1037`，文案明确“bare admin-created row must not invent 1000 refundable points”。
- H读取该source的测试文件，新增方法完整正文与原H `unbacked-post.review.patch` **逐字一致**，不是为了制造失败换了业务断言。真实建单先占一席，真实Controller handler.add插入伪造行，再经原会员取消。原测试在37积分断言处失败，后面的会员7积分、remaining_inventory及points_detail计数断言均未执行；不把源码推断写成这些值的实际读回。
- 原链路/位置仍见此前公开H报告：后台add整实体→insertCmsRegistration无扣分/预占→原会员cancel按pointUsed返分。此处是handler/Service/Mapper受控真库证据，显式登录fixture不等于真实HTTP、Spring Security方法拦截或RBAC授权测试；不声称匿名可触达。

裁决：P1继续阻断。root已交A，入口关闭/正式代报名/审计补录的合法政策由用户选择，H不猜“改0即可安全”，不清理账本、不补造扣款证明。未来修复需原新增95例变绿并保留原94，不以忽略/删除该失败或只改断言消除风险。

## W19 候选和准确范围

生产程序集成source `08b3feaea460533112da40991254b7777bf19bd3`，parent `b453b6e1b41e59b365255a05c318cd305fd1c054`，tree `94041440d08fb16fab97057c2ad9a46b6a8fcf24`。实际29项source `05f4aa69e61752dcafb0bec73ca9ae9b2ff7a7f5`，parent正是08b，tree `ebf16ae3db2baae0e52e76f30c9cda9ab2e6c7de`；该子提交仅workflow/registry/harness自测/协调证据，无生产变动。

H取得05f未截断完整tree，与前轮已核对的2721个b453生产Java/XML比较，唯一生产差异为 `MerchantOfferServiceImpl.java`，blob `caf0ac05ac8ef129711df70ea5d6eff1a30ffab3`；共享IM消费者/接口/XML/调度器/通道均沿用精确原blob。H将新类移除四字段/两个helper、还原原owner通知调用后，@Service起整个类正文与b453逐字一致；没有暗改原退出算法/锁/CAS/玩家supplier。新增两个测试文件也逐blob核对，不凭作者报告签源码身份。

真实caller：`ApiCoopController.offerWithdraw`（POST `/offer/withdraw`）→既有 `MerchantAccessService.require(...COOP_MANAGE)`取得ownerId→`withdrawOwnChapterOffer`。该Service已有 `@Transactional(rollbackFor=Exception.class)`。实际锁序还包括 `supplyReviewService.lockBeforeChapter` 的上游审核/主题锁，不能只照旧chapter→topic注释判断。源码锁调用本次未改变。

## 原事务 → 原ID → 本地终态

1. 原归属/主题/生命周期检查后，对offer作current锁读并重验归属；已退出且有原原因提前返回，历史已退出缺意图不会被本次补造。仅 `withdrawOwned=1` 的winner继续原申请退役/canonical失效并登记通知。
2. `registerOwnerWithdrawalNotice:498–536` 强制真实事务及同步活跃。事件永久为 `chapter-supply-withdraw-owner:<offerId>`；非随机事件号，不以正文TTL释放。先调用真实代理 `findIntentId`，其XML为 `FOR UPDATE/useCache=false/flushCache=true`，不沿用RR旧空值。
3. 首次新意图在原业务事务中enqueue，固定recipient、MSG_TEXT、`title+换行+body`、null extra。B接口为REQUIRED加入同事务；insert后current锁读校验原hash。无正ID或写失败抛出，使供给退出/申请退役/需暂停报名同事务回滚。存在原ID则只复用，不用当前owner/title/body覆盖原payload，也不再排WX。
4. afterCommit闭包仅捕获原intent ID，调用现有异步 `dispatchInNewTransaction`；任务在REQUIRES_NEW事务内进 `deliverSystemNotification(originalIntentId)`。队列拒绝或进程丢唤醒不会删原PENDING。
5. B消费者锁原intent：DELIVERED直接读原messageId；PENDING才用 `notification_<intentId>` 做确定消息身份，消息/会话/未读和DELIVERED回执同事务写。恢复任务R7固定本轮maxID、分页、逐项事务，失败推进游标不饿死后页；不去读live供给/主题重建通知。没有外层AOP在消息提交后另存业务回执的拆分。

helper新增注入对象均有当前实际Spring候选类型：ImServiceImpl同时实现两个接口，WxSubscribeChannel为@Component，`ThreadPoolConfig`提供同名ScheduledExecutorService。没有缺依赖时报告成功的fallback；但本次未跑完整Spring装配，不把手工代理真库IT当全应用启动证明。

## WX 一次尝试边界

仅本次新建意图的winner构造WX原payload，afterCommit再通过既有executor单次schedule。执行时若仍有数据库事务就拒绝发；正常走 `WxSubscribeChannel→WxSubscribeMsgServiceImpl`，非 `NotifyService.fanout`，不再顺带生成另一份IM。通道false/异常只记录未确认；R7及原ID IM回放都没有WX调用。已有意图/重复退出/历史恢复不会再排WX；未见新可达自动重发路径。

**此处不是持久化WX回执或exactly-once供应商承诺**：队列丢失/进程退出可导致WX根本没发，UNKNOWN没有供应商查询闭环；D报告明确保留该缺口。源码/测试的“0次或1次”以正常调度及原winner为边界，未证明供应商真实接收/用户订阅授权/内部网络行为。不能把IM的DELIVERED显示成所有通道送达。原玩家supplier仍是旧afterCommit fanout，受众冻结/漏通知不在W19闭合范围。

## 同一候选29项实际证据矩阵

run `40038698400`，source均为05f4，同attempt1：

| 类/范围 | 运行 | H裁决 |
| --- | --- | --- |
| MerchantOfferOwnerNoticeIntentMySqlIT | 21pass，0fail/error/skip，CLEAN | 原意图/原ID/本地收据窄链有真实MySQL证据 |
| FreeExploreNodeSupplyMySqlIT | 原8pass，0fail/error/skip，CLEAN | 原供给断言在补依赖夹具后保留通过 |
| 新11unit及旧10＋18unit/Controller | 本轮未下载其原XML | 不计入29，不声称H已验证运行 |
| HTTP真实token/完整审核/金融/Quartz | 未验证 | 不外推 |
| 真实WX/provider、真实进程kill/网络ACK丢失 | 未执行 | 模拟不能替代真实证据 |

21项H逐例读源码：真实退出/申请XML及IM意图/消息/未读/receipt同DataSource事务代理；覆盖意图写后抛错回滚、队列拒绝/丢唤醒恢复、原退出ACK模拟、投递提交后ACK模拟、双worker并发、原payload冻结、重复退出/历史原意图、赢家CAS失败及越权零变化、需暂停报名时失败全回滚、消息/未读/receipt写后失败回滚、同事件变payload冲突、WX false单次且恢复不再调。XML21个方法名与源码核对；两个类Maven退出码0，残留schema均为空。

边界：topic/chapter由显式JDBC fixture适配，审核/canonical及WX是明确mock，原玩家affected集合为空；不是全部供给审核/真实收件人权限/金融链路。ACK用代理提交后本地抛异常模拟；丢进程用清内存队列模拟；不声称实际kill或网络断连。原8类的通知依赖桩也不证明owner通知持久化，后者由专属21项承接。

## 裁决和交付

W19限定合同：源码和同候选29项证据相符，未发现新可触发P1/P2；未覆盖项继续UNKNOWN，不签全系统上线。裸POST积分P1已有真实红例；共同资金fence、B支付P1、typedquota外层两P2也不因W19绿关闭。H本轮未修改或重派任何owner实现/测试；只保存独立取证补记，无新依赖/schema/迁移/DDL。回退通知接线不得删除原PENDING/DELIVERED消息身份、原资金/库存/审计记录，且不能让旧owner fanout与新消费者为同一事件双轨发送。
