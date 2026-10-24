# H：小程序 Theme Draft v2 最小接线方案与独立基线反例

结论：**Page 尚未实现 v2，不能启用或沿用旧 pending 测试预期验收。** 最小方案是 owner 隔离的持久身份 journal + 原 prepareKey 恢复 + 服务端原 requestId execute/read + 页/会话/当次操作守卫；不新建通用平台。当前旧源的存储失效继续发送、UNKNOWN 改词换号、NOT_FOUND 清锁及迟到回调均已用本地 Node 反例触发。root 持有所有实现；H 只交方案、证据和基线专属反例。

## 身份与审查范围

- 公共参考基线 2ec68899099a1e250df0949a8383bf85b5d3ffe0。
- 实际读取当前源 **864e708e2f38130e21780485e462f8dc147bb76f**，tree 4f3bfd77f199992c84cdf7d538d4554f5a712e7f；该提交仅改真库 registry。用户提及的短 e683 在公共 API 不可解析，本报告以已给定且可核实的 current864e 为准，不声称做了 e683→864e 的差异证明。
- 合同 **ee96ffe45bab6126136e1c5582be53601c4f6fc3** 的 E/THEME-DURABILITY-02/CONTRACT.md，blob a85511ea3898e4eaa9c1817af185470461f8061f。合同只是协议候选，不能当 864e 已有 v2 服务。
- H 工作目录 /workspace/hengyin-h-review，分支 codex/architecture-day-h-review，开工 HEAD 1156c19528b95abf2d42257ff82991eece5e94fd，clean；没有修改 Page、共享工具、owner 测试或后端。
- 读取 Page、两个原测试、request-client、session-store、session-manager、login-reload、app.js、WXML及复用候选。逐 blob/source 见 SOURCE.json。
- 本轮遵从用户禁止微信交互，不执行本地 AGENTS 的模拟器截图流程；只做获准源码/Node 轻量检查。没有 UI、账号登录、上传、真实网络、Java/DB或 CI 派发。

## 当前代码的具体机制

| 位置（864e） | 可触发行为 | 新接线要求 |
|---|---|---|
| simple/index.js:117–156 | pending.unknown 且 idea 改变即可新 UUID、旧 POST；非三个 pending code 的响应先清身份再验证草稿 | v2 不再调用旧 POST；PROCESSING/UNKNOWN 不可换号，单凭 4xx/5xx 或报错文字不收尾 |
| :178–187 | NOT_FOUND 清锁；SUCCEEDED 即清锁，然后才尝试解析可能不存在的 result | 旧号 miss/新 intent unavailable 均不等于未执行；已知成功与正文可用性分离 |
| :198–218 | 先改内存再写动态当前账号 key，storage 异常吞掉；读取异常也当没有 pending；长期保存 idea，无 deadline | 身份先写并读回核对，再允许发送；读失败/坏记录须阻断；identity-only journal |
| :139–156,:171–195 | POST success/fail/complete 无 owner/epoch/页面/原 pending 校验；GET 只有对象引用校验，fail/complete 无校验 | 所有回调和所有异步边界统一 current()；complete 也不能解别的 hop/操作的 busy |
| :62–95 | ownerChanged 只在少数生命周期比较当前 member；onUnload 仅置标志，业务回调不读；无 onHide 失效 | 页生命周期 epoch；账号回到 A 也必须失效旧 A 回调 |
| request-client.js:110–127,151–175 | 比较捕获 owner/token；A→B→A 回到相同值可通过；自己的静默刷新会原参数重发一次 | 复用通道不等于已有单调登录代次；Page 独立守卫、原身份重放，绝不依赖 abort/timeout 证明未发送 |
| request-client.js:75–88；Page 的显式 Authorization | 401 后 state token 更新，但 buildHeader 不覆盖 param 中的旧 Authorization，原参数重试会继续旧头 | Page 只给 Content-Type，认证交既有通道注入；不要假设不存在的 disableRelogin 参数 |
| app.js:169–185,291–304；session-store.js:81–115,143–177 | _featureFlagsEpoch 是特征拉取序号，部分登录/清空路径会增加；store 无公开单调身份 revision | 不能把它无条件当覆盖所有身份写入的 session generation |

迟到 A success 在 B 当前身份时，真实 Page 会使用 aiRequestStorageKey() 的 B key 清掉 B pending 并渲染 A 草稿。此为本地回调隔离实证，**不是 HTTP 越权、真实账号数据泄漏或后端二次扣额实证**。A→B→A 用相同 token 的反例也实际触发；真实 request-client 的注入测试同样会投递旧 success。

