# Lane W10b · 后端依赖私有组：请求回读与审核回执（2026-10-22）

- 分支：`feature/lane-w10b-readback-backend-20261022`（基于 master `063a6c3c`），草稿 PR：#477
- 依据：Lane G2 `private-groups.md` 的 MISSING 项 #22、#23、#25、#27
- 边界：没有合并任何 PR，没有推送 master，没有对其他 PR 做任何写操作，没有访问生产，没有对真实库执行 DDL，没有新增依赖。C00 保留文件一个都没改（`app.js`、`app.json`、`scripts/uiaudit/*.json`、`utils/transport/request-client.js` 等）。

## 每项状态

| # | 后端 | 小程序 | 状态 |
|---|---|---|---|
| 22 AI 草稿状态 | `POST /api/ai/theme/draft` 可带 `requestId`；新增 `GET /api/ai/theme/draft/request` | `pages/publish/simple` | 已完成 |
| 23 按令牌撤销分享 + 版本 CAS | 新增 `POST /api/roam/share/revoke-token`；`POST /api/roam/share/snapshot` 支持 `expectedVersion` 并返回 `version`；**`/revoke` 不变** | 未改（见 CY-HANDOFF） | 后端已完成；客户端交给 roam owner |
| 25 资料版本审核 | `/api/user/update` 记版本；新增 `GET /api/user/profile/review`；`/api/merchant/npc/save` 返回 `receipt`；新增 `GET /api/merchant/npc/review` | `pages/gerenziliao`、`pages/merchant/decor/ai-npc` | 已完成（另有 2 个已有缺陷，见“发现”） |
| 27 NPC 原请求回读 | 新增 `GET /api/ai/npc/chat/request` | `subpackageMember/components/cy/npc-chat` | 已完成 |

### #22 AI 主题草稿 requestId 幂等与回读
- `AiThemeDraftRequestService`：先用 Redis `setIfAbsent` 写入 PROCESSING 占位，再调模型。终态（SUCCEEDED 带当时那份草稿，FAILED 带原因）写回同一个键，保留 24 小时。键里带 memberId，所以别人的 requestId 在本人这里永远读到 NOT_FOUND。
- 占位后超过 3 分钟租约仍没有终态，判为 UNKNOWN。状态存储不可用时直接拒绝，不调模型，返回机器码 `AI_DRAFT_STATE_UNAVAILABLE`。
- POST 的机器码：`AI_DRAFT_INVALID_REQUEST`、`AI_DRAFT_IN_PROGRESS`、`AI_DRAFT_OUTCOME_UNKNOWN`（409）、`AI_DRAFT_FAILED`。成功时 data 结构不变，另加 `requestId`、`requestState`、`replayed`。不带 requestId 的旧客户端行为完全不变。
- 客户端：网络失败或服务端回 IN_PROGRESS/UNKNOWN 时锁住重试，生成键改成“查看结果”，只回读原 requestId，拿回来的就是第一份草稿。回放结果里的剩余额度已经过时，不当作当前值，改为重新读一次额度。额度涨落从来不被当作“已生成”的证据。锁跨关页重开有效（本机只存 requestId、灵感和模式）。只有服务端确认 UNKNOWN、且用户改了灵感，才算一次新的起草（文案提示可能再用一次额度）。

### #23 按令牌撤销单条分享 + 发布版本 CAS
- 迁移 `migration_roam_share_version_20261022.sql`：幂等地给 `roam_share_snapshot` 加 `version INT NOT NULL DEFAULT 1`。同一条足迹每重新发布一次，`version + 1`。
- `revoke-token`：`DELETE ... WHERE member_id=? AND share_token=?`。令牌不存在、已撤销或不属于本人，都返回 `revoked=0`，不区分这几种情况，避免被用来探测令牌。令牌格式不合法返回 `SHARE_TOKEN_INVALID`。
- CAS 在已有的会员行锁内判定。版本不符或这条已被撤销，返回 409 `SHARE_VERSION_CONFLICT`，不写库，也不会悄悄重建一条已撤销的分享。

