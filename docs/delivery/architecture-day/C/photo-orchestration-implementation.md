# C：Photo 原动作四阶段编排候选

结论：代码已接入 E 的既有 IntentService，拆掉 act 的跨模型业务事务，UNKNOWN 保留原命令，已有首次结果可在本地 CAS/回执失败后恢复。当前仅 Node/静态验证已执行；Java 编译、单元和真实 MySQL 均待 root 受控 CI，不能视为闭环验收通过。

## 基线与独占边界

- 公共仓库 `liboyang42-cpu/hengyin`，工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 本候选精确 parent `a480e4083618120332539d0ac49c7e51e9db6839`。9a/83 冻结；d5、7929、a480 均保留，不重写历史。
- 共享依赖：root adapter `bb4a1852018238b9280602714f4358863d7da9e2`，E service 493/1162，原 key 恢复 `ffc2e7159846892b530edb17bbb5e411471da8f4`，原 ID current-read `91e78c448f69b2e3e3407acc111ff460c4a37ebb`。已 read-only 核对 91e 的实际重载源码。root 联合树应已有 C83 指纹与 G051 查询源，按 delta 集成，不用 C 整树覆盖共享依赖。
- 未修改 E 的 service/DTO/row/mapper/schema、root Gateway/Vision、root 独占的两份 Photo 业务测试及 quota 夹具。root 的 orderedMembers、ClubLead IT 清理、ThemeRuntime/PredictLateBet fingerprint 列必须保留。

## 实际调用链与失败机制

主代码唯一 act 调用者为 ApiAdvancedPlayController.action；小程序对应 _submitPlayKitAction→_submitKitPhoto→advanced-game.action→/api/play/advanced/action。已搜索 system/admin 主代码所有 .act 调用；接口参数不变。原路径是外层 @Transactional act → submitPhotoCheck → 模型 → CAS/路线事实/PlayAttemptLog。后半段失败会回滚本地效果与回执，不能撤回模型调用，重试原 key 会再次调模型。

本候选仍复用 PlayAttemptLog attemptType=8 的终态回执。Photo 表只通过 E.markApplied 关联原 receiptId，不另存一份玩法 responseJson，不新建通用 AOP。

## 实现合同

