# BUG-CLUB 上线前 bug 修复（clubs, events, tickets, orders, cart）

分支：`fix/bug-club-20261023` · 基线：master `b84ed673`

计数（共 18）：FIX 7（5 处独立修复）· ALREADY_FIXED 1 · NOT_REPRODUCIBLE 3 · DEFER 6 · OWNER 1

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-67 | FIX | `chengyinhub-system/.../service/impl/ClubEventScheduleMaterializer.java`：每周排期的期数超出首次日期起 90 天窗口时直接拒绝，提示「最多可排 N 期」，不再悄悄少排；`chengyinhub-xcx/pages/club/utils/weekly-schedule-window.js`（新增）+ `pages/club/event-ops/index.js`：提交前按同一口径算出上限并拦截 | `ClubEventScheduleMaterializerTest`（13 期恰好排满；14/64 期被拒）；`tests/unit/club-weekly-schedule-window.test.js` | 原来请求 64 期只落 13 期且不报错。上限 64 与 90 天窗口都保持不变 |
| C:U-014 | FIX | 同 A:P0-67 | 同上 | 与 P0-67 是同一个缺陷 |
| B:P1-031 | FIX | `chengyinhub-system/.../service/impl/CircleExploreServiceImpl.java`：同行邀请码撞上唯一键 `uk_circle_session_invite` 时换新码重试，最多 3 次 | `CircleExploreServiceTest`（撞码一次后换码成功；连撞 3 次后失败且不写成员） | 原来撞码会抛出未捕获的 DuplicateKeyException（500）。字母表不变（排除 0/O/1/I 属于体验优化，暂缓） |
| B:P1-075 | FIX | `chengyinhub-xcx/utils/ticket-source.js`：票源归因记录绑定到账号（`user_id`）；登录前捕获的记录由第一次下单的账号认领；换号或退出后不再带上一账号的归因 | `tests/unit/tandianri-b5-ticket-source.test.js`（新增 3 条） | 原来同一台手机上 A 号捕获的俱乐部归因会跟着 B 号的订单走。失败方向是归平台，与模块原有的 fail-closed 规则一致 |
| C:U-030 | FIX | 同 B:P1-075 | 同上 | `clearAttribution` 仍未被调用：如果要在退出时直接删除，需要改 `app.js` 的 onClear（C00 保留文件），见下方 CY-HANDOFF。账号绑定已经堵住了跨账号串单 |
| C:U-009 | FIX | `chengyinhub-xcx/pages/club/checkin-detail/index.js`：读取失败按状态码分流：403 进入「没权限」，401/重登失败提示「登录已过期」，两种情况都清掉已渲染的客户信息；只有真正的传输失败或 5xx 才提示「网络不稳定」 | `tests/unit/club-checkin-detail-load-failure.test.js` | 复用 `pages/club/utils/owner-action-guard.js` 现有判据，没有新增判定逻辑 |
| C:U-023 | FIX | `chengyinhub-xcx/utils/order-status.js`：退款已到账（payout 1/4）先于人工售后判定 | `tests/unit/order-status.test.js`（新增 1 条：1/4 显示已退款，其余仍是人工售后） | 原来人工案件把已到账的退款显示成「人工售后」 |
| A:P0-08 | ALREADY_FIXED | — | 现有 `CartSettlementTransactionalH2Test`、`ApiCartControllerSettlementTest` | `UmsMemberCartServiceImpl.settleCart` 会先锁购物车行（FOR UPDATE），再逐行删除，删除数不等于 1 就抛异常，所以重复提交不会生成第二张订单。另外，小程序和后台 UI 都不调用 `/api/cart/*`（grep 无结果），弱网重试拿不到原单号的问题在已上线链路上碰不到。以后接入购物车时，requestId 幂等需要和新表一起设计 |
| B:P1-007 | NOT_REPRODUCIBLE | — | — | `/api/cart/list` 的 `type` 只出现在 Swagger 注解里；小程序和后台 UI 都没有调用方，也没有任何请求会带这个参数 |
| C:U-024 | NOT_REPRODUCIBLE | — | — | 俱乐部自建勋章按产品设计由主理人通过 `/api/club/badge/grant` 手动颁发。阈值自动发章 `awardByThreshold` 只对系统勋章码（FIRST_STEP/STREAK_*/TOPIC_CLEAR/MILE_*）生效，不读俱乐部勋章，所以不存在「该自动发却没发」的行为 |
| C:U-132 | NOT_REPRODUCIBLE | — | — | 同 C:U-024。如果以后要让俱乐部勋章自动解锁，属于新功能，需要产品决定 |
| B:P1-028 | DEFER | — | — | 两个调用方（`OmsTicketServiceImpl.hasOpenSaleRegistration` 用 contains，`CmsActivityServiceImpl.openSaleTicketIds` 转成 HashSet）都把结果当集合用，顺序不影响行为 |
| B:P1-073 | DEFER | — | — | `club_event_series.timezone` 固定写 Asia/Shanghai，所有日期计算也都用 Asia/Shanghai，前后一致，没有算错的场景。是否支持多时区属于产品决定，不在上线前范围 |
| C:U-017 | DEFER | — | — | 同 B:P1-073（只涉及常量抽取和注释） |
| C:U-013 | DEFER | — | — | 前端的 `CLUB_ROLE_CODES`/`EVENT_ROLE_CODES` 与服务端 `ClubRoleCode` 的可分配集合逐项一致，属于重复定义，没有用户可见影响 |
| C:U-065 | DEFER | — | — | 多份 ok-code 判断的语义一致（都认 200/'200'），属于代码整理 |
| C:U-168 | DEFER | — | — | 报名页客户端的截止/窗口判断只用于预先置灰，建单时以服务端 `RegistrationOrderCreateGateImpl` 为准，前端放行的单服务端照样会拒。改成服务端统一下发判定对象属于重构 |
| A:P0-33 | OWNER | — | — | 推荐兴趣画像的事件权重（payment_success=8）属于排序产品规则，不是数据错误。是否降权、是否加 sponsored/disclosure 标注、是否出 Top-N 差异报告，都需要主人决定。这里没有可以先做的 fail-closed 部分，所以没有改动 |