## 最小状态、持久化及复用

只在本页/本页专用纯 helper 持有逻辑；不引入全局 intent 平台，不直接搬旧发布 helper。

**身份 journal** 建议独立 v2 namespace，key 固定到捕获的原 member，例如 ai_theme_draft_v2:m<id>:active。一个同步写入的完整对象包含：

`{version:2, memberId, prepareKey, requestId:null|td2_..., phase, createdAt, knownState?, reasonCode?}`

不保存 token、角色/配额 nonce、原 idea/prompt/结果正文，也不把业务身份设成 24h TTL。执行时权限以服务端为准；journal 的 member 只是路由/隔离，不是授权。原输入只作为本次不可变内存 snapshot（idea/productType/existingNodeNames，保留 null/数组顺序语义）；改输入不得修改该 snapshot 或原 prepareKey。服务端不接收客户端 role/pool/hash/nonce。

可复用现有 app.sendRequest/login-reload/UUID 生成思路、_applyAiDraft 的完整性校验与三节点上限、replayed 不采用旧 quota、现有 WXML 互斥进度和禁止本页发布防线。advanced-photo-pending.js 的“捕获原 keys、写后读回、先保留身份”的原则可借鉴，但其 Photo command 结构不直接套入本页。

**不要复用 publish-intent.begin 的原语义**：它会在 payload 变化时换 key，storage 失败仍返回可用新 key，并把原 payload JSON 留存。其 saveStage 的写后读回只是参考模式，不能因存在 helper 就宣称 v2 已可靠。

存储每次都要区分 ABSENT / VALID / ERROR_OR_CORRUPT。只在明确 ABSENT 时可创建首身份；已有异常/损坏行不得删除或按新动作继续。写后 get 并按完整预期字段比较，静默 no-op 也算失败。每次修改/删除都核对捕获原 member/key/prepareKey/requestId，不能按回调时当前账号查 key。删除失败也不能阻止继续识别原终态；保留旧 journal 只会再次读原事实。

### 最小状态表

| 本地阶段/服务端事实 | 唯一允许的下一步 | 禁止行为 |
|---|---|---|
| 无 pending、明确新起草 | 生成 prepareKey；可靠持久化；POST prepare 原 snapshot | storage 失败仍发；先发后存 |
| PREPARE_UNCONFIRMED，尚无 serverId | GET request?prepareKey=原key；同页仍有原 snapshot 时可重发同 key/同 payload 的 prepare | 新 UUID；用现输入覆盖；execute 无号 |
| PREPARED，取得 serverId | 先可靠保存原 prepareKey+serverId，再允许 POST execute {requestId:原id} | id 未持久化就执行；把 idea/role/pool 混进 execute |
| PROCESSING / UNKNOWN | 只 GET 原 id；提示原结果待确认；允许以后读回 | 改 idea、额度变化、超时、取消、租约到期就换号或自动 execute |
| SUCCEEDED + resultAvailable=true | 校验原身份、result 完整；解析 view.result（它才是旧 AiThemeDraftResp）；保留已知终态 | 把 view 当旧 data.draft；先清身份再校验 |
| SUCCEEDED + RESULT_EXPIRED | 保留成功事实、原id；说明原正文已到期；仅新的明确用户动作可新 prepare | 降格 NOT_FOUND/UNKNOWN；自动再生成 |
| SUCCEEDED + RESULT_UNAVAILABLE | 保留成功事实与原id，只回读恢复；现有草稿不被清空 | 假称到期；把 DB 成功改失败；自动新 prepare |
| FAILED，匹配原 view 的持久终态 | 记录原终态/原因；明确新动作才新 prepare | 单凭 code=500/配置失败 msg 就当已知终态清锁 |
| 旧 UUID / NOT_FOUND / INTENT_UNAVAILABLE / STATE_UNAVAILABLE / 错误或不匹配 view | 保留对应原身份，保守只读/错误状态 | legacy rid 自动升级成 v2；伪造 td2_ 或重新 prepare 补号 |

新 prepare 的 200 也可能是原 PROCESSING/UNKNOWN/SUCCEEDED/FAILED 的重放，必须走同一 view 归约器；只有其明确 PREPARED 才有首次 execute 路径。不能把 code200 本身当“允许执行”。首次原 prepare 成功可在同一显式动作链继续 execute；跨重开恢复则以新守卫读到的 PREPARED 为据、用户“继续原起草”操作执行，禁止页面加载自行执行 UNKNOWN。