1. **入口分流**：act 无外层事务；普通动作由必需的 PlatformTransactionManager/TransactionTemplate 保持原事务。Photo 若上游已经持有业务事务，在任何模型/HEAD/数据库准备动作前拒绝，不使用 NOT_SUPPORTED 暂停锁后继续 HTTP，也无 null-manager 直跑 fallback。
2. **准备短事务**：admitPhoto/lockPhotoSession 先读取授权；按现有 activity→registration 次序只锁当前资格行，然后 session→intent，避免与原 CurrentPlayRegistration 的锁序反转。锁定行本身不代表资格通过，终态回执仍优先于当前版本/当前可用资格判定。session 及成员授权回读和 PlayAttemptLog 回读使用 flushCache/useCache + FOR UPDATE current read；Photo 短事务明确 readOnly=false，并用 READ_COMMITTED 避免先前 RR snapshot。原终态先读，原 E intent 按 member/session/scopedKey/hash 查回，只有确无原意图或仍 PREPARED 才校验当前资格/版本/epoch/config/照片输入。prepare 不增加 tries、积分、奖励或路线事实。
3. **原意图与指纹**：非 Photo 继续 C83 advanced-act:v1；Photo 使用 advanced-photo:v2，在同一个 commandFingerprint helper 内增加原 session 冻结的 runtimeAuthorityEpoch、originalGameSessionId/originalRouteSessionId 和 canonical config，仍绑定 actor/session/scope/original expected/raw key/action/payload。计算一次并原样传 E，不由 Photo 服务猜 JSON。当前外部 epoch 不参与重放指纹；它只参与新的本地提交资格。旧 NULL/不同 hash 继续拒绝，不回填猜测指纹。
4. **事务外执行**：E.executeOnce 只以原 intentId/hash 调用。现有 r5 照片 HEAD 也移到事务外，以受管媒体解析后的处理地址校验；已有结果重试不再 HEAD。IN_FLIGHT/UNKNOWN/缺首次结果均返回待确认，不能合成 degraded 抢先提交 fallback。E 实际已保存的 RESULT_READY（包括第一次真实 degraded）继续使用原配置 fallback；providerState UNKNOWN 不因此变成 RECEIVED，也不重领 nonce。
5. **终态短事务**：commitPhoto 重锁原 session，重新授权，先读终态，再核对原 hash/key/期望/资格/epoch。只用 E current-read 的持久结果调用纯本地 submitPhotoCheck，不调用模型。CAS、原路线事实、PlayAttemptLog 与 markApplied 在同一事务。首次公开回包也从原 receipt JSON 读回，避免 Long/Integer 漂移；仅首次提交后铸卡的内部 marker 单独带出。receipt 必须真实存在并有 ID/同 hash/scope；markApplied 失败回滚本地事务。外部已有结果而本地冲突/DB 失败，返回 520 intentUnknown，保留原结果和命令。
6. **明确未执行**：仅在 session 锁内、两份 current read 都证明原终态/原 E intent 不存在，且资格校验拒绝尚未 prepare 时，可返回 data.intentNotExecuted=true。E DB 错误直接抛出，不能当成不存在；提交结果不确定不带该标志。控制器不泄露内部 provider/DB 原因，不在 UNKNOWN 后铸卡。
7. **提交后铸卡**：原 finishPendingCardMint 仍在 act 返回并提交后执行；本候选不声称解决铸卡本身的外部持久意图问题。

## 同批 F/H 锁读缓存补充

按 root 补发授权，ClubLeadMapper.selectByActivityForUpdate/lockClubOwner 均补 useCache=false + flushCache=true。人工展开 TopicRouteSessionMapper.selectAccessibleByIdForUpdate 的 sessionColumns include，确认 SQL 实际以 FOR UPDATE 结束，也补两 flag。不改全局 MyBatis，不触碰 E/G mapper。新增 PlayLockReadCacheContractTest 的 6 个实际 MappedStatement 参数场景覆盖上述三处及本批 Advanced 的三处锁读；每项含只关闭 L2 而不清 L1 必红的 mutant。Java 未运行，沿用原 ClubLead 并发 IT/H cached-empty 回归由 root 再验；不把 XML 解析当作真实并发通过。

## 当前小程序接线

- Photo 上传 mapResult 取实际返回的 assetId，生成 canonical media-asset 引用；缺 ID/不安全数字直接拒绝动作，没有 URL fallback。共享 upload-client 和其他上传调用不改。
- action 冻结调用者 payload 后才计算原 key。Photo 发送前同步持久化并回读验证原命令；磁盘记录只含账号/活动/主题/节点/原 session、key、expected 与 media-asset ID，不含 token、签名 URL、照片字节或答案。
- pending 按主体/会话/动作 ID 分键，节点索引仅用于找回；未确认记录不得被后续新输入覆盖。520/500/非明确未执行的冲突保留原命令，个人 Photo 也原 key 重放，不用实体版本增长推断成功。
- start 发现原 Photo pending 时只 GET 原 session，保持 unknown，用户核对后才 POST 原命令；不自动 start/切换另一个 runtime。存储损坏拒绝推进。
- 回调冻结账号、登录代次、页面 epoch、节点和 session。切 B 不重放 A，A→B→A 的旧回包不改变新登录 UI。迟到成功只清捕获的原账号/原动作键，不清 B 或 A 后来的命令。已验证终态的存储清理失败不掩盖原成功。

## 文件与测试