### #25 资料版本化提交与审核回执
- 迁移 `migration_member_profile_version_20261022.sql`：新表 `ums_member_profile_version`，只记字段名、头像引用和送检时头像 URL 的 SHA-256，不复制昵称、简介等文本。缺表时新 jar 只记 WARN，资料照常保存，`profileVersion` 返回 null。
- 头像复核状态不另存一份，回执时按媒体哈希实时读 `wx_content_callback_inbox`（内容安全回调的权威表）：PENDING、APPROVED、REJECTED、NOT_SUBMITTED（没有送检记录，**不等于通过**）、NOT_REQUIRED（内置预设形象）。复核结论和 `live`（这张图此刻是否就是对外展示的那张）分开给，互不推断。
- 门店 AI 形象回执：人设审核状态、`playerVisible`（审核通过且已启用）、形象图复核状态。商家每次修改都会把审核状态置回待审，回执和页面都明确说“审核通过前玩家看不到这个形象”。保存提示由“角色已更新”改为“已提交审核”。

### #27 NPC 对话原请求只读回读
- `GET /api/ai/npc/chat/request?requestId&scope=roam|merchant|shop&bizId`：按本人、原会话或原店、原 requestId 三项限定，任一不符一律 `INVALID_REQUEST`。不进模型、不扣额度、不重新判资格。不存在返回 `NOT_FOUND`（可重试），存储读失败返回 `REQUEST_OUTCOME_UNKNOWN`。
- 组件：结果未确认的问题，关闭对话后仍保留 requestId；重开时只读回读。仍在处理时，“查询处理结果”只再读一次。只有回读结果是 NOT_FOUND，才把重新发送（同一 requestId）交还给用户。只改了 npc-chat 组件本身。注意：该组件实际位于 `subpackageMember/components/cy/npc-chat`，`pages/play` 下没有 npc-chat 组件；`pages/play` 一行未改。
- 限制：恢复只覆盖同一组件实例内的关闭/重开（组件挂在店铺主页，开关对话不会销毁实例）。离开页面后不恢复，因为对话内容按既有隐私约定不写本地存储。

## 验证证据
- 后端单测（本地 `mvn -pl chengyinhub-admin test -Dtest=…`）：
  - 新增 6 个测试类全部通过：AiThemeDraftRequestServiceTest 7、ApiAiThemeDraftRequestTest 5、NpcChatReadbackTest 7、ApiRoamShareVersionTest 6、ProfileReviewServiceTest 6。
  - 受影响的既有测试全部通过：NpcChatServiceTest 22、ApiAiControllerTest 23、ApiAiNpcPassExpiryTest 8、ApiRoamShareControllerTest 7、E06_17Test 5、ApiMerchantNpcSaveTest 14、ApiUmsMemberPartialUpdateTest 5 等。
- 真库 IT：`LaneW10bReadbackMySqlIT` 已加入 `ci/backend-test.sh` 的 MYBATIS_ITS。它把真实迁移跑两遍来证明幂等，并验证版本号 insert…select 自增、inbox 按媒体哈希读、版本 +1、按令牌撤销只动本人那一条。第一次推送时 CI 的 Backend tests 已通过。本地没有 Docker daemon，所以没在本地跑。
- 资金 IT 门禁：`funds_it_gate` 固定了各类的 @Test 数量。新加用例的那个类，新增用例已撤回（覆盖已移到上面的 IT），`python3 -m unittest ci/test_funds_it_gate.py` 本地 45/45 通过。
- 小程序：本地全量 `node --test "tests/unit/**/*.test.js"` 结果 **13490/13491**。唯一失败是 `action-ledger.test.js` 的“release 前重建 inventory”，原因见下方 CY-HANDOFF。
  - db4f3850 还原权限矩阵之后，`get-auth-matrix-contract` 也会因 `69 !== 65` 失败，补登记的行见 CY-HANDOFF。
  - 新增测试 3 个文件共 16 条全部通过：npc-chat-readback 6、simple-ai-request-state 6、profile-review-receipt 4。
  - 本地已跑通 `ci/api-existence-gate.js`、`ci/silent-error-gate.js`、`lint:requestbody`。按要求没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## 发现（已有缺陷，未在本 lane 修复）
