# B02 作者保存保真调查与候选补丁

冻结基线：`d551c6babd018d41aec90dad2c0acaf1c534644d`。本 lane 仅完成源码调查、最小补丁和未运行的 Java 回归；不表示 Java 编译、CI、VPS、数据库读回、微信设备或业务验收通过。协调者已追加授权独占 `CmsTopicServiceImpl.rebuildTopicContent` 与其保真辅助方法。未切分支、提交或修改 DDL/依赖/配置/权限/发布政策。

## 确认的缺口与补丁

`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java`：真实入口 `updateTopicByOwner/updateTopicByOwnerV2WithReview → updateTopicByOwnerInternal → rebuildTopicContent`。

原 `rebuildTopicContent` 对缺席字段生成有值的 SQL patch：`normalizeTeamConfig` 把 null 改成 0/4；`publishMode` 缺失改 pro；`selfPlay` 缺失改 0；`xpBudget` 缺失改 3000；`resolveProductType` 在请求和票都无模式时回落经典定向。`CmsTopicMapper.xml/updateCmsTopic` 对这些非空值执行更新，故并非仅内存默认值。非默认存量配置可在保存后被覆盖。现有小程序 `pages/publish/fabu/index.js/_doSubmit` 的真实 payload 没有 xpBudget，注释明确“XP自动分配无需上送”，因此 XP 预算覆盖可由当前客户端触发；其他缺席场景来自兼容旧端/接口消费者，未声称全部当前页面都会遗漏。

在重建开始新增 `preserveOmittedAuthorConfig`：缺席的 publishMode、teamMode/teamMaxMembers、xpBudget、selfPlay 及在 selfPlay 缺席时的关联参数沿用本主题已存配置；产品类型仅在请求和全部票均无 mode 时沿用已有合法类型。显式 0/新值、旧端票 mode 推断、创建默认、全部既有校验继续沿用原路径。未扩充 DTO 字段；`TopicCreateDTO.java` 仅说明此更新合同。未改池开放政策/奖励/价格规则。

新增 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicDraftLegacyFieldPreservationTest.java`，5 个用例走真实 owner-update 并捕获 Mapper SQL patch：缺席配置保留、自由探索类型保留、显式关闭/改值、旧票 mode 兼容、显式坏组队值拒绝。夹具身份 `42/700` 为测试值。测试先于补丁编写，但没有执行红/绿测试，不冒充失败/通过输出。

## 历史线索反证和剩余边界

| 调查 | 精确代码入口与结论 |
|---|---|
| PR12 点赞方向 | `ApiTopicController.topicLike` 的 `legacyToggle` 与 `wantLike` 分支已有，type=0/1 为显式方向，未重做、控制器无 diff。 |
| 草稿体系是否真实存在 | `createTopicInternal` 写 `updateTopicDraftState`，控制器 create 返回 topicId；update 带 TopicUpdateDTO.id；`publishDraftInternal` 的 `clearDraftIfDraft` 原子清草稿并沿用 id。`ApiTopicSaveDraftContractTest`、`TopicDraftPartialSaveGateTest` 已有，未新增同义体系。 |
| 顺序/章节节点 ID | `saveChaptersAndNodes/saveNodes` 与 `compileChapterFlow` 明确重建新数据库 ID，数组顺序写 sortId；`CmsTopicChapterMapper.selectCmsTopicChapterList` 和 `CmsTopicNodeMapper.selectCmsTopicNodeList` 均按 sort_id asc 读。ID 不保证跨保存稳定，前端保存后必须重读。源码没有证实“顺序必丢”，数据库双次重排读回仍待 VPS。 |
| 绑定/规则 | `persistJourneyStoryAfterChapterRebuild` 已用 chapterOldToNew 重映射；`rebuildTopicContent` 对缺席 journeyRules/Story/runtime 保留旧列，runtime/路线重映射；旧端省略分支路线也复制原图重新映射并 CAS，映射缺失则回滚。`CmsTopicRoutePublishPolicyTest`、`TopicJourneyStorySaveTest` 已有对应测试，未宣称本轮已跑。 |
| 发布快照 | `saveRouteDefinition` 专用 updateRouteDefinition 做 configVersion CAS；`publishRouteSnapshot` 只增加 published_config_version，发布图与草稿图分离；不改该语义。 |
| revision | `invalidateApprovedCanonical` 调 `TopicSaleCanonicalMutationServiceImpl.invalidateApprovedCanonical`，存在 canonical draft 时增加 parameterVersion；它不是客户端通用 revision。TopicCreate/UpdateDTO 只有路线 configVersion，没有全局内容 CAS，不能宣称覆盖全部字段的并发编辑保护。 |
| 丢响应 | 已知 topicId 时可用 edit-detail 重读并沿用 update/publish-draft。首次 create 的 HTTP 响应丢失，没有持久 requestId→topicId 回执；重放 create 仍可能产生第二主题。不能按名称或内容哈希猜配主题。需要协调者另定持久请求身份/兼容方案，本批不靠新表补洞。|
| 发布重放 | `clearDraftIfDraft` 保证草稿只能清除一次；已发布重试会收到“该主题不是草稿”，不等于原成功回执重放，需读取原 id 的真实状态。|
| 媒体送检异常误报 | 已撤回：`IWxContentSecurityService.submitMediaCheck` 明确 best-effort；真实实现 `WxContentSecurityServiceImpl.submitMediaCheckOne` 已捕获 provider/inbox/Redis 失败并 ERROR 记录人工复审。仅让接口 mock 抛异常不足以证明当前调用链 bug，未改控制器/审核路径、未保留这类人工造失败测试。 |
| 其他可选配置 | 池开关/关联券等现有整包替换行为未在本补丁改成局部更新；如要更改需核消费者和原政策，不能由该 helper 推断全部 DTO 都已支持缺席保留。 |

## 实际验证与待验证

实际轻量输出：[static-checks.txt](static-checks.txt)：本 lane 三个代码文件 `git diff --check` exit 0；只读解析三份真实 Mapper XML 成功；记录冻结基线中的覆盖表达式和当前发布 payload 未传 xpBudget。XML 解析不是 SQL 执行，静态校核不是行为验收。

未运行 Java 编译/单测、GitHub CI、MySQL/Redis/VPS、HTTP 保存发布、独立 SQL 读回和设备测试。应由协调者在既有 Java CI/授权 VPS 串行执行新套件及 `ApiTopicSaveDraftContractTest,TopicDraftPartialSaveGateTest,TopicJourneyStorySaveTest,CmsTopicRoutePublishPolicyTest,TopicRoutePublishedSnapshotContractTest`，再做同一主题两次修改/重排→独立读回→发布，核新 ID 引用一致、规则不漂移、已发布快照和旧局不被改写。

回退仅撤销新增保真 helper 的调用/定义与 DTO 说明，不能删历史迁移、其他 lane 修改或既存绑定/回执保护。当前交付状态：候选补丁待编译/回归；首次 create 丢响应与全局 revision 明确保留为未解决合同缺口，B02 业务验收未关闭。
