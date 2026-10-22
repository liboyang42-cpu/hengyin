# Lane W4 收尾 — 同意记录 requestId 去重 + #3 / #11 剩余子项（2026-10-22）

- 分支：`feature/lane-w4-consent-3-11-20261022`（从最新 master 切出，开 PR 前已 rebase 到最新 master）
- 依据：`feature/lane-g-triage-20261022` 的 `docs/delivery/release-closeout-20261022/disposition.md`：F 组 #3（PORT_REMAINING_DIFF）、#11（SPLIT_PORT_SUBITEMS），以及“需要 owner 决策的事项”第 3 条“同意记录去重”
- 授权：owner 于 2026-10-22 批准
- 与 #456（Lane E，退出重登同意台账去重）的关系：#456 只改小程序（`utils/roam-player-memory.js`、`pages/club/apply`），本 PR 不改这些文件；退出时保留 `cy_usage_pending_v1:<账号>` 与漫游桶的行为保持不变（见下方 #11 R2）

## 1. 同意记录：requestId 相同但事件不同，不得静默成功

| 位置 | 改动 |
|---|---|
| `ConsentRecordServiceImpl.record`（两个重载） | 移植 55cd5573。加 `@Transactional`。插入时撞上 `uk_consent_request`：用 `selectByRequestIdForShare` 做当前读（`lock in share mode`，不受外层 RR 旧快照影响），逐项比对 member/docType/docVersion/scene/scopeType/scopeId/eventType。全部一致 → 返回 0（幂等）；有任何不同，或原记录读不到 → 抛 `ServiceException("请求标识已用于其他同意事件", 409)`。异常不带原事件内容 |
| `ConsentRecordMapper(.java/.xml)` | 新增 `selectByRequestIdForShare(requestId)`，只追加，不改已有语句 |
| `ApiAgreementController.consent`（`POST /api/agreement/consent`，即任务里说的 agree 入口） | 捕获 409：返回 `AjaxResult{code:409, msg:"请求标识已用于其他同意事件", errorCode:"CONSENT_REQUEST_CONFLICT"}`，不再回 `success()`。其他异常照常抛出 |

其他调用方的影响（都核对过）：
- `ApiComplianceController.record`：本来就通过 `byRequestId(memberId, requestId)` + `matchesWrite` 回读，不一致时失败。现在 409 会被它已有的 `catch (RuntimeException)` 接住，返回“授权状态暂不可用”，仍然是失败，不会报成功。
- `ApiRoamController`（roam_presence）和 `MemberIdentityService`：requestId 由服务端随机 UUID 生成，不会撞键。两者都已在事务内，`REQUIRED` 传播不变。
- 仓内小程序和后台 UI 目前都没有调用 `/api/agreement/consent`（grep 无结果），所以前端不用改。

测试：
| 用例 | 覆盖 |
|---|---|
| `ApiAgreementControllerTest#sameRequestReplayOfSameConsentEventIsIdempotentSuccess` | 同一请求重放 = 幂等成功 |
| `ApiAgreementControllerTest#sameRequestIdForDifferentConsentEventReturnsExplicitConflict` | 事件不一致（换文档 / 旧版本 / REVOKE）= 409 + `CONSENT_REQUEST_CONFLICT` |
| `ApiAgreementControllerTest#anotherAccountCannotReuseRequestIdAndOriginalIsNotEchoed` | 跨账号不能复用 requestId，响应里不回显原事件 |
| `ApiAgreementControllerTest#duplicateWithoutReadableOriginalFailsClosed` | 撞键但读不到原记录 → 失败（fail closed） |
| `ConsentRecordServiceImplTest`（+3 例，改 1 例） | 7 个语义字段逐项不一致都会 409；带 scope 时比对商家；读不到原记录 → 409 |
| `ConsentRecordIdempotencyMySqlIT`（移植 55cd5573，场景改为 `app_launch`，因为 master 已禁止通用入口写 `roam_location`） | 真 MySQL：原事件不可变；两条连接并发重放（同事件 0/0，不同事件 0/409）；外层 RR 旧快照下仍按当前原记录判定。需设置 `CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1` 才会运行（与原提交一致，未加入 funds 闸的固定计数） |

## 2. #3 剩余子项

| 子项 | master 上的现状 | 本 PR |
|---|---|---|
| 支付中订单对账跨页出错时保留进度（`OrderCloseTask`） | 已覆盖（f1341d06），`OrderClosePayingReconcileTaskContractTest` 与 #3 的 blob 完全相同 | 不改；本分支重跑 2/2 通过 |
| 消息：IM 列表过期响应不得恢复已删除/已静音的行 | 已覆盖（f1341d06 及之后的修改），`tests/unit/im-list-mutation-race.test.js` | 不改；本分支重跑通过 |
| 消息：无效会话不能更新已读 | 已被后来的重写覆盖：`markReadVisible`（`deleted = 0` + cutoff） | 不改；`ImLegacyReadLockTest` 6/6 通过 |
| 草稿：普通保存写入章节/站点顺序（`CmsTopicServiceImpl`） | 已覆盖，`TopicDraftSubmittedOrderTest` 与 #3 完全相同（diff 为空） | **只记录**：草稿子项是后端 service，不涉及 `pages/publish/fabu`（归 lane D）；本 PR 没有改任何小程序发布页。本分支重跑 2/2 通过 |
| 同意记录：重复 requestId 必须与原事件一致（55cd5573） | **部分覆盖** | **已移植**，见第 1 节 |
| NPC 有效期：自助通行证过期后不能开启新的文字/语音对话 | 已覆盖（f2b2c7c2/b44d43df） | 不改；`ApiAiNpcPassExpiryTest` 8/8，`ApiAiNpcVoiceChatFormatTest` 16/16 |
| 动作台账提速 + 行号（0f0c32f1） | 派生文件 | DO_NOT_PORT |

