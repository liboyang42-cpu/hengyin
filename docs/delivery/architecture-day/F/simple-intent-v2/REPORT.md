# F：simple 页持久 AI 意图 v2 调用闭环

结论：本候选实现 simple 页 prepare / execute / 原身份只读恢复、历史选择和明确确认后另起的客户端闭环。专属真实 Page Node 用例 **74/74**，复用共享用例 **101/101**，均 0 failure / 0 skip。它是可审查的客户端候选，**不是后端联合、微信 UI、真实 provider、完整 WP09/WP13 验收通过**。当前独立基线缺新 Controller Mapping 和 recovery GET 权限矩阵登记，共享检查的真实失败保留，不改门禁或 baseline。

## 基线与独占范围

- 初始公开基线：`4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa`。
- 为消费 root 指定的共享 journal，独占工作分支依次快进到公开 c666、最终 **`4195f5437ce81bc178237eaafa95b0c782f938c4`**。交付 commit 的 parent 是 4195；root 只取 **4195→本候选** 的 delta，不能把 root 前置变更重复归于 F。
- 分支 `codex/architecture-day-f-simple-intent-v2`；隔离 worktree `hengyin-f-simple-intent-v2`。开工干净。没有切换其它 worktree、覆盖他人修改或重写冻结 F 架构/HTTP 候选。
- 仅修改 `chengyinhub-xcx/pages/publish/simple/index.{js,wxml,json}`、两个原专属 Node 测试，新增一个 `tests/unit/simple-ai-v2-guards.test.js`，以及本报告目录。
- journal、session-store、request-client、modal、app、后端、CI、registry、SQL、其它页面都不在 delta 中。4195 journal blob 为 `02015aa8f406b7d4e55c69172fd532e4fe77906a`。
- 初始 4cf 与最终前置 4195 的旧 Page blob 均为 `377072bfc7e1fb1f19d01e95fe5aa5cc24b63b2f`，没有把已变更的旧 Page 当同源。

## 先验失败机制与调用链

已完整读取 H `235264eb8ad9d95c6d6151dddf300a0b2879a381` 的 PLAN 与 12 条旧源负例；其旧源负例不是 v2 acceptance。本次另用实际不可变 4cf Page、实际 session-store、当前本页 fixture 复现：身份存储写入抛异常时，旧 `/api/ai/theme/draft` POST 仍发出 1 次，本应 0；断言实际 exit 1。仅本地回调 double，没有调用模型；原始失败输出见 evidence/baseline-negative.txt。原测试中的“UNKNOWN 改词自动换 UUID”“NOT_FOUND 清锁”“storage 只是便利”不能保留为绿签。

生产入口搜索发现 `components/cy/publish-sheet/index.js:65` 的原 `mode + scope` 导航；`app.json` 注册 simple 子页。其它命中为测试、automator、既有 baseline/报告。没有改入口权限或给其它页面迁协议。旧 `p2-motion-visual.js` 仍是未运行的微信 UI 调用者。

调用链：publish-sheet → Page → 原 login-reload / app.getSession().getRevision → journal → app.sendRequest 原 transport → prepare / execute / request / recovery。正文仍用原草稿解析、三节点约束与 POI 确认，最终仅写原账号业务草稿并进入 fabu；本页不直接发布。

## 实现合同