对已有 requestId，任何响应都须严格相等；无 id 的 prepareKey 查询只在捕获原 key 的当前响应中接受 td2_ +32hex，并可靠保存，之后不准更换。错误码可能在非200 HTTP 回调中出现，也可能是 HTTP200/AjaxResult 非200；success、successStatusAbnormal、fail 要汇入同一保守处理，网络 fail 不能臆造 FAILED。

首次 client 对 idea 的 trim 可以保留现有输入习惯，但 snapshot 后不得再次规范化/排序/去重来“重试”；服务端合同的 hash 不 trim idea/null 不折叠，客户端也不替代其 hash 校验。remainingQuota 只是展示，不能认领成功/允许换号；恢复读取不因显示0次而被拦。

## 页、登录代次和回调守卫

最小 current() 需要捕获以下五类条件并全部成立：

1. owner 非空且原 member 与当前 member 相等；
2. **单调 session revision** 与捕获值相等，且当前 authorization 与捕获值相等；
3. 本页 pageEpoch 相等，未 hidden/unloaded/detached；
4. 当前稳定 pending token/prepareKey 相同；已有 serverId 不变；
5. 当前 operation/hop seq 相同，且 journal 中仍是原身份。pending 的 phase 更新不能通过重新 clone 对象意外使自己失效。

当前 _featureFlagsEpoch 可作为额外保守失效信号，却不是已存在的完整登录代次 API。建议 root 在**已有 session-store** 内维护并只读暴露一个单调 revision，由 app 委托：成功 applySnapshot（即使同 member/token 的刷新）、setAuthorization、setUserID、身份角色变化、clearSession 等身份边界都会使旧工作失效；清空中途 storage 异常也必须失效旧回调。不要通过新全局平台实现，也不要把特征 flags 的网络读取序号改名就当已经满足。所有实际身份写入 caller 要搜索并补相应组合测试；本轮 H 不编辑这些共享文件。

onLoad 新 pageEpoch，onHide/onUnload 立即失效旧 callback；onShow/重新登录后以新代次恢复**原身份读回**。RequestTask.abort 可用于减少无用回调，但不改 durable 状态、不补偿、不证明未发送。登录刷新使旧回调失效时保留 journal，下一次原id回读即可恢复，不需要换 prepareKey。

prepare→execute→quota 可能连续启动子请求：每个 hop 分别有 seq，父 prepare.complete 不能把 execute 的 loading 清掉。所有 success/fail/complete、quota 回包、存储前后、延迟 setData callback 和导航动作都检查 current()。旧 complete 不能解除新任务 busy；旧 fail 不能把后继 pending 改 UNKNOWN。切账号/刷新时不能删原账号 journal，A→B→A 也不能接纳旧 A success。

401 的通道重试仍带相同 prepareKey/同 serverId，后端 durable claim 才保证一次模型效果；Page 不自行增加 transport retry。Page 不传旧 Authorization 固定头，自己的回调守卫也不能绕过“有原身份才能执行”。这是接口接线前提，不能用 Node 模型冒充后端 CAS 证明。

## 两个不能由 Page 独自补齐的恢复边界

**B1：prepareKey 尚未查证且原输入已不在内存。** 原 prepare 回包丢失后，持久 prepareKey 能找回已经提交的行，故正常恢复不需要存 idea。若跨重开后 GET 仍是 PREPARE_UNCONFIRMED，合同既不证明原 prepare 绝不会迟到，也不返回原输入。Page 不能重新拼现 idea、自动换 key 或伪造 serverId。

本轮安全最小行为是保留原 key、允许继续查询；同页保留原 snapshot 时才同 key/同 payload 重放。若产品要求“跨重开、原输入丢失、永久查无 prepare”仍可立即恢复新起草，需要 root 明确额外协议：服务端对原 prepareKey 做可持久化的终止/拒绝晚到 prepare 的 tombstone，或提供允许验证并恢复原 payload 的短期机制。不能擅自加取消端点/状态、永久明文 idea 或猜“没查到就是没送达”。这是待 root 收口的可恢复性缺口，不是要求放宽 UNKNOWN。

**B2：整个持久介质不可用/被外部清空。** 首次可靠 journal 保存失败则零 prepare/execute 请求；已有 key 的 serverId 更新失败则零 execute，恢复后凭原 key 查询。若在已执行后整个设备持久身份被系统清空且进程也结束，客户端无法从没有的数据恢复唯一 id；本合同没有 owner 下列举原未决请求的入口，不能承诺解决这种冷恢复。需要 server-side owner recovery/index 能力才能提供此保证。无该能力就保持功能停用/明确恢复阻断，不能以仅内存锁伪称跨重开可靠。

