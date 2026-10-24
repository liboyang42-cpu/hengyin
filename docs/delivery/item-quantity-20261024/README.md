# 物品条件数量边界修复与历史 PR 分流

本分支从公开 master `e2e05b9614a3c5d62b774718797f9a6792a3eeca` 开始，不导入旧 carrier 历史。

- 当前修复：`ITEM_HELD` / `ITEM_ACQUIRED` 数量必须为 1..999 的整数，保存期与运行期都拒绝截断／非整数输入。合法持有阈值、累计获得事实及不消耗库存的语义保持不变。
- 后续审查补齐 `JourneyStoryValidator` 与 `TopicRouteConfigValidator` 的 `canConvertToLong()`，回归覆盖 `18446744073709551617`、`-18446744073709551615`（两者 asLong 可截成 1）和合法 1／999。剧情创建／编辑／发布重验均经前者，条件原值由 `PlayJourneyStoryServiceImpl.pickEnding` 交给路线引擎；路线发布另有 `TopicRoutePublishGate` 的引擎二次门禁，不据此声称能绕过路线发布。
- 历史 8 PR 的完整逐项分流：[historical-pr-disposition.json](historical-pr-disposition.json)。已完整读取全部 /files 分页，共 2,316 条文件记录；包含各 PR 精确 head、已承接子项、明确遗漏、待定项和关闭建议。分流不是旧 head 的运行时验收。
- #338：[调用链与真实触摸复现步骤](sheet-readonly-reproduction.md)。用户影响仍为 unknown，不修改 sheet。
- 自动核销游标遗漏由 #596 独立处理，本分支不重复实现。

## #3 可关闭重复入口的承接证据

以下均已在上述 master 核对。提交 `f1341d06`、`f2b2c7c2`、`b44d43df`、`e3b302dc` 为该 master 的祖先；证据来自现有实现和测试，不依赖 PR 标题。

| 子项 | 当前实现文件与可查符号 | 现有回归入口 |
| --- | --- | --- |
| 支付中订单对账翻页 | `chengyinhub-system/src/main/java/com/chengyinhub/business/task/OrderCloseTask.java`：`reconcileCursor`、`listExpiredPayingIds(afterId)`、扫描上限及失败后游标恢复；对应 `CmsRegistrationMapper.xml#selectExpiredPayingIds` | `chengyinhub-admin/src/test/java/com/chengyinhub/business/task/OrderClosePayingReconcileTaskContractTest.java#failedSecondPageKeepsAttemptedPayingPrefixUntilQueryRecovers` |
| IM 列表删除／静音与旧响应竞态 | `chengyinhub-xcx/subpackageB/pages/im/list/index.js`：`_conversationRequestEpoch`、`_conversationAppliedEpoch` 和按会话身份恢复，旧列表不能覆盖新写入 | `chengyinhub-xcx/tests/unit/im-list-mutation-race.test.js`：删除后旧响应、静音失败重排、晚回调及重试等 |
| 无效／不可见消息不得推进已读 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java`：当前会话/成员锁与 `selectViewerVisibleByIdForShare`、`markReadVisible`；`mapper/business/ImConversationMemberMapper.xml` / `ImMessageMapper.xml` 提供锁定当前读 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImLegacyReadLockTest.java`、`ImServiceImplTest.java`；真实并发入口 `ImLegacyReadLockMySqlIT.java` |
| 草稿章／节点按提交顺序重建 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java`：`saveChaptersAndNodes` 中每章递增 sortId，`saveNodes` 每章重置并递增 sortId，重建清除客户端旧 ID | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicDraftSubmittedOrderTest.java`：`createDraftAssignsChapterAndPerChapterNodeOrderFromSubmittedArrays`、`rebuildDraftUsesNewSubmittedOrderAndRestartsNodeOrderForEachChapter` |
| consent 请求号必须对应同一事件身份 | `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ConsentRecordServiceImpl.java`：重复键后 `selectByRequestIdForShare`，比较原事件／商家 scope，不一致返回 409；`chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAgreementController.java` 映射冲突 | `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ConsentRecordServiceImplTest.java` 的跨身份冲突、无原记录关闭、商家 scope 用例；`ConsentRecordIdempotencyMySqlIT.java` |
| NPC 通行有效期与原请求答案恢复 | `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAiNpcController.java` 调 `hasCurrentNpcRegistration`；`chengyinhub-system/src/main/resources/mapper/business/PlayTeamMapper.xml#hasCurrentNpcRegistration` 检查通行证 expires_at／场次结束与取消 | `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiAiNpcPassExpiryTest.java`：过期阻止新文字／语音、ASR 中过期、续期恢复、历史与原答案恢复；`NpcCurrentRegistrationMySqlIT.java` |

结论：未发现 #3 的明确剩余运行时修复；可以由协调者关闭重复入口。本分支不关闭 PR，不重建其动作台账或旧交付文档。以上列的是现有测试入口，不能当作本次全部实跑结果。本次另在上述基准实跑 `node --test tests/unit/im-list-mutation-race.test.js`，11/11 通过、0 skip；没有在 agent 环境执行其 Java/MySQL 测试。

## 本次验证

先提交仅回归测试候选 `13c12e6ae67a50cab356d706afba90d75a9ee61c`，通过既有 GitHub 托管 CI 复现后再提交修复。覆盖两种 op 的负截断、正截断、超 Long 正负值、0、1000、decimal（包括 1.0 / 1e0）、字符串、null 和合法 1／999。运行时非法条件抛异常，避免 NOT 反转成通过。

修复前 [GitHub CI 40066080353](https://github.com/liboyang42-cpu/hengyin/actions/runs/40066080353)：Backend tests 的 Build and test backend 步骤失败（job 120789603975）。日志接口当时返回 Transport closed，具体失败断言需回读，不仅凭 exit 1 声称已定位所有失败。最终精确 head、完整 CI 结果及失败日志补充以 PR #599 的交付说明为准。

本次另实跑 `git diff --check`、现有 secret-file-gate 及其 3 项测试，均通过；gitleaks 8.24.3 扫描候选提交与新增公开文档无泄漏。本环境不运行 Maven／Java／数据库。不部署、不上传微信、不修改路由、依赖或迁移。