1. **门店 AI 形象的违规照片不会被撤下**（已从代码确认；修复在 #473，本分支已对齐）。在 master 上，`npcSave` 用 `BIZ_TYPE_MERCHANT_PROFILE` + merchantId 送检，而回调只核对和清除 logo、cover、gallery 三项。结果是 NPC 头像即使收到“违规”结论，也只会落成 IGNORED_STALE，头像不会被撤下。#473 把送检改成 `BIZ_TYPE_MERCHANT_NPC(18)`、bizId 用 npc_profile.id；本分支的 55ab4f37 已让回执按 (18, npcId, 哈希) 读取。注意：#473 合入之前，门店形象图的回执读不到送检记录（NOT_SUBMITTED），所以页面不显示形象图的复核结论，只显示人设审核与玩家可见性。
2. **个人头像同样可能被判过期**（可能存在，需 owner 核实）。`/api/user/update` 用原始头像 URL 送检，但库里保存的是 `media-asset:<id>` 引用。两者不一致时，回调的 current 核对会把结论判为过期，违规头像同样不会被清除。图片在上传时已有同步审核兜底，所以这是第二层防线失效。

<!-- CY-HANDOFF -->
## CY-HANDOFF（需要 C00 / 其他 owner 处理）
- **action-ledger 重建（C00）**：`scripts/uiaudit/action-ledger.json` 因为本 PR 改了 3 个页面的 wxml/js 而失效。我在本地 `audit:actions:update` 一次后计数，然后已还原文件，未提交。漂移规模：66 个控件 id 只是行号变化（gerenziliao 30、merchant/decor/ai-npc 35、publish/simple 1），新增 0、删除 0，另有对应的源码 digest 变化。所以 CI 的小程序单测里，`action-ledger.test.js` 那一片会红，需要 C00 重建台账。
- **GET 权限矩阵登记（保留契约 `tests/unit/get-auth-matrix-contract.test.js`）**：这个文件已被另一个会话按保留契约还原到合并基线（db4f3850），所以这里不再改它。在 owner 补登记之前，单测分片会因 `69 !== 65` 变红。请在 `MATRIX` 里加入以下 4 行，并把 `assert.equal(directCount, 65, …)` 改成 69：
  ```js
  ['subpackageMember/components/cy/npc-chat/index.js', "url: '/api/ai/npc/chat/request'", 'required', 'member', 'self-account'],
  ['pages/publish/simple/index.js', "url: '/api/ai/theme/draft/request'", 'required', 'author', 'self-account'],
  ['pages/gerenziliao/gerenziliao.js', "url: '/api/user/profile/review'", 'required', 'member', 'self-account'],
  ['pages/merchant/decor/ai-npc/index.js', "url: '/api/merchant/npc/review'", 'required', 'merchant', 'self-merchant'],
  ```
  依据：4 个端点都不在 SecurityConfig 的 permitAll 里，所以都是 required。`ApiAiNpcController.chatRequest`、`ApiAiController.themeDraftRequest`、`ApiUmsMemberController.profileReview` 都只按登录态读本人记录；`ApiMerchantController.npcReview` 走 `merchantAccessService.require(PROFILE_WRITE)`，按登录商家派生。
- **#23 客户端（roam / privacy-sheet owner）**：后端已提供 `POST /api/roam/share/revoke-token`（form 参数 `token`），以及发布时的 `expectedVersion` 参数、返回的 `version`。现在可以提供“撤销这条分享”了：撤销成功或 `revoked=0` 都表示这条已不再公开；409 `SHARE_VERSION_CONFLICT` 时应刷新，不要重试写入。本 lane 没有改 roam 页面。
- **部署顺序**：两个迁移都要**先于新 jar** 落库（chengyin-db-migrate 只读预检 + 用户确认）。新 jar 的发布 SQL 会写 `roam_share_snapshot.version`，迁移没落库时足迹分享发布会失败。

## 需要在微信开发者工具里人工确认
1. 店铺主页“和 TA 聊聊”：发出问题后断网，关掉对话再打开，应看到原问题和“查询处理结果”；恢复网络后能拿回原回复，Network 里没有第二个 `merchant-chat` POST。
2. 简易创建：生成时断网，按钮应变成“查看结果”；关页重开会自动回读；回读成功后显示草稿，并重新拉取额度。
3. 个人资料：换头像并保存，回到本页时，头像下方显示审核回执文案（内容安全未开启的环境不显示）。
4. 商家装修 → AI 形象：保存后提示“已提交审核”，形象卡下方显示“审核通过前玩家看不到这个形象”；过审后显示“玩家现在能看到这个形象”。
