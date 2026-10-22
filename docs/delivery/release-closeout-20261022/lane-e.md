# Lane E 收尾 — 同意台账退出重登 + 主理人资质图片（2026-10-22）

- 分支：`feature/lane-e-consent-qual-20261022`
- 基线 master：`063a6c3c`（2026-10-21，Merge #408）
- 草稿 PR：https://github.com/liboyang42-cpu/hengyin/pull/456（Refs #418 #412；原 PR 均未改动、未评论）

## 改动

| 提交 | 内容 |
|---|---|
| `a796c787` | 重放 #418：`utils/roam-player-memory.js` 退出清缓存时保留严格 `cy_usage_pending_v1:<正整数账号>` 同意台账；与 master 的 `verification_unknown_v1:<sha256>` 保留规则合并解决冲突；+5 条回归 |
| `f7c2d139` | 带入 #412：`pages/club/apply/index.js` `removeCert` 删除后回调 `validate`；+3 条回归 |
| `cea160c9` | `removeCert` 忽略负数/越界/非整数/字符串索引（行数不变，台账行号不漂移）；+3 条回归 |

未改 app.js 及任何 C00 预留文件；无后端、无 DDL、无新依赖。

## #418 验收（BUG-17）

37 项回归 = roam-player-memory 9 + usage-consent-app 17 + logout-server-ack-contract 11（Node 22.22.0）。
master 代码：32 通过 / 5 失败（新增的 5 条全红）；本分支：37/37 通过。

| 验收项 | 证据 |
|---|---|
| 同账号重登只记录一次 | `normal settings logout retains completed owner consent ledger without recording agreement again` |
| 待补传沿用原两个 requestId | `normal settings logout resumes both pending consent documents with their original UUIDs` |
| 其他账号不能消费原账号台账 | `logout and another normal account login cannot consume the former owner consent ledger` |
| 冷启动仍走同意门 | `preserving an owner ledger on logout does not preserve the global choice or bypass a cold consent gate` |
| 异常账号键（空/0/负/前导零/后缀/换行/\r/U+2028/U+2029/7e0/:other）不保留 | `退出只保留严格账号同意台账，不保留全局选择、重试键或伪前缀` |
| 漫游恢复桶不误删；删除失败如实上报 | 既有 `删除本地账号键失败时返回失败且绝不删除漫游桶`（仍通过）；`logoutThroughSettings` 断言 `roam_memory_v1:7:sessions` 保留 |
| 核销 UNKNOWN 保留不回退 | `verification-unknown-adapters.test.js` 236/236 |

相关 8 个同意/隐私/漫游测试文件合计 334/334 通过。

## #412 验收（BUG-16）

- #412 原分支合入当前 master：无冲突；引用 `club/apply` 的 20 个测试文件全部通过（club-apply-state-contract 21/21）。
- 本分支 club-apply-state-contract 24/24；action-ledger.test.js 45/45（台账无需重建）。

| 验收项 | 证据 |
|---|---|
| 删除最后一张失败图 → 可提交 | `删除最后一张失败资质图后恢复无资质提交` |
| 仍有失败/上传中图片 → 继续阻止 | `删除一张资质图后仍有失败项不得提交`、`删除失败资质图后仍有上传项不得提交` |
| 索引重排后错误标记贴对图 | `删除失败资质图后错误标记随索引重排贴到正确图片`（新增） |
| 异常索引不删错图 | `异常删除索引不删错资质图也不清错误标记`（新增；撤掉守卫即变红） |
| setData 回调后真实页面恢复 | `真实页面 setData 回调延后执行时删除最后失败图后仍恢复提交`（新增，模拟回调延后执行） |

未新增任何资质/证件要求。

## 本地门禁

silent-error-gate、forbidden_hosts_gate、roam-redline-gate 均通过。未运行 ci/xcx-check.sh 与 ds-hardcode-gate-selftest.sh（按规则）。

## 台账 / 基线

action-ledger、route-matrix、基线均未改动且无需重建（计数 old = new）。

## CI

PR #456 head `2246753c`：
- GitHub CI run `39756576123`：success —— Fast gates、Secret scan (gitleaks)、Detect changed areas、Mini program static gates、Mini program static checks（台账逐字节检查通过）、Mini program unit shard 1–20/20 全部 success；Backend/Migration/Admin UI/SBOM 等因无对应路径改动 skipped。
- Funds MySQL IT run `39756575911`：success（2/2）。
- 上一提交 `cea160c9` 的 run `39756506402` / `39756506458` 同样触发（已被新提交覆盖）。

#412 原分支 head `28ee751a`：mergeable=true / clean（对 master `063a6c3c`），6 个 success、其余因路径 skipped —— 状态与之前一致。

## 未决 / 观察（未改，避免扩大范围）

- `removeCert` 删除图片时未同步位移 `certUploading` 索引；若上传进行中删除其前面的图片，上传回调 `images[index] = ...` 可能写到错位。目前 cy-upload 是否允许上传中删除需真机确认；如需修复请另开任务。

## 需在本地微信开发者工具人工核验

1. **同账号退出重登不重复记录**：清缓存 → 冷启动同意协议并登录账号 A → 调试器 Network 记录 `/consents` 两次 → 设置页「退出登录」→ 再次登录 A：不应再出现 `/consents` 请求；Storage 中 `cy_usage_pending_v1:<A的id>` 退出后仍在，`cy_usage_choice_v1`、`cy_consent_retry:*`、`authorization` 已清除。
2. **待补传沿用原 requestId**：Network 面板将 `/consents` 设为失败（或断网）→ 同意并登录 A → 记下 Storage 中两份 pending requestId → 退出 → 恢复网络后重登 A：两次 `/consents` 请求体的 requestId 与记下的一致，完成后 pending 为空。
3. **跨账号隔离**：在第 2 步的待补传状态下退出，改用账号 B 登录：B 的 `/consents` requestId 与 A 的不同；A 的台账原样保留。
4. **冷启动仍走同意门**：退出后「编译」冷启动：应再次出现使用协议弹窗，点「拒绝」不发登录请求。
5. **漫游恢复桶**：退出前有 `roam_memory_v1:<id>:*` 键，退出后仍保留。
6. **主理人资质图**：主理人申请第 4 步上传一张资质图并让上传失败（Network 断网）→ 提交按钮禁用 → 删除该图 → 按钮恢复可点，提交成功且 `certImages` 为空。
7. 上传两张都失败，删一张 → 仍禁用，剩余那张仍显示失败标记；在上传中时删除另一张失败图 → 仍禁用。
8. 四张图中第 2、4 张失败，删第 2 张 → 失败标记出现在原第 4 张（现第 3 张）上。
