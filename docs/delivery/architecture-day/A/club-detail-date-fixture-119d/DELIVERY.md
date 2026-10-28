# A：club detail 核销夹具 localDateText 装配修复

结论：实际失败是测试摘取生命周期方法的边界错误，不是生产缺日期 helper。只修改 `chengyinhub-xcx/tests/unit/verification-unknown-adapters.test.js` 的共享 harness 和新增一条直接回归源码；生产页面、localDateText、账号/角色/UNKNOWN/重发规则均未修改。

- parent：`119d3f87bbf89162d47db837c404b361915d1b81`。
- 工作树：`/workspace/hengyin-a-club-date-fixture`；分支：`codex/architecture-day-a-club-date-fixture`。
- 原运行：标准 CI `40215793929`，Mini program unit shard 1 job `121236649947`。
- 原日志通过 GitHub 只读 connector 实际读取；CLI 日志端点先返回 Forbidden，未据此重开 CI，未绕过权限。没有把读取旧日志称作本片运行。

## 实际机制与最小修复

1. 生产 `pages/club/detail/index.js:204` 已定义原 `localDateText(date)`；`:539` 的月历初值在正常页面模块词法作用域内调用它。
2. 原 `:512` 的 `onHide()` 是完整单行方法，只更新 `_loginContinuationEpoch`。它后面是 `_clubAccessState` 与 `data`，日期初值属于 `data`，不是核销 adapter/lifecycle 的依赖。
3. fixture harness 原 `:34–39` 只寻找下一行同缩进 `}`，未识别本行已经结束的方法；于是把 `onHide` 及其后整个 `data` 摘进 `new Function`。这个隔离函数没有页面外围 helper，构造时即 ReferenceError，尚未到达核销/席位业务断言。
4. 旧日志的 68 个 `localDateText is not defined` 均指向该同一 harness `:58/61`，包括席位丢包、UNKNOWN、账号 ABA、存储失败、4 个实际 request-client/form 场景以及 JSON header 负控；它们不是 68 个独立生产故障。
5. 最小修复让共享 lifecycle 摘取先识别当前生产完整单行 `},`/`}` 形式，再保留原多行同缩进边界；只拼接实际生命周期 member，不注入假的日期函数、不执行无关 Page.data，也不跳过 onHide。

所有旧 case 正文和业务断言原样保留。四入口仍是同一个真实 `createVerificationWorkflow`、seatRecovery 和实际 request-client（原 form/JSON 负控也保留），没有 mock 成统一成功或自动重发。

新增一条固定 `test` 声明：`club lifecycle extraction keeps the real inline or block onHide without evaluating Page.data`。它使用真实 club adapter 和 lifecycle 源、同一方法的等义多行装配，核对未带入 `localDateText/eventCalendarMonth/data`、原账号代次确实递增、超时后 UNKNOWN 不产生第二 POST。仅为新增回归源码，未运行。

## 后像与边界

`SOURCE_SHA256SUMS` 包含修改 fixture 与本报告摘要。生产页面仍为原 parent 字节，SHA256：

`a325f3c2e012991529ce0e4e1e069c38413c927231e3576cb4debd16de1e0ed4`

独立分支没有覆盖 H Java、D QA、E inbox、F registry、G migration 或其它 owner 的源。没有新增依赖、通用解析器/测试框架、页面兼容 fallback 或新业务规则。

本片只读源、编辑源码、git diff/hash/秘密扫描。Node/Java/编译/CI/微信及所有新行为验证 **NOT_RUN**；原68失败回执保持，不宣称修后68通过。root 在同源集成后安排定点复验。