这些边界不授权改资金规则或新增平台；root 可先交安全 read-only/阻断行为，待具体协议决策后再开放相应路径。旧 UUID 的保守 GET 不是保留旧客户端执行兼容。若需清除旧 pending 的长期 idea，应先可靠保存其原 rid 的 legacy 只读身份，再移除该精确旧字段/记录；不能以升级为由删除原身份或其他资金/审计。用户明确进入编辑器保存的业务草稿不是 pending 原正文，不能顺手按服务端24h TTL删除。

## 必须回归的最小矩阵

| 类别 | 点名场景与不变量 |
|---|---|
| 准备身份 | 1 写异常；2 no-op/读回不符；3 读异常/损坏；均零 HTTP 写；4 双击只一个 key；5 两页原 scope 复用未决 key；无 anon fallback |
| prepare | 6 丢 success/complete、超时、断网、401 各只保留原 key；7 同 key/同 snapshot 重放；8 改 idea/mode/null/数组顺序不得改原 payload；9 prepareKey 恢复 serverId，10 保存id失败零 execute |
| 路由 | 11 prepare 200 原 PROCESSING/UNKNOWN/SUCCEEDED 不误执行；12 execute 只含原 server id；13 disabled 不退回旧 POST；14 无号/旧UUID/伪造新号不执行 |
| 未知 | 15 execute 丢回包后只 GET 原id；16 PROCESSING/UNKNOWN 改灵感/额度变动/超时不换号；17 NOT_FOUND、404、DB故障、unknown view/mismatched id 不清锁；18 read的 quota=0 仍可读 |
| 正文事实 | 19 SUCCEEDED 可用 result 正确解 view.result；20 24h EXPIRED 保留成功与身份，不自动生成；21 到期前丢正文/Redis故障 RESULT_UNAVAILABLE 不假称过期；22 malformed body 保留原事实/现有草稿 |
| 终态 | 23 匹配 view 的 FAILED 才记录原终态；24 一般500/403/426/401/non200 HTTP 不猜 provider outcome；25 新显式动作与原重试明确区分，不能覆盖未决身份 |
| 回调隔离 | 26 A→B；27 A→B→A 原token相同；28 同member刷新/token变化；29 role变化；30 onHide/onUnload/重开；success/fail/complete、quota、setData后导航均零旧副作用 |
| 请求链 | 31 prepare.complete 晚于 execute 开始；32 旧read/旧complete晚于新hop；33 journal phase更新仍能接受正确当前回调；34 401只用新认证头重发同身份，外部换号不得重绑定 |
| 恢复/隐私 | 35 页面重开原 prepareKey、serverId恢复；36 不持久 idea/prompt/result/token；37 identity不按24h释放；38 B1/B2保持原身份阻断/按具体批准协议恢复；39 清理失败保留可回读原记录 |
| 保留正控 | 原三节点上限、有效节点、回放不采用旧额度、旧草稿/POI保留、进度错误互斥、无本页直接发布或死编辑器复活 |

这些是 root 新实现的验收合同，不是已有39条测试或微信实证。真实服务仍须独立验证 prepare 零配额/模型、原计划 CAS、24h 固定 TTL/metadata 和一次 provider；H 不派这些 CI。

## 本轮实际执行

Node v24.19.0，直接运行获准的冻结 JS：

- simple-ai-request-state.test.js：6 PASS，0 fail/skip；含“UNKNOWN 改灵感另起号”旧危险预期，必须按 v2 替换，不能继续作为绿签。
- simple-ai.test.js：9 PASS，0 fail/skip；其中旧直调/额度成功夹具需换 v2 wire，结构/上限/禁发布等有意义正控保留。
- H baseline-negative.review.test.cjs：**12 条全部实际断言失败**，0 skip/cancelled；退出1。它加载真实864e Page或真实request-client，wx/app网络与storage均本地 doubles。失败证明表列旧边界，不是新 v2 实现失败、12次真实供应商调用或后端资金事故。
- 该 H 文件明确只用于旧源负证据，未加入 CI，也不是将来 v2 通用 acceptance suite；root 须按上方新接口构造相应 fixture。默认 --test 子进程只汇总文件级失败，本报告采用直接 Node 的实际12 case输出。
- SOURCE.json 与 RUNTIME.json 保存源码/用例/source/退出码/输出hash；未进行 WeChat/UI/登录/上传/Java/MySQL/Redis/外部网络/CI。没有改弱旧测试、skip 或扩大 baseline。

本轮交付可回退为文档/独占负例文件删除，无生产迁移影响。旧有资金/B支付/G结构未闭事项均不因本方案或 Node 测试关闭。
