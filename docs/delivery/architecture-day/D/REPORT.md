# D 官方广播与投递恢复候选交付

结论：已编写广播专属正确性补丁、12 个新状态机单测、4 个 Controller 单测和 7 个 MySQL 集成用例，并完成经追加授权的官方广播结果文案修复。Java 编译/JUnit/MySQL 均未运行，不能作为已验收或可直接部署版本。轻量静态检查与相关 Node 测试共 21/21、零跳过。候选迁移没有执行。微信 UNKNOWN 查证及受控 Java/MySQL 验证仍是集成阻塞。

## 基线与隔离

- 唯一来源：`https://github.com/liboyang42-cpu/hengyin.git`。
- 开工实际 HEAD 与指定参考一致：`2ec68899099a1e250df0949a8383bf85b5d3ffe0`，原分支 `work`，原目录 `/workspace/hengyin`，工作树干净。
- D 独立工作树：`/workspace/architecture-day-d`；分支：`codex/architecture-day-d-broadcast`。未切换原 checkout，未回退或覆盖他人修改。
- 未创建额外 Agent/会话；未访问 Mac 路径、私有仓库或私有历史；未连接生产、未获取凭据、未部署、未上传微信、未调用真实微信收费/付款动作。
- 用户追加批准“仅为 CI 的隔离候选 commit/push，未验证不得正式集成/合并部署”的明确例外。本候选可发布到现有 codex 分支供协调者 fetch；此例外不代表 Java/MySQL 已通过，不允许正式集成、合并或部署。基线及源码身份用逐文件摘要和 candidate tree 固定。
- 候选身份：`CANDIDATE.sha256` 列出生产代码、测试及迁移提案的内容摘要。完整可集成差异输出到 `/workspace/architecture-day-d.patch`，外置校验文件 `/workspace/architecture-day-d.patch.sha256`。补丁以本基线为父，包含新增文件。

## 独占文件

