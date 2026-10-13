# story-city 集成分支承接记录

对照来源：`feature/story-city-integrate-0926` 的 `7c5172270f89a4c3e577418218af356fd3a3e921`，以及该 worktree 的未提交改动。承接分支为 `fix/story-drafts-closeout-0925`（PR #1182）。旧目录、分支和暂存区保留，不整支合并两套不兼容的广场状态字段。

| 旧分支工作 | 当前承接位置与验证 |
|---|---|
| `20b38475f` 场次隔离 | `TopicRouteSessionScope` 统一定位；玩家读写接口与小程序贯穿 activityId，省略场次仅在唯一局时兼容。现有 controller/runtime 测试覆盖歧义与跨场次。 |
| `7c5172270` 念头认领 | `TopicRouteRuntimeServiceImpl.claimableThoughts` 读取建局冻结的 thoughtChapters，沿用 `ChapterBlockReadSupport.projectForPlayer` 的节点、条件与标签判据；runtime 测试覆盖未来节点与冻结条件。 |
| 编辑、存草稿、删除撤回广场 | `withdrawProjections` 统一撤回并取消未结审单；新增 `TemplateEditOwnershipTest.editDraftAndDeleteWithdrawExistingMarketProjectionRegardlessOfLegacySyncFlag` 覆盖三种操作 × 三种旧 isSync 值。 |
| 删除草稿不能复活 | `saveOrUpdateDraft` 锁定真源并验证存活、归属；`savingDeletedTemplateAsDraftCannotReviveIt`。 |
| 广场审核快照与来源存活 | `TemplateAuditSnapshot`、`AuditTaskServiceImpl`、`AuditTemplateDecisionProjectionTest`；MySQL IT 另覆盖真实锁竞争和审计失败事务回滚。 |
| 管理员删除投影保留操作人 | `deleteTemplateLibraryProjections` + `TemplateAndMerchantMutationGuardTest`，不再绕过发布服务直接批量删除。 |
| 个人/广场状态分离 | my-list 返回 numeric marketStatus、marketTemplateId、marketPublishStatus、marketReason；SQL 按 owner 匹配且重复历史投影不放大行数。旧分支的 LISTED/PENDING 等字符串合同不再并入。 |
| 编辑页移除自动同步 | 当前编辑页已移除 isSync 开关，明确个人通过后再主动提审广场。 |
| 我的项目、我的节点玩法入口 | 两页共用 `utils/template-market.js`；提交、撤回、上下架基于广场状态，未知状态不提供写入口；成功后回读列表，不翻转个人 publishStatus。 |
| 弱网与重复点击 | 我的节点玩法按稳定 ID 取行、请求在途防重；失败可重试，成功后加载服务端列表。新增页面测试覆盖。 |

本地验证：相关后端 36 项测试通过、无跳过；小程序完整静态门禁通过。页面已在开发者工具核对个人审核与模板广场分栏；实际提审、审核、上下架的生产回读不在此次模拟器检查范围内。

本次仅承接既有代码工作，不代表《预制人生》完整作品、生产迁移、微信上传和真机结局全链路已经验收。既有发布边界见 `story-drafts-closeout-20260926.md`。