1. **先有可靠身份，后有 POST。** 新动作先用普通 `journal.save` CAS 保存 PREPARE_UNCONFIRMED 的原 UUID prepareKey 并读回，才 prepare；服务端原 td2 RID 保存后，再可靠保存 EXECUTE_UNCONFIRMED，才 execute。execute 正文只有原 requestId。没有显式 Authorization，只 Content-Type，沿原 transport 注入认证。
2. **原输入只在本页不可变 snapshot。** 原 idea/productType 不落 pending/history。丢 prepare 回包只 GET 原 prepareKey；同页显式继续才允许同 key、同 payload prepare。冷恢复无原 payload 时不拼当前输入。网络失败、generic500/404、NOT_FOUND、坏 view、错 RID 均不是 FAILED 或未执行证明。
3. **一个事实归约器。** 严格 10 字段 view；非 200 的匹配原 view 也可记录事实，只有本次成功 prepare 的匹配 PREPARED 可继续首次 execute。自动恢复全部只读。PROCESSING/UNKNOWN 不自动重跑；成功正文过期/不可用保留 SUCCEEDED 与身份，坏草稿不覆盖用户旧 plan/POI，不采用坏/回放的旧额度。
4. **明确确认后另起。** 仅 UNKNOWN 或无 RID 的 PREPARE_UNCONFIRMED 显示专用按钮；PREPARED、PROCESSING 默认仍读原号。实际 `utils/modal.js` 提示原结果可能返回、旧记录保留、另起可能额外占额度。冻结原账号/auth/revision/page/hop/完整 pending 与此次输入；取消、身份变化、重复回调零 journal 写、零新 POST。确认后消费原 `beginConfirmedNewAction`：旧 8 字段归档/读回、active CAS 均成功后才新 key + prepare，无 helper 降级。普通 save 不放宽。
5. **终态/历史不是删除身份。** 原 UUID 先严格迁移为只读 8 字段身份、读回后，才精确删除本 owner 未被别人修改的旧 pending key；不复制旧 raw idea，不 TTL 删除业务草稿。owner recovery 展示全部 5 状态、每页 20 条、更多/返回最新，不逐条读正文。使用 cy-option-sheet 的原 props/select/cancel。ABSENT/终态通过 `selectHistory` 保存已有 server RID，再 GET；未决 active 下其它历史只在页内独立只读 selection，不能覆盖 active 或给所选记录执行许可。
6. **本机归档仍可找回。** `journal.history` 只枚举当前 owner；读取失败不当空。单独本机保留记录入口能以原 RID/原 prepareKey 只读查看，包括未绑定 RID 的旧 prepare。active 异常消失而本机仍有未决归档时不凭空准入新动作；保留只读，不发明 null-active 新动作授权。
7. **数字和原模式不猜。** recovery cursor/nextCursor 只接受正十进制 String，按长度+字典序比较，不 Number parse，拒绝越 Long 上限、乱序、重复、错误 paging/shape。读取 E `c65c7ac90ecddeed9c4e1d95eaee4348ee7c2b58` 的最终 DTO：view 尾部第10 / recovery 第9字段 productType Integer nullable；内部 cursor Long 仍由 root Controller 投影 String。冷恢复按原 productType 展示及保存/进入 fabu；null 保留正文只读，禁用编辑与 POI，阻断进入编辑器。同页原 snapshot 与已知 server mode、metadata 与 view、已应用同 RID 的已知模式若矛盾，保留身份并 fail closed，不能据此另起。
8. **迟到回调隔离。** quota、prepare、execute、read、recovery、sheet、modal、POI、延迟 setData 与导航均绑定原 owner/auth/真实单调 revision/page epoch/hop/完整 active。onHide/onUnload 失效；onShow 同 owner 新 revision 只读恢复原号。prepare.complete 不能清 execute loading，recovery.complete 不能清所选 GET loading，旧 quota 不能覆盖执行后的新额度，旧 POI 不能修改新 plan。A→B→A 即使相同 token/毫秒，也拒绝旧 A 回包。没有修改 transport 或声称 transport 自身已解决 ABA。

## 实际执行与边界

玩家额度仅采用 owner 服务的专用 remaining；新增 gen 已尽而服务投影专用额仍为正的页面准入正控，以及专用额0拒绝负控，不查询 gen、不计算双池 min、不重置计数。gen 状态及当前扣减规则本身仅由 A 后端负责，本 Page double 不证明它们。

Node `v24.19.0`；直接 `node tests/unit/<name>.test.js`，不是把 `node --test` 的文件汇总数冒充 case 数。原始 stdout、逐命令 exit/count/hash 与被测源码 SHA256 见 [EVIDENCE.json](EVIDENCE.json) / [evidence](evidence)。

