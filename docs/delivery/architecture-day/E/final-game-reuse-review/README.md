# E 最后集中审查：完整游戏作者复用与券归属收口

源码完成，所有编译／测试／数据库／HTTP／故障／CI 均 NOT_RUN。
父：90e18550ae75e83641e85bf4bb218f08922c7d3e；tree：55ad70d6e20746d7b47ce368ca1d08f2014b59ff。
分支：codex/architecture-day-e-final-reuse-coupons。

执行器首探 No usable execution environment，恢复后原工作树无改动；遵协调要求用原代理正常 fetch 公共90e一次成功，没有修改代理或凭据。新 worktree=/workspace/hengyin-e-final-reuse-coupons，所有编辑 preimage 已核对该公共提交的精确 blob。fa5c 只作历史，不作本补丁输入。

## 确认问题及最小复现

原现成政策由 ICouponRewardAuthorizationService 声明：作为奖励的券必须属于当前内容发布者；RewardRulesValidator 原注释也明确写入口必须检查归属，纯 validator 只检查形状。

90e 中可达差异：
- ApiTemplateController.publish/draft 只检查平铺 couponId；rewardRulesJson 经 shape validator 后写入。例如本人提交 title/validationMethod 等合法内容、couponId 为空、rewardRulesJson=[{outcomeCode:COMPLETED,couponId:其他作者券}]，原 service 没有调用该归属闸。
- POST /api/template/topic-template/use {id,scope} → copyCmsTopic(copyType=3,memberId=已授权owner) → TopicContentCopyServiceImpl → CmsMemberTemplateServiceImpl.copyForContentOwner。原 BeanUtils 完整复制 couponId/rewardRulesJson 和审核态，未检查目标 owner 是否可用这些券；与已拒绝携带来源商家券的 adopt-draft 不一致。
- 持久旧模板重新进入正式主题发布时，普通无 themeRuntimeConfigJson 分支提前返回，原 E 引用校验没有补这项资产归属。

本包复用原归属服务，不改变券发放、核销、库存、奖励金额或优惠政策：
1. 原 TemplatePublishServiceImpl 的发布／草稿／商家据点写入、后台创建编辑、个人再提审、广场提交／编辑／重上架均在写入前核对平铺及条件券；对 patch 使用原“省略=保留、clearFields=显式清除”合同。下架不新增券检查，旧引用失效不阻止撤回。
2. copyForContentOwner 在创建个人内容副本前按目标原 owner 校验；原 topic/chapter/node/NPC/资源绑定复制及事务／谱系仍原实现。整个主题复制拒绝靠原事务回滚，不清空字段或转嫁券。
3. 正式 ContentGuard 使用已有 CurrentContent.templates()，按每份模板真实 owner 而非 topic creator 复核，覆盖普通题、模板来自合格商家及无 runtime 配置的分支。旧 preview 不新增该数据库读取，不注册声明、不启用玩法。
4. RewardRulesValidator.couponIds 只复用原形状规则提取正 ID、稳定去重；不修改输入/输出配置，不增加资源授权或新引擎。公开 Controller、DTO、service interface 和响应签名均不变。

新依赖的实际源方向：三个原生产 owner → 现成 ICouponRewardAuthorizationService → 原 CouponRewardAuthorizationServiceImpl → SmsCouponServiceImpl → SmsCouponMapper。后两类实际只有原 mapper 依赖，没有新增回调 Template/Publication 的 DI 边；不是运行启动证明。

## 完整后端链核对边界

