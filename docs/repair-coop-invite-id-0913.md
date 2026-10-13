# S05 合作邀约精确 ID 合同补全

基线 `261fdb3bc`，独立分支 `codex/repair-coop-invite-id-0913`。

## 问题与最小改动

真实 `/api/coop/list` 返回 `id`，但原生列表卡片与详情读取 `inviteId`。原生 `.tap()` 无跳转，直接进入合法详情返回 `notfound`。该缺口在 `814a3ca22` 已存在，不是 F02 只读投影引入。

真实 MySQL 大主键 `9007199254740993` 序列化为数字后，JavaScript `JSON.parse` 得到 `9007199254740992`。不能从这个值再转字符串恢复精度。

- `CoopInvite` 新增只读 `getInviteId()`，直接由 `Long` 生成精确字符串。旧 numeric `id` 与其他字段保持不变，无 setter，Jackson `READ_ONLY` 明确忽略请求别名，不能用它篡改实际主键。
- 共享 `coop-invite-view.decorate` 校验精确候选后统一卡片、详情、操作身份；相同且安全的旧 numeric `id` 保持类型。兼容旧 `id` 字符串、旧仅 `inviteId` 响应，不修改输入对象或其他展示事实。

新增 getter 会给所有直接序列化 `CoopInvite` 的响应加一个字符串属性，不局限于 list；这是加法字段，不全局改变 ID 序列化类型。数据库、Mapper 写入、鉴权及支付规则均未修改。

正式 `decorate` 消费者仅 `pages/coop/list`、`pages/coop/invite-detail`。列表两侧卡片读 `inviteId`，现有接受/拒绝/撤回/取消、保证金创建/回读/退款与供给动作使用本地 `id`；两者现统一精确值。`club/detail` 仅使用 `decoratePool`；`merchant/coop-center` 的官方邀约字段属于独立合同，不改。相关列表及 checkout 没有对 invite ID 执行 `Number`/`parseInt`。

## 验证

- 原生 RED：`/tmp/f02-ui-probe/ui.log`、`ui-receipt.json`、`expired-detail.png`。过期状态与按钮正确，但卡片无跳转且详情 `notfound`。
- 原始 Long 丢精度：`/tmp/f02-ui-probe/large-id-observation.json`，真实 MySQL → Controller wire 精确数字 → JS 丢失末位。
- 后端 RED：`/tmp/s05-probe/json-red.log`，缺少精确别名；GREEN `json-green.log`，保留 numeric ID、其他事实、拒绝请求别名改写。
- 前端 RED：`/tmp/s05-probe/js-red.log`，普通 id 无 alias、大 ID 使用舍入值；GREEN `js-green.log`。
- MySQL `8.0.45`、本机 `3317`，每次新 UUID schema；真实 Mapper、Service、事务代理及 Controller。原只读/范围/槽位/过期写护栏 4 项及新大 ID 响应 1 项全部通过；独立连接确认查询不写库，finally 删除 scratch。外围身份、商家、俱乐部等依赖使用合成替身，非生产或 HTTP 鉴权 E2E。
- 独立 Maven 编译与测试 **7/7，无跳过**（JSON 2 + MySQL 5），日志 `/tmp/s05-probe/maven.log`。
- 前端合作/保证金/商家合作中心相关测试 **90/90**，日志 `/tmp/s05-probe/coop-regression.log`。覆盖大 ID 的详情查找、列表跳转、接受/拒绝/撤回/取消/退款请求，以及真实 checkout 创建/支付状态回读精确 ID。
- `/tmp/s05-probe/controller-export.log`：当前实际 Controller 生成 `large-id-list.json`，再经真实 `JSON.parse` 与候选 `decorate`，两个前端 ID 均保持 `9007199254740993`。

