# B：完整内容采用、派生与绑定源码交付

基线/parent：`fa5c263440573992701263d6bee60830844a49b8`。施工目的按协调方案 `ceb4e5f923091dc0fc5e5b6b7670d60c27bff85f`。状态：SOURCE_ONLY / ALL_TESTS_NOT_RUN / NOT_ACCEPTED。

## 已实现的具体闭环

- `POST /api/template/adopt-draft` 只需 JSON `{"originalTemplateId":公共库ID,"scope":"MERCHANT"}`；个人可省 scope。仍先验证登录和原 PROJECT_MANAGE/owner。采用时客户端标题/优惠券将被覆盖，不再先校验这些废弃输入。真实来源必须可见，来源直接/条件优惠券的原拒绝保持；真实媒体、玩法、能力校验保持。
- 首次采用完整复制来源，清除的是客户端的清空**指令**，不是内容。客户端 medal/interactionType 也不作为来源事实。已关联个人原单的库行必须有相同 id/owner 的有效已审核 canonical；按该关联补库表未保存的互动语义及奖章。个人列表未上架不等于该已审核公共投影已下架。历史直接入库且没有 canonical 关联的行维持无该元数据，不猜值。
- 编辑已有副本先锁 owner 行，来源 ID 保留；不能擦成0或换库。秘密字段按原既有补回规则从自己的已锁行读取，奖章保持自己的原值，不重新追随 live 库；本地也缺必要事实则沿原 validator 拒绝。新建兼容回填路径仍检查公共可见性，原 DB 异常不吞。采用后可修改自己的内容，不能据此改来源作者。
- `interactionType` 增补到发布 DTO/个人字段白名单。公共 DTO 的同字段只读，库 Mapper 经既有 `member_template_id + member_id` 读取 canonical 字段，**没有新增库列**。保存市场投影/再读/原 TemplateAuditSnapshot 的既有 interactionType 字段一致，原单 owner 不符返回缺失、采用拒绝。后续 G 发布仍使用原个人内容与审核/发布快照，不增加版本表。
- 未改复制器、玩法引擎、鉴权政策、资金、媒体权限、发布/运行 owner 或任何 migration。

## 字段往返及现成调用者

个人持久化经 `CmsMemberTemplateMapper`；公共持久化投影经 `copyCanonicalToProjection → CmsTemplateLibraryMapper`；采用经 `saveOrUpdateDraft(adoptSourceOnly=true)`。完整白名单保留：标题/封面/说明/人数/地点/材料/时长/难度/规则、merchantGuide、validationMethod/sensorType/sensorConfig、题面/选项/答案与图片音频/optionMedia、preferenceJson、advancedConfigJson（含 interactionRules/resourceSlots/工具/原效果）、剧情/提示/解析/拍照要求、audioUrl/audioDuration、feedback/coupon/奖励JSON、奖章、分类。VM切换原本清理不适用 sensor/preference 的规则不变，不称“不适用字段必须原样保留”。

草稿/发布：`ApiTemplateController.draft/publish`；后台 create/update：`CmsMemberTemplateController → createAdminTemplate/updateAdminTemplate`；据点：`ApiMerchantNodeController.submitTemplate → saveMerchantNodeTemplate`；市场：`submitTemplateToMarket/updateTemplateFromLibraryProjection`。前三条编辑路径均不从公共来源替换已有副本答案；据点仍用原个人表及生命周期规则。市场 projection 的 interactionType 是关联只读字段，不能用后台 JSON 独立覆盖。

原 `clearFields` 调用者（fa5c）：`chengyinhub-xcx/pages/publish/utils/publish/template-author-fields.js` 的 CLEARABLE/clearFields；`pages/publish/temp/index.js` 的 prepareFormData/saveDraft/sendData；据点 toNodeTemplate 本来就删除该 marker。服务端 `prepareClearFields → CmsMemberTemplate.clearFields → Mapper update`。本补丁只在**新建来源副本**分支丢弃客户端 marker；合法后续编辑的 rewardRulesJson/merchantGuide/sensorType/sensorConfig/storyImg 白名单、传感器必需项拦截和 SQL NULL 更新全部保留。原 TemplateClearFieldsMySqlIT、TemplateEditOwnershipTest 和小程序 publish-template-tools/v51 roundtrip 源码保留；不修改两端文件。