| 链 | 90e 实际源结论／本包动作 |
|---|---|
| 整主题采用 | ApiTopicTemplateController /use 在认证及原 Merchant PROJECT_MANAGE 后派生 owner，copyType=3，返回 topicId；CmsTopicServiceImpl 委托原内容复制 owner。章节／节点／NPC／templateBindings ID 重映射和同 owner 共享条件保留；只在个人模板复制漏斗补券资源归属。 |
| 小游戏库采用 | ApiTemplateController /adopt-draft → saveOrUpdateDraft：originalTemplateId 为 library ID，服务端从可见 source 复制完整内容，canonical 关联核对 memberTemplateId/owner；新返回个人 ID 才是 node.templateId。旧个人副本编辑保留其原 lineage，秘密／奖章从自己已锁原副本补回，不追随 live source。B 的这些实现保留。 |
| 字段／秘密 | DTO interactionType、advancedConfigJson、题面与答案、反馈、媒体、传感器、preference、剧情／hint／answerReveal、音频、资源槽等原 copy 与 Mapper 字段保留；/info 非 owner 仍 TemplateSecrets.strip，个人读取仍原 owner/scope。未扩公开字段或返回答案。 |
| 原后台与商家写口 | CmsMemberTemplateController → create/updateAdminTemplate、submitTemplateForReview；CmsTemplateLibraryController → updateTemplateFromLibraryProjection；ApiMerchantNodeController → saveMerchantNodeTemplate，沿实际 owner。无需新 caller 参数，已在它们原 service 漏斗补检查。 |
| 正式发布／审核 | TopicPublicationService 原 tag/clue 派生 → 最终 current declarations → freezeForSubmission；TopicBundleReviewService 当前 topic/chapter/node/排序模板锁及 hash 对比、applyDecision 仍原实现。批准 publishApprovedRouteSnapshot 不再派生；三参 freeze 最终声明比较、全内容 snapshot 及普通直写发布桥均保留。本包仅增加同投影券校验，没有修改 G producer。 |
| 运行与前端 | A 的实际运行消费、GameSession/Advanced/Journey、资金和 root CI 未改。按最新移交，所有 xcx/ui 由 D 独占，本包无前端编辑；不替 D 签署 renderer/session/UNKNOWN journal。 |

前端只读交接（均来自90e，无 diff）：pages/template/index.js 的 /topic-template/use；pages/publish/temp/index.js 的 library adopt→个人 /myinfo 回读；pages/publish/utils/publish/template-binding.js 的个人 templateId／slot 身份；后台 membertemplate/index.vue 的 buildTemplatePayload 无损保存。D 接续这些接口，不用新 alias 或重写登录。

## 测试 SOURCE 与原场景保留

- 新 TemplateRewardOwnershipTest：16 个 unit 源场景。覆盖各真实作者 service 入口、固定 owner、条件券／平铺券、数据库读取异常、合法全字段保存、省略与 clear 的差异、整主题复制漏斗、同 owner 强制副本、规则纯提取、匹配 review 不免除归属及下架不受阻。
- TopicPublicationDeclarationsTest：原32全部保留，追加3，现35。普通无 runtime 内容仍拒外部条件券；允许模板实际 owner 的合法券而不按 topic owner 猜；旧 preview 无额外查券／锁读。
- TopicContentCopyMySqlIT：原25全部保留，追加3，现28。跨 owner 平铺券／条件券拒绝使完整复制事务回滚；同 owner NPC 强制副本保留合法券、原 library lineage 和 source 不变。旧所有测试方法及断言未删除或改写。
- 原 fixture 仅新增显式 installCouponOwnership 接缝给这三例：在原 UUID scratch 中读取 base_schema_20260818.sql 的原 sms_coupon CREATE 原文，真实 SmsCouponMapper/Service/Authorization，未 mock 核心资产规则。其余25例原 schema/装配与条件不变；新快照额外包含实际券行。没有新生产表或 migration，没有执行 DDL。
- 已读相关手工构造模板 fixture：无券／XP／文案继续不需要伪造 coupon 数据；来源券采用拒绝在原 guard 前生效。本包不加 nullable Autowired、空依赖 fallback 或全放行 fixture。
- root 须将原 copy 真库声明25→28；新 unit16 和原声明32→35纳统一 Java 收集。共享 registry/workflow 由 root 唯一接线，本包未改。

## 交付与限制

owned.patch.b64 是仅8个代码／Java fixture文件的完整 full-index patch；其 decoded SHA256、各输入及后像 SHA256 在 source-evidence.json。含此文档／patch／manifest 的总 commit diff 是另一范围，不能与 code patch 混称同一 hash。

只做源码读取、编辑、身份／diff格式与有限新增源码秘密模式扫描；全量 gitleaks NOT_RUN，由 root 集成扫描。上述字段／调用／拒绝是源证明，不是测试 PASS。未运行 Java/编译/Node/Python控制/MySQL/Redis/HTTP/故障/容器/CI；不继承90e或旧6fcd绿色。

未改变历史已开局、runtime 消费、原奖金／核销／发券动作或其他迁移。真实供应商、设备、授权 HTTP、运行一致性、数据库事务／清理及容量仍需最后统一验收；不宣称全游戏／全治理已经通过。生产 DDL、部署、微信及真实收费调用均未进行。