## 验证

- 后端：`mvn -pl chengyinhub-admin test -Dtest=ClubEventScheduleMaterializerTest,CircleExploreServiceTest,ClubEventOpsServiceImplTest` → 4 + 26 + 12 个用例全部通过
- 小程序：`node --test` 跑本次改动和相关页面的单测：order-status / ticket-source / checkin-detail / event-ops 全部通过；`lint:scope`、`silent-error-gate`、`api-existence-gate` 通过
- 动作台账（`scripts/uiaudit/action-ledger.json`）**按规则没有重新生成提交**：controls 5009 → 5009（数量不变），30 个控件的源码摘要漂移（`pages/club/checkin-detail`、`pages/club/event-ops` 两页的 JS 改了），`tests/unit/action-ledger.test.js`「release 前重建 inventory」那一条会因此变红，需要 C00 统一重建

## CI

提交 `0c1ecb77` 上的 CI（PR #558）：
- GitHub CI run 39957816704：35 个检查中 29 个成功、3 个跳过、3 个失败。失败的是 `Mini program unit shard (10/20)`、`Mini program static gates`、`Mini program static checks`（汇总），三个都只是因为动作台账漂移（`action-ledger.test.js`「release 前重建 inventory」与 `build-action-ledger.js --check`）。按规则这里不提交重建的台账，交 C00 统一重建。后端测试与其余小程序分片全部通过
- Funds MySQL IT run 39957816746：通过
- Mini followup focused regression run 39957816846：通过
- Isolated authenticated Redis recovery run 39957816961：通过

<!-- CY-HANDOFF -->
- C00：合并后统一重建 `scripts/uiaudit/action-ledger.json`（`npm run audit:actions:update`）。漂移只涉及 checkin-detail / event-ops 两页的 30 个控件摘要，控件数不变（5009）
- C00（可选）：在 `app.js` 的 `getSession().onClear` 里加一行 `require('./utils/ticket-source.js').clearAttribution()`，退出或换号时直接删除归因记录。不加也不会串单，`ticket-source.js` 已经按账号隔离
<!-- /CY-HANDOFF -->