| 范围 | 实际结果 |
|---|---:|
| simple-ai-v2-guards | 59 PASS / 0 skip |
| simple-ai-request-state（原 6 迁移） | 6 PASS / 0 skip |
| simple-ai（原 9 UI 意义保留） | 9 PASS / 0 skip |
| root 4195 journal / 原 session-store | 17 + 16 PASS / 0 skip |
| 原 UI 状态采用 / malformed-list / simple-preset | 26 + 40 + 2 PASS / 0 skip |
| JS --check、页面 JSON | PASS |
| 原 js-scope-lint | 713 生产 JS PASS，未解析标识符/不存在导出均 0 |
| 本页 U3/U4/U5 | 各 0 新违规；不改 baseline |
| 独占 delta 的 secret-file / gitleaks 8.21.2 | PASS / 0 findings，不新增豁免 |
| 共享 get-auth-matrix-contract | **28 PASS / 1 FAIL**：直接 GET 76 != 冻结75 |
| 共享 ui-integrity | **FAIL 6**：本独立基线无新三条 Mapping，以及同三条的 U1 路径归类连带项；其它规则不通过豁免绕过 |

最后一次完整轻量证据绑定 EVIDENCE 的文件内容；中途新增动态 progress 文案触发旧共享精确文案合同后，已恢复原进度/错误互斥文案，再实际重跑 26 项共享状态合同全过，不改该测试。

**未执行**：微信开发者工具/真机/截图/上传、Java编译/JUnit、SQL/Redis/MySQL、真实 HTTP/provider、后端联合 CI、source gate、compiled jdeps。没有派 CI、新 agent、真实模型调用、VM 后端或数据库。用户明确禁止本次微信操作，故没有执行就近 AGENTS 的截图流程；不能据 Node 推断微信 UI 可用性、服务端权限或一次经济效果。H/F 其它历史 CI 不挪作本候选证据。

## Root 集成接线与回退

- 先合入已审 journal 4195、E c65 的真实类型/服务与 root Controller 原维护 flag；Controller 需确实提供三条 Mapping、nullable productType 与两个 String 游标的真实序列化。Page 不添加新后端 state、终止接口、权限或额度规则。
- root 逐权限审阅后，在原 GET 矩阵追加 `['pages/publish/simple/index.js', "url: '/api/ai/theme/draft/recovery'", 'required', 'author', 'self-account']`，直接 GET 75→76。保留已有 request 行；这里沿用原 author/self-account 分类，实际执行权限/归属以 root Controller 真实测试为准。F 未修改共享矩阵。
- 需 root 对整合后的同一完整 SHA 实际跑后端编译、维护 flag/鉴权、prepare 零模型/零配额、一次 execute、恢复/保留与 SQL/Redis 合同，并运行完整小程序检查。共同通过前不得把本候选描述为可启用/端到端验收完成。
- 秘密扫描中两个 path→SHA256 同行字段被 generic-api-key 误识别；已改为 path / sha256 分行数据格式，并按原配置重扫 0 findings，不添加 allowlist。
- 无 F schema、DDL、依赖或全局配置变动。回退仅客户端候选，但不能恢复已关闭的旧 POST 作执行 fallback，也不能清 journal/history/业务草稿/资金/回执；后端与入口维护状态由 root 独立控制。已发 UNKNOWN 不能靠回退认作 NOT_SENT 或换号自动执行。

## d49 定点补片：sheet event context 的正确状态归属

本片以公开 `d49c12b820419308236010e0710f96d7bf48c4c9` 为parent，只修Page/WXML、原option-sheet及对应Node fixture/10项定点控制。真实 `scripts/setdata-dead-field-lint.js` 的两项B2已先复现，修后该实际U4、U2、U3均通过，baseline未改；此前ui-integrity局部检查不能替代此门。

完整owner/auth/revision/pageEpoch/hop/原pending仍在Page私有`_sheetContext`。Page每次打开先卸载旧sheet，再挂新实例；组件可选`bindEventContext`只绑定一次私有、不落data的UI事件标识，select/cancel回传原实例标识。旧排队事件、已detached实例、旧render callback无法冒充新opening。认证/原pending仍由原`_current`逐次核对；没有把安全身份删去或放进隐藏文本。组件旧宿主不调用此API时，props及原select/cancel payload和重复选择行为保持。

当前实际轻量定点：原59项guard和新增10项真实Page+组件控制均PASS、0skip，4份JS语法通过。按用户最新“先源码批次、统一回归”裁决，不再重复原175/整仓全套；统一回归、ledger维护、Java/CI和微信仍由root安排。本片不改登录/权限、journal、action-ledger、CI、额度政策或任何baseline。