## 给 D 的两种采用粒度及 ID

1. 小游戏公共目录 `/api/template/list` 的 id、详情 `/api/template/info?id=...` 的 id、preset node.playTemplateId：均为 **cms_template_library.id**。详情秘密仍剥离，不能拷公开 JSON 代替采用。`POST /api/template/adopt-draft` 返回 AjaxResult.data 的 Long 是新 **cms_member_template.id**。用这个返回值读 `POST /api/template/myinfo`（同 scope），编辑/发布传 `id=个人ID`；节点 `templateId` 也传个人ID；来源 `originalTemplateId` 保持库ID。库列表原9字段投影未扩散秘密，互动语义可在详情与采用后个人内容读取。
2. 整套主题：`POST /api/template/topic-template/use`，原表单参数 `id=cms_topic.id&scope=MERCHANT`，成功 `data.topicId` 为本人/商家 owner 的新草稿主题。不传 sourceMemberId，不把小游戏库ID传此接口。原 `selectTopicIsTemplate → useTemplateForMember(copyType=3, server memberId) → CmsTopicServiceImpl.copyCmsTopic → TopicContentCopyServiceImpl` 保留。新主题重置审核/上下架/结算状态，内容复制由同一本地事务完成，不是已发布或已开局事实。
3. 原 topic copier 复制章节/节点、主题数值规则/剧情/分支配置、协作者；跨 owner 个人模板用既有 `copyForContentOwner` 派生，同一操作同源模板只派生一次；同 owner 无需重映射可共用 ID，含 NPC/sys-node 引用则强制派生后按新章节/节点/NPC ID 重映射。templateBindings.templateId 和 slotDigest/场景资源绑定保持对应，不能把模板库ID塞进去。入口注释虽写“票种”，当前实现此路径没有复制票种调用，不能据此称旧销售/票务/运行实例一起复制。

整主题现成测试源码已读取并保留：`ApiTopicTemplateMerchantOperatorScopeTest`、`TopicCopyTemplateOwnershipTest`（跨 owner/同 owner、资源绑定、伪造绑定拒绝、前置节点与写失败）、`TopicCopyNpcRemapTest`（NPC/章节剧情、sys-node、工具计划来源、READY音色及在途音色隔离）。无需另造主题复制实现；没有把32/42注册key数量当主题复用证明。

## 配套源码与验证界限

增补采用 endpoint 的最小 JSON/真实 scope；首次采用不被 clearFields 清掉、不可夹带 interaction/medal、canonical 缺失/异主/删除/未审拒绝；副本本地秘密回填/live源不可读/出身防替换/合法后续清空；interaction 原请求/市场快照/序列化只读；完整实际 MyBatis 内容往返、偏好题组、资源槽、来源变化不追随和异主拒绝。既有题面媒体/音频、sensor/filter/preference、copy/NPC、coupon/secret/owner、审核及 clearFields 全部原 case/断言保留。直接使用公共库完整 SELECT 的三个轻量 fixture 补其 canonical 查询依赖字段，不把 H2 当 MySQL。

本轮没有执行任何 Node/Python/Java/MySQL/HTTP/CI 测试，没有外呼、DDL或公共写入。只读源、编辑、diff和摘要不记为行为通过。Git fetch 一次在继承 proxy:8080 连接失败，原树未重建/覆盖；本片由公共精确字节重建 base/post，并以完整 patch 交 root 扫描提交，不能冒称新公共 head。实际库存/上架覆盖、页面端到端、MySQL执行、G最终组合冻结一致性及真实运行实例仍由同源统一验收；旧绿色不转签。已存在带非空 interactionType 的旧在途市场审核快照可能因当前新增真实字段不匹配而需重新送审，不能自动认可旧快照。
