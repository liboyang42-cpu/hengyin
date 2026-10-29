# D / W19 商家退出供给 → 主办方站内通知

结论：生产接线与专属测试源码已完成。仅原退出 winner 在原事务登记主办方 IM 原意图，提交后按原 ID 唤醒；旧 owner 整包 fanout 已移除。微信用既有通道在事务外尝试一次，未确认/异常不随 IM 恢复自动重发。原玩家通知与退出/权限/经济算法保持不变。本候选仅供受控 CI，Java 编译、JUnit/MySQL 尚未执行，不能据此正式集成、合并或部署。

## 基线、隔离和取回

- 公共仓库仅 `https://github.com/liboyang42-cpu/hengyin`；参考基线 `2ec68899099a1e250df0949a8383bf85b5d3ffe0`。
- 本轮唯一父 source：`00fc58c50febc3366f78af600c8f12636cd13de5`，父 tree：`b0ae8ba46eca8bcc3c2e61abb5a6c4e2536e101c`。
- 工作树 `/workspace/architecture-day-d-w19-owner`，独立分支 `codex/architecture-day-d-w19-owner`；本地 HEAD 仍是公共 2ec 基线，已在本工作树索引复原父 tree。**所有本轮 diff/提交/清单以父 source/tree 为准，不以本地 HEAD 为准。**
- 原生 Git fetch 实际失败：`Failed to connect to proxy port 8080`。通过已连接 GitHub API 读取同一公共来源，核对 1,167 个目录对象和 339 个缺失公开 blob。一个 33,062,054 字节的小程序 UI 审计文件连接器返回 `Transport closed`，正文未取回；以 skip-worktree 排除，父索引保留其原 SHA `f1cd9054d2127aff867b91c18c53b61e9624cad2`。候选继承该文件原身份，不修改、删除或重建它；未调整代理或导入私有历史。
- e717 / db5 / 928 / SQL75 / b626 及所有原工作区保持不动；未创建新 Agent、未修改其他人的分支或改动。用户此前明确批准的“仅为 CI 隔离候选 commit/push”例外适用，不能把未验证提交当正式集成许可。
- 远端候选 commit/tree 在交付消息给出；完整逐文件 SHA256 见 `CANDIDATE.sha256`，源范围、原测试方法及 B/E/G 指纹见 `evidence.json`。

## 文件所有权与调用链

唯一生产文件 `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantOfferServiceImpl.java`：只替换 `withdrawOwnChapterOffer` 原主办方通知三行，增加必要 import、四个依赖注入和两个专属 helper。没有修改其他方法或玩家通知 supplier。

生产 caller 搜索为 `ApiCoopController.offerWithdraw`（`POST /offer/withdraw`），经既有 `MerchantAccessService.require(...COOP_MANAGE)` 得到商家 ownerId，再调用 `IMerchantOfferService.withdrawOwnChapterOffer`。通知 recipient 仍为锁内主题的原 `topic.memberId`，不是把调用商家当成收件人。Controller、鉴权和公共接口均未修改。

原事务为 `@Transactional(rollbackFor = Exception.class)`。实际锁序包含 `supplyReviewService.lockBeforeChapter` 内审核任务/主题锁，随后章节、主题、供给与原申请/审核写入；不能只照旧注释推断 chapter→topic。本候选完整保留原调用顺序，在原业务写入之后才取得通知意图键锁。B 恢复消费者锁 intent→IM 会话/消息/成员，不反向锁业务供给或主题。没有在数据库事务里调用微信。

## 原失败机制与最小反例

原 owner 路径：退出成功 → `sendAfterCommit` → 内存闭包 → 10ms executor → `notifyOfficialBroadcast`。没有原通知意图；队列拒绝仅告警，进程丢唤醒无法恢复。再次退出因 `status=0` 和原退出原因提前返回，不会重登通知。fanout 的 any-success boolean 还会使“WX 成功、IM 失败”表现为 helper 成功。

受控最小反例：令 executor.schedule 拒绝，执行一次真实退出事务，检查退出已提交而 IM=0；重复退出仍无原意图/消息。第二反例为 WX=true、IM=false，验证 any-success 不能代替 IM 回执。本轮没有运行 Java 故障注入，反例由新增测试源码承接，不宣称运行复现完成。

先编写的 7 项 Python 源码合同在不可变父源码上为 1 pass / 6 errors（原 helper/原意图登记不存在），修补后 7/7 通过。`before-static.txt` 与 `verification.txt` 为实际静态输出；不是 Java 红绿或 MySQL 验证。

## 实现合同与字段变化