| 文件（仓库相对路径） | 修改目的 |
| --- | --- |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/OfficialBroadcastServiceImpl.java` | 冻结通道意图、事务投递、恢复及真实统计 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/OfficialBroadcastLogMapper.java` | 广播专属锁、意图、状态、恢复扫描及统计 |
| `chengyinhub-system/src/main/resources/mapper/business/OfficialBroadcastLogMapper.xml` | 对应 SQL；不删日志、不释放业务身份 |
| `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/OfficialBroadcastLog.java` | 四态、冻结文案、本地消息回执及确认时间 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/OfficialBroadcastDeliveryTest.java` | 12 个新增状态机单测；内存桩不代表数据库证据 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/OfficialBroadcastDeliveryMySqlIT.java` | 7 个批准环境真 MySQL/真 IM Mapper 集成用例；微信为故障注入桩 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/OfficialEventContractTest.java` | 仅适配广播测试/装配；原 17 个测试入口全部保留，非广播业务断言未改 |
| `chengyinhub-admin/src/test/resources/it-schema/official-broadcast-delivery-it-schema.sql` | 公共基线两个广播表的原始 DDL，仅供随机隔离测试库 |
| `chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql` | 候选增量迁移；经 F 集成维护移至规范目录并登记精确依赖，仍待授权执行 |
| `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/BusinessOfficialEventController.java` | 仅两个广播发布结果文案改为“通知已提交”，原鉴权和返回 ID 不变 |
| `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/business/OfficialBroadcastSubmissionControllerTest.java` | 4 个 Controller 结果边界测试源码，未运行 |
| `chengyinhub-ui/src/views/business/officialevent/index.vue` | 仅广播提交回调和广播状态展示文案 |
| `chengyinhub-ui/tests/unit/official-broadcast-submission-contract.test.js` | 动态执行真实组件方法，验证提交/送达分离及拒绝边界 |
| `docs/delivery/architecture-day/D/{REPORT.md,verification.txt,CANDIDATE.sha256}` | 交付报告、实际输出与候选摘要 |

没有修改 NotifyServiceImpl、NotifyChannel、微信发送实现/公共接口、IImService/ImServiceImpl、OfficialBroadcastMapper、其他业务 outbox、schema 基线、根 pom 或 CI。CoopDepositRefundService/RoamBadgeDeliveryService 的持久意图后唤醒保持原样。

## 调用者与失败机制

已在生产 Java 全树搜索 `officialBroadcastService.`、`notifyOfficialBroadcast(`、`OfficialBroadcastLogMapper`、`markReached(`、`runDueScheduled`，并搜索小程序和后台消费路径。开工按实际源码定位，未沿用旧行号。

调用链：

- `BusinessOfficialEventController.broadcast`、`ApiOfficialEventController.broadcast` → 直接 publish，或 `OfficialEventV2ServiceImpl.publishBroadcastForOperator` → publish。
- `OfficialEventTask.run` → `runDueScheduled` → dispatch。
- 两个 Controller 的 stats、V2 stats → reachStats；小程序 `pages/activity/official-mine/index.js` 读取 reached/clicked，后台 `src/api/business/officialevent.js` / `src/views/business/officialevent/index.vue` 发布广播。
- 点击回流：`ApiOfficialEventController` → markClicked。
- 日志 Mapper 的生产调用者仅广播 Service；没有其他生产调用者需要迁移旧 insertIgnore/markReached 方法。
- `INotifyService.notifyOfficialBroadcast` 的其他调用者：`CommittedBusinessNotice`、`ChapterMerchantNodeServiceImpl`、`MerchantOfferServiceImpl`；仍经 `NotifyServiceImpl` fanout，本任务不改它们。
- `IWxSubscribeMsgService.notifyOfficialBroadcast` 原经 `WxSubscribeChannel` 调用；本候选的广播订阅分支直接调用这个现有微信专用接口，其他调用链未改。

根因与最小反例：

1. 原广播忽略 notify 的 false，仍执行 markReached。更深层的 NotifyService 是“所有通道任一成功”，站内信成功也可能被误记为 subscribe 成功。最小反例：订阅未配置或微信失败，而公共站内通道成功。
2. 原 insert-ignore 占位后发送失败，日志 reached=0；下次占位冲突直接跳过。最小反例：第一次站内写入异常，第二次服务恢复，但唯一行使消息永不恢复。
3. 原站内消息提交与 markReached 不原子，成功后回执写失败可能留下“已发但 reached=0”，不能直接删除或重置旧行重投。
4. 原每轮把 reachedCount 写为“本轮新成功数”，重复任务会降为零；reachStats 又累计所有通道行，与站内人数混用。循环结束无条件 ST_SENT 掩盖失败、频控和 UNKNOWN。

## 实现合同

确定业务身份继续使用 `uk_bc_member_channel(broadcast_id, member_id, channel)`，广播所属 event_id 仍从广播主记录关联。不新增全站 outbox，不靠 TTL 或租约释放唯一身份，不删失败行。

首次 dispatch 在锁住广播主行的独立事务中去重圈人，并一次性写入所有成员/所选通道 PENDING 意图及冻结文案；全部写入提交前无发送。重试读取原意图，不重新圈新成员或换文案。定时广播仍在到期首次 dispatch 时圈人；空受众在同事务置完成，后续任务不扩大受众。

| 通道/现态 | 动作及结果 | 恢复合同 |
| --- | --- | --- |
| inapp PENDING / FAILED | 锁原日志，调用现有 REQUIRED sendSystem；消息写入和日志 SUCCESS/message_id 在同一事务提交 | 异常/无有效消息 ID 整体回滚；重新锁查原日志后，仅 PENDING 可标为本地确定失败；SUCCESS 不覆盖 |
| inapp SUCCESS / UNKNOWN | 无发送 | SUCCESS 返回原事实；旧 UNKNOWN 没有证明不重发 |
| subscribe PENDING / 模板缺失 FAILED | 预检同一个 `wx.subscribe.tplOfficialEvent` 配置；缺失记 FAILED/TEMPLATE_MISSING，无外部调用 | 配置就绪后允许处理原行 |
| subscribe 可发送 | 先锁行并提交 UNKNOWN/OUTCOME_UNCONFIRMED，再在事务外调用现有微信专用接口 | 只有返回 true 才将原 UNKNOWN 行改 SUCCESS；false/异常/进程死亡/回执保存失败均保留原 UNKNOWN |
| subscribe SUCCESS / UNKNOWN | 无发送 | 不因时间过去或任务重启自动重投，不假设授权未消耗 |

本地事务提交响应丢失时，恢复先锁查持久行；已经 SUCCESS 的行不降级、不重发。MySQL 回滚、锁及提交边界必须由集成用例实测，当前只是候选实现与待执行断言。

频控查询异常继续禁止发送；触发频控的意图保留待处理。无效/重复通道在广播写入前拒绝；既有活动归属闸不变。publish 不接受传入的 reachedCount 为事实，初始化为零；主记录写入及生成 ID 校验在 REQUIRED 事务内，唤醒失败不会把已提交发布伪装成拒绝，保留待恢复事实。

若 publish 参与调用者事务，唤醒注册于 afterCommit；外层回滚不产生投递。dispatch 使用 NOT_SUPPORTED 挂起外层上下文，内部 DB 步骤 REQUIRES_NEW，微信动作不继承业务事务。没有全禁 afterCommit。立即广播的恢复扫描也包含 scheduled_time=NULL，成功回执后主汇总更新失败的任务能恢复。

主 reachedCount 及 stats.reached 是已确认站内持久消息人数；clicked 是这些站内成功行的点击人数。`channelReached` 是所有通道 SUCCESS 回执行数，同一成员双通道可计两行；`byChannel` 返回 channel / deliveryState / receiptCount 分组。微信 SUCCESS 仅表示接口明确受理，不代表用户已读。FAILED/UNKNOWN 不计成功。

主状态沿用现有枚举：只有全部意图 SUCCESS（或冻结受众为空）才 ST_SENT；部分成功、FAILED、UNKNOWN、频控仍 ST_PENDING。不把循环结束当送达。

## 实际验证与未验证

实际命令和完整输出见 `verification.txt`：

- `git diff --check` 通过。
- Python XML/绑定检查：13 个接口方法和 XML statement 一致；14 个模型映射属性存在 setter；锁查询禁用缓存；两个测试表 DDL 与公共基线逐字一致；原 17 个契约测试方法名全部保留。
- `node chengyinhub-xcx/tests/unit/official-broadcast-stats.test.js`：4 通过，0 失败，0 跳过。
- `node chengyinhub-xcx/tests/unit/official-pages-state-contract.test.js`：7 通过，0 失败，0 跳过。
- 新管理端提交用例修复前：3 例中 2 个预期失败（“已发送”及“待发”误表述），1 通过；修复后 `node chengyinhub-ui/tests/unit/official-broadcast-submission-contract.test.js`：3 通过、0 失败、0 跳过。
- `node chengyinhub-ui/tests/unit/official-enum-text-contract.test.js`：7 通过、0 失败、0 跳过，未知枚举展示和其余官方活动语义保持。
- 曾用 node --test 启动两文件时只得到文件级结果，因此未把它当 11 个用例证据；后续直接执行获得上述实际逐用例输出。

明确未跑：Java 编译/package；17 个原契约 JUnit、12 个新状态机单测、4 个 Controller 单测、7 个新 MySQL IT；MySQL 迁移/回滚/并发；真实进程 kill/restart；真实微信模板/授权/网络投递。未启动云 VM Java 后端、数据库或 runner，未用 H2/Mock 冒充 MySQL。服务实例重建用例不等同真实进程重启，微信桩不等同微信验收。

新增单测覆盖：缺模板、false、异常、成功后回执保存失败、重复任务/服务重建、站内失败而微信成功及反向部分成功、无消息 ID、冻结圈人文案、统计口径、通道拒绝零发送、旧 UNKNOWN 不重投、已提交发布的唤醒失败仍返回待恢复。原广播五个用例继续覆盖跨角色去重、分角色文案、重复任务与频控。

MySQL IT 覆盖：真实 IM 写入/回执一起回滚并恢复、模拟微信受理但返回丢失、两个 worker 并发、原 UNKNOWN 经过长时间仍不重发、缺模板仅恢复订阅、回执成功后主汇总中断恢复、外层 rollback/afterCommit。类名 *IT 默认 Maven test 不收，必须点名执行；缺显式环境设置时硬失败，不用 assume/skip。

集成人在批准 VPS 或现有 GitHub-hosted backend 环境安排：

```sh
mvn -B -DskipTests package
CHENGYIN_BROADCAST_MYSQL_IT=1 mvn -B -pl chengyinhub-admin -am test \
  -Dtest=OfficialEventContractTest,OfficialBroadcastDeliveryTest,OfficialBroadcastSubmissionControllerTest,OfficialBroadcastDeliveryMySqlIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

需要已批准隔离 MySQL 的显式 `CHENGYIN_MYBATIS_IT_PORT/USER/PASSWORD`；不从 VPS/生产复制凭据。该 IT 仅连接 localhost、核实 MySQL 8、在随机 `cy_broadcast_d_<uuid>` 库建测试表并在结尾只删除自身创建的库。不要在生产运行。`failIfNoSpecifiedTests=false` 仅允许 reactor 上游模块没有这些指定类；必须核对 admin 新鲜 XML 的预期 17/12/4/7、失败/错误/跳过都为 0，否则不能验收。当前共享 ci/backend-test.sh 的 IT 点名清单没有本类；由集成人单一加入，D 未修改 CI。

## 迁移影响、预检与回退限制

候选 SQL 已由 F 集成维护移至 chengyinhub-admin/sql/ 第一层，并登记官方广播表迁移的精确依赖；内容字节不变，未执行 DDL，进入库存不等于授权部署或落库。生产唯一身份索引、业务资金表与旧审计记录不变；新增六列：delivery_state、delivery_title、delivery_sub、failure_code、message_id、reached_time。新字段默认 UNKNOWN，避免旧写入被当成安全可重投。

实施前必须停所有旧/新广播 worker 与发布入口，在受控环境审评并执行只读预检：

```sql
SELECT VERSION(), DATABASE();
SHOW CREATE TABLE official_broadcast;
SHOW CREATE TABLE official_broadcast_log;
SELECT column_name FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='official_broadcast_log'
 AND column_name IN ('delivery_state','delivery_title','delivery_sub','failure_code','message_id','reached_time');
SELECT broadcast_id,member_id,channel,COUNT(*) AS n FROM official_broadcast_log
 GROUP BY broadcast_id,member_id,channel HAVING COUNT(*)>1;
SELECT channel,reached,COUNT(*) FROM official_broadcast_log GROUP BY channel,reached;
SELECT COUNT(*) FROM official_broadcast_log WHERE channel IS NULL OR channel NOT IN ('inapp','subscribe');
SELECT COUNT(*) FROM official_broadcast_log l LEFT JOIN official_broadcast b ON b.id=l.broadcast_id WHERE b.id IS NULL;
```

确认 InnoDB、保留 uk_bc_member_channel、没有重复/异常通道/孤儿行、新六列全部不存在；异常数据交决策阻塞，不删除日志绕过。迁移是一次性提案，列已存在或部分迁移时禁止盲重跑；先核对实际结构和回填阶段。评估表规模、DDL 锁/磁盘与维护窗口，使用已批准备份/恢复流程，不把备份搬入公共仓库。

旧 inapp reached=1 保留为 SUCCESS，但没有伪造 message_id/确认时间；旧 inapp reached=0 可能发过，转 UNKNOWN。旧 subscribe 不论 reached 值均无法证明微信成功，转 UNKNOWN/LEGACY_UNVERIFIED，原 reached/clicked/create_time 原样保留作为历史证据。读统计要求 SUCCESS，避免把旧错误标志继续计入。主表派生人数回算；旧已发但含 UNKNOWN 的主状态变为待确认，草稿/已撤不改。历史数据是否另有独立微信证明，必须人工核实后决定，不能猜。

部署顺序：隔离 MySQL 演练及测试通过 → 集成人审评候选迁移 → 经授权窗口停广播 → 已批准路径执行 DDL/回填及只读后验 → 启动新版本。D 未授权或执行这些步骤。

回退不提供 drop-column/删除日志脚本：新增字段和业务身份必须保留；旧代码会忽略状态并无条件标成功，因此禁止新旧 worker 混跑。若代码必须回退，先停广播发布/调度并保持停用，再由集成人制定前向修复/证据核对；不得让旧 worker 接管重发 UNKNOWN、重置状态、清空回执或经济记录。

## 仍阻塞的验收事项

1. **Java/MySQL 实测缺失**：未有受控执行回执，候选不能宣称通过编译、事务原子性、并发或实际恢复验收；需集成人按上述清单统一执行并提供新鲜输出。
2. **成功文案已修复，Java 断言待执行**：追加授权后，仅修改 `BusinessOfficialEventController.broadcast` 的 V2/非 V2 发布结果为“通知已提交”，不附带可能被当成全渠道送达的站内人数；返回值仍为原广播 ID。管理端回调固定确认提交，不把响应 msg 当作送达证明；主状态 1 为“投递未全部确认”、2 为“投递已确认”。ApiOfficialEventController 原本已用“通知已提交”，保持不动；鉴权及其他 Controller 未改。4 个新 Java 用例验证结果/拒绝边界，当前未执行；Node 3/3 和枚举回归 7/7 已执行。
3. **微信合同能力**：现有专用接口 boolean 折叠明确拒收与结果未知，也不接收确定业务请求号、不返回提供方回执、不暴露原号查证能力。D 保留原日志 ID、禁止 UNKNOWN 自动重投；自动查证/人工核销 UNKNOWN 的审计流程及所需公共接口由集成人决策，不猜微信授权是否已消耗。不能把长期 UNKNOWN 当失败后重新发送。
4. **迁移与历史事实**：旧订阅 reached=1 不可信；保守回填会降低历史统计并把部分“已发”改为待确认。这是纠正可证明事实的提案，需集成人审评/维护窗口授权，未执行。
5. **CI 接入**：专属 IT 未加入共享点名清单，需集成人在现有批准路径加上明确 gate 与 7/7、零跳过检查。未创建 runner、未改 CI、未扩大历史 baseline。


## 环境恢复与候选传递补充

此前 exec_command 返回 `No usable execution environment is available`，但状态 API 报告 running；随后原绑定重新可读。没有调用 restart/recreate，没有新建、清空或重置 workspace，没有新增 Agent。诊断列表 history_status=not_found、records=[]，无证据判断根因。当前 HEAD/分支/暂存内容均与故障前一致。

改动前重新导出故障前完整暂存补丁，103847 字节，SHA256 `c72b57b69352c593de3154dd842881353fafb4bfc44d6353b3899eb7474cb773`；故障前暂存树 `fc7d94ae6375fc9bfdc716cff3b32c3352711b9d`，9 个源码/迁移文件摘要逐项复核一致。保全文件 `/workspace/architecture-day-d-preserved-c72b57b69352.patch` 未覆盖旧补丁。

Shell Git 访问 github.com 时返回 `Failed to connect to proxy port 8080 ... Could not connect to server`。没有移除代理或绕过网络规则；GitHub 连接器可读取同一公共基线，候选发布使用连接器的 Git tree/commit/ref 操作，保持同一 parent 和与本地一致的完整源码树。文案追加修改后重新生成逐文件摘要、完整补丁和 candidate tree；最终 commit/tree 由交付消息给出，避免把自身 commit SHA 写入自身内容形成循环。