完整命令：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_PORT=3317 mvn -q -pl chengyinhub-admin -am -Dtest=CoopInviteExactIdJsonTest,CoopListReadOnlyMybatisIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test chengyinhub-xcx/tests/unit/coop-*.test.js chengyinhub-xcx/tests/unit/merchant-coop-center*.test.js chengyinhub-xcx/tests/unit/club-invite-feedback-contract.test.js
```

候选原生 UI GREEN 等待总控集成并明确加载新源码；不会在旧 bundle 中替换 handler 冒充。双轴审查待总控安排；当前冻结，未 commit/push/PR/部署。旧服务不带别名时的超安全整数数字已经不可逆舍入，不能从旧响应恢复；修复后的服务直接给出精确别名解决该链路。


## Spec P1 修订：精确候选与恢复策略

首次审查 Standards 0、Spec 1（P1），未集成。补修兼容策略：

| 输入事实 | 结果 |
| --- | --- |
| 正整数 safe number 或 Long 正数范围内十进制 string | 可精确解释；字符串前导零规范化 |
| 精确 alias + 不安全 numeric id | 使用精确 alias，正常大 Long 可展示、跳转与提交 |
| 精确 raw id + 不安全 numeric alias | 使用精确 raw，绝不丢弃可恢复的真实标识 |
| 两个精确值相同 | 同一目标；安全原 numeric id 保留兼容类型 |
| 两个精确值冲突 | 不任选目标；保留卡片和事实，明确标识待确认并提供刷新 |
| 均不精确、非法、零、超 Long 范围 | 不由舍入值生成业务 ID；同上可恢复错误 |

异常记录不是被过滤掉：保留列表卡片，新增 `cy-error` 的“刷新邀约”按钮，合法同屏记录照常操作；异常记录在刷新拿到一致精确身份后恢复原功能。错误记录的 `rowKey=unresolved-index` 仅用于渲染，解决多条空身份卡片的 key 冲突；业务 `id/inviteId` 为空，展示 key 从不发送。列表邀约操作入口校验精确 ID；详情遇身份不确定返回可重试错误，不虚报“记录不在列表”。用户给出的无效详情编号明确提示回列表，不冒充缺参。

必要范围新增 `pages/coop/list/index.js`、`index.wxml`、`pages/coop/invite-detail/index.js`，复用现有列表刷新、详情 retry、cy-error；没有改动颜色或创建组件。DESIGN.md / chrome-spec.md 在当前仓库缺失，沿原页面组件和样式，无新增皮肤。

- 三种问题独立负控：`/tmp/s05-probe/three-identity-red.log`（将审查前 helper 逻辑单独置于 /tmp 回放，三例均 RED）。初始精确 raw 被 unsafe alias 覆盖的测试失败见 `identity-red.log`。
- 正向：`coop-invite-exact-id.test.js` 共 4 项，包含三问题、Long 范围、非法值、多异常卡独立 key、同屏正常卡可操作、刷新恢复、大 Long 正常请求。
- 全部前端相关回归 **90/90**；`UI-GATE-0 --warn-as-error`、U1–U5 变异自证及 WXML 条件链均通过。日志 `ui-gate.log`、`ui-selftest.log`、`wxml-conditional.log`。未改门禁/基线。
- 后端实现与两份后端测试未在本轮修订中变化，既有编译与 **7/7** 证据仍对应当前文件，不重复跑相同测试。
- 原生正常/异常多卡/大 Long 精确操作请求 GREEN 仍待根集成并确认新运行时加载；当前冻结待两轴复审。

## 根集成与原生回读完成

第二轮 Standards / Spec 独立复审均 0 项。source `94628c244` 已本地集成为 `5534654ac`。根后端相关回归 **139/139**，零失败、错误、跳过（`/tmp/cy-s05-backend-integrated.log`，含真实 MySQL8 五项与 JSON 两项）；前端相关 **90/90**（`/tmp/cy-s05-client-integrated.log`），UI-GATE-0 通过（`/tmp/cy-s05-ui-gate.log`）。

`/tmp/s05-native-green/evidence.md`、`receipt.json` 与 `runtime-proof.json` 记录正式页面原生节点点击。真实 Controller 大 Long 响应解析后旧 numeric 已失真，页面本地操作 ID 与别名仍精确为字符串 `9007199254740993`，证实新运行时已加载；没有替换 handler。普通卡和大 Long 卡均进入详情，实际接受按钮与确认节点触发的请求保持精确 ID。两张异常卡保留事实和刷新按钮，同屏正常卡仍可操作；原生刷新拿到可靠数据后错误消失、功能恢复。5 张截图由执行者实际查看，总控另查异常卡截图。

17 次网络调用全部拦截，2 次写请求仅捕获并受控拒绝，未执行真实业务写入。替身均恢复并断开已有 DevTools 实例。此为本地开发者工具证据，不证明真人／真机、HTTP 鉴权、真实资金或生产上线。