## 3. #11 拆分子项（只处理非资金部分）

| # | 子项 | 结论 | 说明 |
|---|---|---|---|
| R1 | 支付回调 inbox 防护 | **跳过（lane W3 负责）** | 资金路径，本 PR 不碰 |
| R2 | 退出登录时让漫游缓存代次失效 | **不移植，需 owner 确认** | 见下 |
| R3 | 首次报名通知改为持久化 intent | **KEEP_OPEN_OWNER_DECISION** | 设计取舍（after-commit 还是持久 intent），按 disposition 不整体移植 A 侧 service |
| R4 | 提现失败恢复 service | DO_NOT_PORT | master 后来的设计已覆盖（90a8c136、ed682117） |
| — | IM `markReadTo`、`roam/index.js`、`im/chat`、`baoming.js`、`square/list`、自助通行证 | 已覆盖或被取代 | 按 disposition 核对，无剩余 |
| — | 旧 integration 包、旧 `app.json`、台账/基线/`docs/verification/mini-foundation-*` | DO_NOT_PORT | 不恢复 |

**R2 为什么不移植**：#11 的写法默认“退出会清掉全部漫游数据”。master 后来改了设计：退出只清账号态，**保留各玩家自己的漫游历史与恢复记录**。依据有三：`clearStoragePreservingPlayerRoamMemory`；`tests/unit/roam-player-memory.test.js` 的「正常退出只清账号态，保留各玩家独立的漫游历史与恢复记录」；#456 的 `logoutThroughSettings` 断言 `roam_memory_v1:7:sessions` 退出后仍在。

我把 #11 的 `logout-roam-cache-epoch-integration.test.js` 补齐 fixture 后在 master 上跑了一遍（原 fixture 缺 `getStorageInfoSync`，退出根本走不到 `reLaunch`，所以那个测试在 master 上是空过）。结果是：退出后漫游页卸载，会把**同一账号**（`roam_memory_v1:9:*`）尚未同步的格子和揭示记录写回**该账号自己保留的桶**。这里没有跨账号写入，桶的 key 按账号隔离。

如果照 #11 加上 `invalidateRoamCache(owner)`，退出时会直接丢掉这部分未同步的进度，和“保留漫游记录”的设计相反。因此本 PR 不移植 R2，测试文件也不带入。如果 owner 认为退出时应该丢弃未同步的漫游进度，再另开任务。

## 本地验证

| 命令 | 结果 |
|---|---|
| `mvn -q -pl chengyinhub-admin -am -Dtest='ConsentRecordServiceImplTest,ApiAgreementControllerTest,ApiComplianceControllerTest,MemberIdentityServiceTest,OrderClosePayingReconcileTaskContractTest,TopicDraftSubmittedOrderTest,ApiAiNpcPassExpiryTest,ApiAiNpcVoiceChatFormatTest,ImLegacyReadLockTest' test` | 12+6+12+10+2+2+8+16+6，0 失败 / 0 错误 |
| 同上，只把 service 修复撤回（负控） | ConsentRecordServiceImplTest 失败 4 例，ApiAgreementControllerTest 失败 4 例 |
| `CHENGYIN_CONSENT_IDEMPOTENCY_MYSQL_IT=1 mvn … -Dtest=ConsentRecordIdempotencyMySqlIT test`（本地 Docker `mysql:8.0`，8.0.46） | 5/5，0 跳过；并发 trace 为 `completed:0/409` 和 `completed:0/0` |
| 其他引用同意 service/mapper 的单测：`ApiRegistrationPayExpiredCodeTest,ConsentGuardTest,MerchantCrmExportServiceImplTest,MerchantCustomerRosterTest,ProjectPlayerContactVisibilityTest,RegistrationConsentGateTest,RoamLocationRevokeHidesPresenceTest` | 通过 |
| `node --test tests/unit/{im-list-mutation-race,roam-player-memory,usage-consent-app,logout-server-ack-contract}.test.js`（Node 22.22.0） | 43/43 |

未运行：完整 `mvn test`；需要专用环境变量的其他 MySQL IT（如 `RoamPresenceRevokeDurabilityMySqlIT`，它们走随机 UUID，不会撞键）。没有新增依赖，没有 DDL，没有动鉴权或 RBAC。
