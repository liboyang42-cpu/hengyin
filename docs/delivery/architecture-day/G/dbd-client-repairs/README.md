# G：PR660 / dbd12e0 实际失败 SOURCE 修补

基线与直接 parent：`dbd12e0a0d88e54597c274b56b08f2784c415d0e`；tree `b9607cf9d468108531b332f3b87fe1baeaa4a83d`。独立 worktree `/workspace/hengyin-g-dbd-client-repairs`，分支 `codex/architecture-day-g-dbd-client-repairs`。只改本任务 6 个实际源文件及 G 目录，5 个原测试文件后像都列出（page-cleanup-audit.test.js 保持原字节）。未编辑 F 的 get-auth、H 的 guest-browse、SDK/资金/CI/workflow/生成物或其他 owner 工作树。

## 原失败、依据与实际改动

- station：原 job `123062586768` 的 9 个创建场景停在装配缺依赖。原 fixture 现在装真实 verification-readback 和 coop-invite-request、按账号持久 Map 与原 credential；不 mock workflow 成功，也不把请求号当据点凭证。case 结束调用原页面 onUnload 清理真实 helper。保留原 scoped GET/接受/取消/缺身份/精确长 ID/不适用原单等场景。隐藏后的 busy 断言迁其真实生命周期：活跃时仍 true；离页后旧内存退休为 false，但原 key/requestId/payload 仍锁住且新实例回同据点仍零 POST。原据点 102 不变，不能靠旧内存 busy 假装跨实例防重。
- invite：原 job `123062586954` 两个摘要失败是 UNKNOWN 增加了原 payload/key 后旧 exact-object 断言缺字段。现在 exact 比较全部原身份与条款，并验证 pending 原 requestId，原成功/明确失败/UNKNOWN/本次未提交的摘要与重试断言不删。
- invite topic 往返：同 job 的旧回包覆盖新 busy 场景不是简单缺字段。实际 dropdown 可触发 onTopicChange，但 `_submitting` 提前返回使测试中的真实切换根本未发生，之后仍是旧活跃请求。现原入口在当前账号/可见页面下通过已有 `_stopInviteWrites` 退休回调和 helper；原磁盘 UNKNOWN 不释放、不重发；相同主题 change 仍 no-op。测试增加实际到 B/回 A/epoch 变化/相同主题不退休的断言，原坏回执 success/fail 加精确 typed 原成功回执都不能改新状态。只改该既有入口，不改写入算法、request fingerprint、服务端 DTO、scope 或金额。
- merchant：原 job `123062586906` 的两个货架断言仍钉旧 gameSeq 单 guard 与仅已加载才 show 刷新；现在用真实 shelf Component 执行原成功→刷新失败保留缓存、原初次失败给错误、hide/show 同时重读两 tab、旧 epoch 回包不覆新状态。保留原 WXML 错误可见/无 inline-error 含义，不把新 current()/epoch 保护撤回旧正则。
- template-tab：原 job `123062587033` 两例的实际 WXML 是 topic 的 wx:if +相邻 game wx:else；旧 helper 找不存在的 game 条件后以 -1 截取了整页。改为严格锚定实际相邻外层分支并要求匹配成功，分别验证 gameError 与 topic 缓存隔离；原 11 类行为/分页/完整集合/空态变体负控/整包采用全部保留。
- cleanup：已通过 GitHub 原 job `123062586906` 完整日志读回，首因是 `Error: spawnSync git ENOBUFS`，栈精确 `tests/tools/audit-page-cleanup.js:9:15`；stderr 空，stdout 是尚未完整返回的 git ls-files，不是页面删除/路由权限断言失败。本基线 Git manifest 实读 `1,055,960 bytes`，超过该内层 execFileSync 默认 `1,048,576 bytes`。仅给该内层 Git 清单读取设 8 MiB 有界缓冲（沿原外层审计测试已有 8 MiB），溢出仍失败；不删路径、不截断、不跳过 scan、不改变 KEEP/UNSURE/DELETE、orphan、真实导航与 mentions 的原判据。原 page-cleanup-audit 两项断言原样保留。没有运行该脚本。

## 历史两轮与本轮完整性

前两轮 donor 已由 root 纳入当前完整基线，不再次套旧补片：

| 供体 | parent | tree |
|---|---|---|
| `ced3a07da364a458abb3575f412cb9677d575a8e` | `62d4e0bbf47efa2f73aa7e604f1dfe092518d923` | `7bce05eefd62ab760df06219a2f6b651652d9ca6` |
| `7e980494034416d4df9a29d8de4c1f238d67f605` | `d4cc5c59f65dcc0196a88eb556de9a10b663c56d` | `69fe798a910d613e2b19704d8b1b20e737e667a4` |

本次单一增量以完整 dbd 为父，保留两轮及其他 owner 后像。没有跨 owner 生产改动，因而没有必须另行应用的共享 hunk。

## 实际执行与 NOT_RUN

读取原 CI job 日志、冻结源码与 Git 清单字节；编辑源码并做 Git diff/apply-check/文件摘要及限定密钥模式文本核对。未执行项目 parse/import、Node/Python/JUnit/Java/DB/Redis/Docker/HTTP/CI 或微信，不改变堆、线程、PID、请求读取/held/cleanup 等验收预算。**全部新源与旧断言均 NOT_RUN；不能把旧失败或本次源码核对签成 PASS。** root 唯一同源合成与验证。

完整源码 patch 为 owned-source.patch.b64，解码字节数及 SHA256 见 patches.bytes / patches.sha256；7 个 owned 后像（含未改文件）在 postimages.sha256。没有文件删除记录。
