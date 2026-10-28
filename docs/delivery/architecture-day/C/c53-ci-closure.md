# C：c53 联合 CI 剩余失败独立 delta

结论：本批只修当前合法命令的测试夹具与控件台账来源解析。生产玩法、缺身份拒绝、历史 NULL 指纹拒绝均不放宽。Photo 编排另存 WIP，不在此提交。

## 基线与所有权

- 公共仓库 `liboyang42-cpu/hengyin`；工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 本 delta 精确 parent `7929b4edbd8885d3cb5a31510ac58ab575710f2c`，已包含 d5 第一批收口和 7929 G 接线；不重复覆盖 83 指纹、root Photo adapter、E IntentService。
- 上游真实 CI：root 报告 c53 / run40025693553，admin 13963 条（631 skip），3 failure / 4 error；本批处理 C 的 3 failure / 2 error。其余 G 金融夹具由 G 处理。
- 不改 root 的 ClubLead IT 清理、orderedMembers 编译修复、ThemeRuntime/PredictLateBet 新增 fp 列，也不改 Photo/Vision 专属文件。

## 失败机制与修改

1. `ApiPlayProgressCompletionTruthTest` 的 GPS、SCAN、ANSWER 三处仍要求 runtime 收到 null/null，而真实合法入口已传原 routeActionId/expectedRouteVersion。正向请求改为明确的原命令 Map，发送和 verify 共用该 Map 的精确字段；不使用 any() 替代身份/版本断言。NaN/错误坐标/旧模板答案拒绝、冻结坐标/冻结答案、来源 ANSWER/GPS、当前局证明以及不写旧进度/不发奖励等断言保留。choice 分支仍精确核对 pick-proof/3。
2. G 接线后 `isCompletedCompletionFact` 第三参已为原探索票列表，反射调用改用 singletonList(pass)。保留「有 paid 票的合法 pure content 完成由 PerkLedger 决定」正负控。
3. `TimeWindowSubscribeRuntimeTest` 的重放回执缺 request_fingerprint。以真实 `commandFingerprint` helper、原 session/scope、原版本、原 key、原 action 和 payload 种入原 hash；保留零版本推进断言，历史 NULL 的生产拒绝不动。
4. 导演台核对的多余 POST 是 `/api/role/info`。实际 Controller 为 `ApiRoleController.info @PostMapping`，实际写入 command 仍只属于 retryUnknownWrite。静态调用链为核对→账号读取→App.getSession→注册的 onClear→roleGuard.clear；clear 只调 _dropCache/removeStorageSync，扫描器因 roleGuard 的 primitive 缓存声明把整个模块（含 load 的 POST）算进来。本补丁只允许这个实际模块的 primitive 声明参与已证明的函数闭包，保留声明文本；可执行初始化、未知 export/别名/动态成员继续返回整个模块。新增负控证明 load 仍保留真实 Controller POST，未知访问不被裁剪。核对 GET 矩阵和 every(GET) 原断言不改。

## 台账交付与集成

实际执行 `build-action-ledger.js --write`：5105→5105 控件，新增/删除 ID 均为 0，1239 行来源/可达目标发生变化。生成文件约 33 MB，详见 inventory-proof.json 的前后 SHA-256。

共享 inventory 应由 root 在最终联合树单一重建，避免本 C 树的整份生成文件覆盖其他会话的来源变化。本批提供 C 树的精确 `action-ledger-inventory.patch` 供审阅/匹配基线应用；在联合树优先执行同一 generator，然后运行 `action-ledger.test.js` 与 `build-action-ledger.js --check`。不增加 baseline/ignore，不删除「伪造控件必须判红」负控，不声称动态设备证据已经补齐。

## 验证边界

- `action-ledger-handler-sources.test.js` 实际 22/22 pass，0 fail / 0 skip；完整 TAP 在 evidence/c53-closure。
- `action-ledger.test.js` 首轮实际 46 条：44 pass，2 条因沙箱 `spawnSync git EPERM` error。为这两条获准启用本地 git 子进程后，限定原测试重跑 2/2 pass、0 fail/skip；没有改测试或跳过。两份 TAP 均保留，不能称单次 46/46。原 release inventory 正负控在首轮已经通过。
- 实际 `build-action-ledger.js --check`：`ACTION-LEDGER PASS: 5105 controls, 0 evidence results`。这表示静态门禁通过，0 条动态结果不等于设备验收通过。
- gitleaks 以现有配置扫描本批源码/报告/精确台账 patch 及 raw diff：2.46 MB，无命中；不加 ignore。后续提交前对最终 staged 集复扫。
- Java 编译、两类 JUnit、MySQL/Redis 未在云 VM 运行；待 root 受控 CI。不得把 Node 或静态检查当作 Java 通过。
- 无 schema、DDL、迁移、资金状态或生产操作。回退仅此夹具/扫描器 delta；不得借回退放开身份/fp 校验。