完整清单在 evidence/photo-orchestration/candidate-files.json。生产修改限定 AdvancedRuntime/ApiAdvancedController、原 runtime/attempt mapper 的锁读入口、当前小程序对应 Photo caller/pending；其他 Java 文件主要是显式注入单元/真实存储夹具所需的事务管理器。

- 新增 AdvancedPhotoOrchestrationTest **16 条已编写，未运行**：短事务边界、CAS/回执失败保留结果、IN_FLIGHT/UNKNOWN、首次 degraded fallback、原 key 恢复、版本变化、终态重放、异输入/expected、旧 authority、上游事务、撤销读取授权、缺身份、r5 HEAD 事务外且不重发。
- ApiAdvancedPlayControllerTest 新增 UNKNOWN 不铸卡/不泄露原因测试，未运行。
- ThemeRuntimeMySqlIT 新增 CAS/RECEIPT/CONCURRENT **3 个参数化真实 MySQL 场景，未运行**。使用真实 E 意图/回执/玩法/路线 SQL；媒体、同意、quota 和 provider 是明确 doubles。CAS/log 失败核对原 session/路线零漂移、原 RESULT_READY/nonce 保留，恢复后同 key 一个终态。并发场景在 provider 阻塞期间要求第二调用及时读到 IN_FLIGHT/UNKNOWN 且零 fallback/本地效果，随后原 nonce/receipt 关联不变。不能把这些未跑用例写成 MySQL 通过。
- 普通高级动作原 raw-new 单元夹具显式装 transaction marker；这不是 DB 回滚证据。真实 SQL 夹具装实际 DataSourceTransactionManager；PredictLateBet 的原手工 SqlSession 使用同一真实 Connection 的 SingleConnectionDataSource，不能换成 no-op manager。
- root 独占 PlayKitPhotoCheckRuntimeTest/ObjectCardMintHookTest 仍注入旧 photoCheckVisionService 字段；该生产字段已删除。未擅改 root 文件，精确适配建议在 root-owned-legacy-fixture.patch。它是旧业务单元用的 intent double + 原真实 Vision supplier，不能当作 E 原 nonce/MySQL 闭环证据；由 root 合并时保留其最新 quota 夹具。

实际 Node：9 文件 125/125 pass、0 fail/skip，见 node-summary.json 与逐文件 TAP；同一组 59 条组件用例在原 HEAD 组件源码下实际 50 pass/9 fail，当前 59/59。基线通过 Module 编译钩子载入原公共源码，未替换工作树或改测试条件；归档 TAP 仅去除行尾空格。其余 Java 仅做括号和 XML 解析，不能证明编译。

## 集成、迁移与回退

- C 不新增/执行 DDL。启动依赖 C83 request_fingerprint 列、E canonical photo_check_intent migration 及新重载。真实存储夹具仅在受控测试 schema 应用 E migration；生产授权/预检/迁移/回退仍由 root 单一安排。
- 先合并共享依赖和 C delta，保留 root fp 列/清理修复，应用 root 独占夹具适配，再跑受控 Java unit/MySQL 并重建联合树 action ledger。a480 附的台账 patch 对旧 C 树，仅供审阅；Photo 源变更后必须再次按联合树重建。
- 回退应用前暂停 Photo 入口；保留原意图/nonce/配额身份/结果/PlayAttemptLog，不删除表、清记录或释放业务唯一 key。不能回退到会在事务内随机调用模型的旧 Photo 路径。
- 范围限制：本批为 Photo 增加跨进程持久恢复；其他 Advanced 动作仍沿用既有内存回读，只补齐原命令的主体/登录代次校验，不能据此宣称所有高级玩法跨进程恢复完成。QA/ALBUM 的既有只读 HEAD 仍在其原事务中，未纳入 Photo 模型编排迁移。
- 未验证：受控编译/JUnit/真实 MySQL/Redis、全量小程序套件、最终联合控件台账、微信模拟器/真机。未发真实模型请求、未访问生产、未部署/上传微信。