1. 保留原 `withdrawOwned(...)=1` winner 闸。重复/历史退出原有提前返回不变，既不重写退出原因，也不补造历史原意图。
2. helper 要求原实际事务与同步活跃。事件键永久固定为 `chapter-supply-withdraw-owner:<offerId>`；不生成随机 ID、不以正文 TTL 释放身份。
3. 先 `findIntentId`。已有原意图仅复用 ID，不根据 live recipient/title/body 重建，也不再次安排微信。缺意图且本次是原退出 winner，验证原收件人/正文后在原事务 `enqueue`。
4. IM 格式逐字沿用原通道：`MSG_TEXT`、`title + "\n" + body`、`extraJson=null`。原主办方、标题、正文首次固定。意图保存失败或无有效回执抛出，使原退出及申请退役等同事务回滚；无危险 null 依赖放行。
5. afterCommit 使用既有 `CommittedBusinessNotice.dispatchInNewTransaction` 仅调用 `deliverSystemNotification(originalIntentId)`。消息/未读/终态收据原子提交与重复回读由未改的 B 消费者保证；丢队列或投递失败留下原 PENDING，复用 R7。
6. 原 owner 整包 `notifyOfficialBroadcast` 不再调用，避免旧 IM 与新意图双发。首次新意图的 WX payload 原 recipient/title/sub 在原事务内构造，仅在提交后通过既有 `scheduledExecutorService` 排队，再调用既有 `WxSubscribeChannel.send`。不创建线程池、不改通道合同。错误的 inline/带事务执行会拒绝调用微信；false/异常记录未确认，保留原 offer 身份人工核实，不自动重试。
7. 本轮没有外部 WX 持久回执或查询能力，不编造查证接口，不声称修复 WX 漏唤醒、UNKNOWN 或授权恢复。IM 恢复任务只恢复 IM。玩家路径仍是原合同，其丢通知/受众冻结缺口没有在本轮冒充修复。

没有数据库字段/索引/schema 变化；生产只新增四个依赖字段：`systemNotificationIntents`、`imService`、`ownerNoticeWxChannel`、`ownerNoticeExecutor`。复用表中的 `event_key/recipient/msg_type/content/extra_json/request_hash` 固定原事实；`status/message_id/delivered_at` 仍由 B 按原合同更新。

## 返回结果及文案合同

原方法返回 boolean 和 Controller 的 `enrollmentPaused` 逻辑逐字保持；它描述原业务的暂停报名结果，**不是通知是否送达**。原 `已退出供给` 表示原退出事实已提交，不能解读为主办方或全部通道已送达。

若管理端以后单独展示通知 `已提交`，准确含义只能是“退出事实与主办方 IM 原意图已提交、等待投递”；只有 B `DELIVERED/message_id` 可证明本地消息已写入。WX true 不能替代 IM 成功，PENDING/UNKNOWN 不能显示“全送达”。本候选未扩权改 Controller 或添加未经回读的送达字段。

## 测试范围与实际执行

| 类 | 数量 | 源码/验证范围 | D 实际执行 |
| --- | ---: | --- | --- |
| `MerchantChapterOfferWithdrawTest` | 原10 | 所有方法与业务/玩家断言保留；仅 owner 旧 fanout verify 换为原意图 verify，补通知依赖和手工同步 fixture | 未执行 |
| `FreeExploreNodeSupplyMySqlIT` | 原8 | 原用例体逐字不变，仅补手工装配的新通知桩及 import；原真实供给 mapper 断言保留 | 未执行 |
| `ApiCoopOfferWithdrawTest` / `ApiCoopOfferWithdrawRouteTest` | 原10＋8 | 两个文件与父源码逐字相同 | 未执行 |
| `MerchantOfferOwnerNoticeIntentTest` | 新11 | 原事务前提、原格式/身份、已有 ID 冻结、非法回执/内容与冲突拒绝、WX 事务外单次/异常/拒绝/inline 防线 | 未执行 |
| `MerchantOfferOwnerNoticeIntentMySqlIT` | 新21 | 原意图和消费真实 MySQL/MyBatis，原供给退出/申请退役使用真实 XML；回滚、漏唤醒、拒绝、历史、重放、ACK、双 worker、冻结和冲突 | 未执行 |

新 MySQL IT 的主题/章节是 JDBC fixture adapter；审核协调和 canonical 为 mock，不是完整供给审核/金融链路验收。原消息/未读/回执真实同事务；模拟 ACK 丢失是在真实代理事务返回后抛异常，模拟进程恢复为重建 task/丢内存队列，均不冒充真实网络断连或进程 kill。每例唯一隔离 schema，cleanup 回读应为零；本轮没有执行该建库/清理代码。所有 WX 通道均为 mock，真实调用为零。

本轮实际执行只有 Python7、scope/字节/指纹/旧断言核对、diff whitespace 和有界凭据模式扫描；详见 `verification.txt`。未运行 Maven/javac/JUnit/MySQL/Redis/后端/CI/生产 DDL。没有新增依赖、迁移、共享 registry 或 CI 改动。

## 集成、预检、回退与剩余项

root 使用现有 scratch runner 登记新 `MerchantOfferOwnerNoticeIntentMySqlIT`：标准源码 gate `CHENGYIN_FUNDS_MYSQL_IT=1`，storage=mysql，预期 21 tests、0 failure/error/skip。PORT/USER/PASSWORD 必须由已批准受控 runner 明确提供。D 不 dispatch、不改 scanner。原 10/8/10＋8 和新11单测均需受控编译执行；失败不得靠删除用例、skip 或 baseline 扩大解决。

父00fc源码已包含 G 精确 R7/R1 target；本轮没有重复修改。自动恢复就绪仍需 root/G 对照其受控 CI、实际注册与 paused 状态，不能用直接调用 task 的 IT 替代 Quartz 验收。D 未访问运行库或启用任务。

无新 DDL，部署前仅沿用已有 B 意图/IM 表的 InnoDB、唯一键、事务同源及 receipt 一致性预检；建议查询见 `integration-notes.md`，未执行。没有历史通知证据不得批量回填。

回退保留全部意图、消息、回执和审计。直接恢复旧 owner fanout 会与已有 pending 消费重复，必须先停该 producer 新流量并按原 ID 清点/处理，再决定回退；不能删行释放唯一身份。WX UNKNOWN 继续人工核实，不能因回退或租约超时重发。
