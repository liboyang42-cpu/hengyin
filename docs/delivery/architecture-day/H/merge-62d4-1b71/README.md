# H：冻结公共三方小程序冲突解析（SOURCE）

- donor 单父：`62d4e0bbf47efa2f73aa7e604f1dfe092518d923`。
- 治理输入：`1b71ae555a3141da948fa4e36ea68cb48a7b9b39`。
- 三方基线：`51f05499c1523648a328f57bcd7927f6d9546003`。
- 仅指定 13 个小程序路径及本静态交付目录。没有删除记录，没有写其他 owner 的生产、策略、CI 或生成物。
- 这是供 root 装入真实双父 merge 的 donor，**不是已验证的双父集成候选**。all_complete=false。

## 解析结果

`owned.review.patch` 为 master → 最终解析后像的完整代码增量；`production.review.patch` 单列三个指定生产文件触点，`tests.review.patch` 单列测试。三个 patch 不可重复应用；完整 patch 已包含后两者。所有 13 个完整文件也在 donor 对应路径中，未变化文件与 master 相同。POSTIMAGE.sha256 / PREIMAGE-POSTIMAGE.json 列全部输入和后像，PATCH.sha256 列补丁字节/摘要。

- advanced-game：组合 master 的安全整数身份、原请求脱敏记录、GET /advanced/receipt、历史视图不倒退/卡片递交，与治理的原照片 media-asset 记录、payload 拷贝、登录代次及原题隔离。普通动作仍存脱敏原号；照片仍走原 advanced-photo-pending 记录，避免两个不同持久槽成为恢复入口。冷恢复照片先 GET 原 session，核对仍只 GET 原号回执；不重放 POST、不以版本或未查到当未执行。显式 intentNotExecuted 才能走原拒绝分支。
- play 页：保留 master 已扩展的 answer/arrive/checkin/photo/puzzleReveal/sensor/preference 上下文、原局/authorityEpoch、GET 原号恢复；合入治理的席位选择/核销恢复、章节 command 身份、原照片上传媒体引用和同题/账号代次检查。未触 master 原呈现、暂停、商家游戏资源与其他非冲突功能。
- play-route-state：重叠区域 master 已覆盖治理的持久索引/固定原版本，并增加安全 ID 与 expectedSessionId；全部保留 master 后像。clear 不释放持久 UNKNOWN。
- architecture-day-c-intent add/add：master 包含治理原场景，且新增 photo/puzzleReveal/sensor/preference UNKNOWN 与重启只 GET 场景，保留其超集。
- 其余 fixture：保留两侧相同业务场景及 master 新场景。补稳定 routeActionId/原 expectedVersion，保留 master 的真实内存存储、按用例重载模块和显式原局上下文绑定。
- v4-scan-cancel：保留 master 用例显式触发上下文回包及治理 intentTerminal/索引要求，不叠加自动和手动双回包，所有扫码取消/定位不替代核验/终态后不补写断言保留。

## 原断言的协议合并

治理旧照片恢复控制曾要求同号 POST replay；master 新合同要求只读 GET 回执，两者不能同时执行。对应控制保留原 key/版本/输入相等、原持久记录、只发首次写、UNKNOWN 不解锁、重启原局、账号 ABA 和迟到只清原键等断言，把“相等的再次 POST 请求体”改为“内存/存储保留原请求体 + GET 精确 sessionId/idempotencyKey”。回执未查到或普通错误仍 UNKNOWN；不能把纯读回查改成业务重发。源中“回查明确未执行后 actionfail”的业务否认场景由真正显式 intentNotExecuted 的提交拒绝覆盖，不伪造 GET 404 是未执行证据。保留 master 原卡片/晚到成功/原号恢复全部场景，并新增独立 409 明确未执行场景；原 answer 预绑定意图的 UNKNOWN 场景另保留，与 master 真实 store/context 场景并存。

## root 集成接缝（只读，未改 caller）

donor 仅运输本组 13 文件，不能作为 master 上独立可运行全包。真实 merge 必须保留治理新增原模块：
- `pages/play/utils/advanced-photo-pending.js`：source blob `123cba18b4f20730feabc14bda73cc367b77d381`。
- `utils/registration-seat.js`：source blob `485d4d457eed7426745e9d00cd6fec7ec14de1f6`。
- 原 `utils/merchant-verification.js`、`play-route-state` 与会话生命周期由实际合并源码供给，不在 donor 中覆盖。

只读核对 ApiAdvancedPlayController 两侧：master 有 GET /receipt → AdvancedActionReceiptReader 且保 CheckReceiptProjection；治理另有 AiProviderOutcomeUnknownException → typed 520。此 H 包保持 root 固定公开 projection/原 GET receipt 合同，不修改该 Java 文件。root 对其负责 owner 的解析应保留这两侧接缝，不能退回治理旧无 receipt 入口或拿未投影原 payload 下发。公共客户端 caller 的其余生成台账/静态行号由 root 最终统一处理；本包没有重生成。

## 验证边界

仅执行源码读取、冻结三方文本 merge、Git 差分、补丁 apply-check 和摘要；完整 patch 对精确 master 文件前像的 `git apply --check` 成功。没有执行项目 parse/import、Node/Python 测试、编译、Java、DB、Docker、HTTP、微信或 CI。所有执行验收 **NOT_RUN**，包括 13 文件组合、真实账号/页面/持久存储/后台 HTTP；不继承任一旧 SHA 的通过结论。尚需 root 在全源齐备的真实双父候选上统一扫描与同源验证。没有新增权限、依赖、费用、支持类型、服务端规则或发布授权。
