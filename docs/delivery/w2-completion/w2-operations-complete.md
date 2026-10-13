# 原 W2 admin、ops-misc 完成记录

日期：2026-10-13。公共基线：`0f0c32f`。仅处理原两线 21 个编号，新 22 项保持暂停。

本交付完成已批准的源码迁入、通知恢复和事实统计、申诉双方入口与持久恢复、未来权益过滤、据点重建恢复及原凭证/原单入口。**不能据此判定 21 项完整业务验收通过**；以下逐项列出缺实现、所需决策与实际证据。

从指定只读交付逐份 review 源码后应用到公共基线，不导入私有 Git 历史、refs、整包文档或测试日志。没有修改主工作区、共享台账、app.json、全局 token、门禁 baseline、生产配置、NPC AI 配置、活动署名名单或全局 play/publish。

## 所有权与方法合同

- 后台：business 下 recommendation、analytics、world、preset、officialevent、config/role-quota；system 下 category、dict/data、banner、notice。在原 audit 页面挂 community-appeals 子组件，复用既有审核权限，不增加菜单/路由授权。
- 后端：商品公开详情、现有角色额度边界、OfficialBroadcast 领域/Mapper/Service/Controller、MerchantSubscriptionMapper，以及相应测试。仅新增一份通知恢复键 migration，旧 migration 不改。
- 小程序：merchant/decor、merchant/citynode、merchant/index 的局部核验；scene-roam-poi-detail、profile/参与详情/club 的局部核验；shezhi/post-governance；mycanyu、im/chat、club-direct-ledger 的局部入口及相关测试。
- 保留 `publish(OfficialBroadcast, Long)`、`reachStats(Long, Long)`；新增 `findByRequest(String requestScope, String requestId, Long actorMemberId)` 和 Mapper `selectByRequest(String, String)`。
- 后台新增 GET `/business/officialevent/broadcast/by-request/{requestId}`，服务器从当前后台身份派生请求作用域；既有 GET `/broadcast/{id}/stats` 增加按渠道/角色聚合的日志事实。
- 申诉复用 GET `/business/community/posts/appeals`、POST `/business/community/posts/enforcements/{id}/appeals/review`，后者实际参数是申诉 ID；提交携 `expectedVersion`。本人端复用 GET `/api/v1/community/me/enforcements` 和 POST `/api/v1/community/appeals`。

## 逐编号：实现与缺口

所有编号的正常 UI 操作、真实身份、外部渠道和真机验收均未由本 worker 完成，统一由 root 集成实跑；Vue 模板编译/Node 用例仅为代码检查。DB 栏区分真实 MySQL 与 H2/Mock。

| 编号 | 已实现/保留事实 | 缺实现与验收缺口 | 代码与 DB 证据 |
|---|---|---|---|
| F0551 | 原评分查询显示 statDate/createTime/memberId/rankPosition；真实分页、失败恢复与旧请求隔离。 | 尚无生产 run/version/TTL/线上采用回执；未实现新的规则配置。 | recommendation-read-state；真实评分 DB/在线采用未读。 |
| M0025 | 人数与事件次数分列；明示原日聚合起步时间口径。 | 订单/会话轨迹、跨日归因与退款回改未接；需先确定单位与窗口。 | analytics-read-state；原单与聚合作业 DB 未读。 |
| F0550 | 生成时间/无记录/读取故障分开，不将原事件 conversionRate 冒充订单转化。 | 作业健康、渠道到账、源版本及有效期未回读；订单/会话切换未实现。 | analytics-read-state/fields-contract；运行作业与资金 DB 未读。 |
| F0559 | 世界目录展示真实公开/停用/未知和更新时间；读取失败可恢复。 | 城市季、任务引用、发布版本/已售实例冻结未接；目录更新不证明奖励生效。 | config-read-state；公开筛选源码已核，真实 DB/UI 未验。 |
| F0560 | 路线配置失败/未知可恢复，展示更新时间与消费边界；现公开查询/站点存储保留。 | 小程序选择→独立草稿复制→引用重映射→来源冻结尚未由本 lane 接入；全局发布文件归其他 owner。 | config-read-state；原配置/草稿 DB 语义回读及正常 UI 未验。 |
| F0561 | 风格配置失败/未知可恢复，真实上架状态与更新时间，不虚称已采用。 | 草稿实际渲染、来源冻结及资源失败回退未由本 lane 接入；全局发布消费者需 root 汇总。 | config-read-state；真实渲染/旧局影响未验。 |
| F0554 | 现 UI 的 maxThemes/maxEvents/maxCoupons 0–99 边界在控制器落实，正数 ID 校验；null 继续不更新，原 QuotaService 消费保留。 | 占用量/配置版本/历史快照未返回；maxNodesPerTheme/maxCityNodes 不新增未知上限。 | UserPermissionControllerTest、role-quota-migration-contract；真实创建/存量资源 DB/UI 未验。 |
| F0578 | 字典/类别失败可重读，首次类型读取异常可恢复；隐藏类别 status=0 编辑不会变显示。 | 每个配置消费者/缓存版本/刷新时间未返回；未修改 Redis 或固定业务规则。 | config-read-state；实际客户端刷新与 DB 未验。 |
| F0579 | 横幅公开展示与公告保存/通知推送分列；失败不冒充健康空列表。 | 角色受众/排期/有效期没有完整合同或消费者；公告玩家入口未确认。 | config-read-state；对应客户端/排期 DB/UI 未验。 |
| F0513 | 数值原批次 ID 正确读取；同主体 requestId 持久唯一、同 key 异参拒绝、竞争插入回原批次而不再次 dispatch；刷新只查原提交，不重发。 | 无请求号的旧消费者保持兼容，不能宣称具此幂等；目标预览/同意名单未返回，真实通知未发。进程在插入后退出时，原批次可能仍待处理，查询不会擅自重发。 | 官方通知前端用例、OfficialBroadcastFactsTest、W2OperationsMySqlIT：专属 MySQL 重放与并发唯一；真实渠道未验。 |
| F0514 | 修复 notify 返回 false 仍写成功标记；按 role/channel 读取 attempted/recorded_success/unconfirmed/clicked，历史总数保留且不叫去重人数。 | 现 notify 是多渠道 fanout，“返回 true”不独立证明微信接受；历史成功标记也不回填为微信送达。完整目标分母/频控跳过/已读/逐接收人详情未持久化。 | OfficialBroadcastFactsTest、OperationsMapperFactsTest 的 H2 聚合；实际微信/同意/目标 DB 未验。 |
| X040 | 真实 claimId 投影为结算申请引用；现 contact 入口携原核销/结算引用，缺号不造号。 | claimId 不是企业打款批次/到账。争议状态机/期限及更正未实现；双方同笔金额/归属/平台批次需真实回读。 | club-direct-ledger；仅源码与合成账目，双方真实 DB/客服处理未验。 |
| F0520 | 同一个 Mapper 给权益列表及额度消费者过滤未来 start_date；沿现 null 起点和 end>=now 语义。付款回调与真实授予回读分开，iOS/能力未知不可付。 | 当前本人接口只返回生效权益，未来/到期历史完整列表和授予来源/原支付映射未提供；过期存量资源政策未定。未进行真实付款。 | merchant-commerce-checkout、OperationsMapperFactsTest 的 H2 时间投影、MerchantCommerceServiceTest；真实支付/授权历史 DB 未验。 |
| F0504 | 商品已上架/未删且门店启用/审核后才可读；商品/SKU 公共字段投影、过滤 HTML 和外来 SKU，现下单重校保留。 | Flutter 消费端不在本工作树；图片错误替代、客户端兼容性与旧页下单尚未实跑。 | ApiProductDetailBoundaryTest；真实详情/下单 DB 与 Flutter UI 未验。 |
| M0070 | 商家/profile/参与详情/club 核验异常保持 unknown，原业务键锁定；202 保留待确认；据点核销无明确 couponGranted 不称已发券。 | 原账当前缺可自动关联“本次写请求”的可靠回执；局部锁不能证明跨设备幂等，页面回收后的扫码原键恢复仍未覆盖全入口。离线消费试点未实现。 | verification-*、write-action-workflow、merchant-station/scope、citynode 等；真实券/库存/多设备 DB 未验。 |
| F0494 | 本人参与记录直达原报名凭证；浏览不写签到，完成与核销状态独立。 | 真正签到、退款/过期/他人凭证拒绝与履约结果回读未实跑。 | mycanyu-refresh/code-type/deeplink；真实原报名与核验 DB 未验。 |
| F0502 | 在 POST 前持久保存仅身份/据点编号的待确认标记；组件重建先 GET 同一据点，明确 completed/needRedeem 才解锁，保存失败不发写请求。 | 照片对象/内容真实性、真实定位、R5/高价值奖励前置未闭合；跨设备恢复仍需服务器事实。 | remaining-scene、五种 citynode-validation、storage-failure 用例；真实任务/发券 DB/UI 未验。 |
| F0521 | 设置页本人处置/文字申诉；原 audit 处理方能展开原理由/规范版本/冻结证据，沿已有 UPHELD/REVERSED 与 CAS。双方重进保留原案待确认，只回读、不重发。 | 附件消费者未提供；原处置/申诉/通知/解封及跨身份拒绝须真实两方 HTTP 和 UI；读取失败不表示无处置。 | post-governance、community-appeals-readback；真实本人/处理方 DB/UI 未验。 |
| F0413 | 系统结果卡标生成时记录；当前原单导航只取原系统卡 action，拒绝未知发送者及站外动作，事件 URL 不能替换原动作。 | 缺 action 的历史卡不造回读；撤回/脱敏政策和原单退款/取消实跑未验。 | im-uiux-recovery-contract；真实系统签发/原单 DB/UI 未验。 |
| X037 | 复核并保留既有双方启用/未删、归属、自邀拒绝与首次条件写；不使用本机缓存当奖励事实。 | 注册 N 天窗口、互邀/长环、不可变绑定时间/来源和奖励资格尚未实现；需要具体政策。 | MemberInvitationService/Mapper 源码复核；没有运行新策略或真实奖励 DB 验收。 |
| N003 | 仅在原后台推荐页呈现试验资格、来源/停止/回滚待确认合同，不增加玩家 UI 或启停按钮。 | 没有实际分组/合格曝光/护栏/停止/回滚实验；样本不足不能下结论。 | 模板与现评分用例；无真实实验、曝光 DB 或运营指标。 |

## 最小决策问题

这些只阻塞相应子链，不否定已完成的常规修复。

1. **X037：注册后多久可绑定、是否同时拒绝任意长度邀请环、何种合格绑定发何种奖励？** 当前首次绑定不限制注册天数。建议先确定一个注册窗口，按服务端条件写与不可变来源记录拒绝互邀/环；奖励规则定好前不新增发奖。
2. **X040：哪些状态可争议、有效期限、金额更正怎么记账？** 当前只有原核销/结算引用与客服核对。建议保留原行，待规则明确后采用可审计反向/更正分录，不改历史金额。
3. **M0025/F0550：按会员、会话还是订单计转化，归因窗口多久，退款是否回改？** 当前只有日聚合事件计数。建议先冻结一种单位及窗口，并联原单，再显示业务转化；不让现事件比率冒充到账。
4. **N003：试点城市、新作者定义、最低样本/观察窗口、停止阈值与负责人？** 当前没有运行实验。建议前置曝光/分组/归因可回读后做限定城市试点，未达到样本不扩大。
5. **F0559/60/61：后台配置仅供新草稿复制冻结，还是作用于已发布/已售实例？** 当前目录/预设不证明玩家已采用。建议优先新草稿复制与来源冻结，保留已售实例。发布消费者由相应 owner 接入。
6. **F0579：需要哪些角色受众、排期和有效期，以及公告玩家入口？** 当前横幅按显示类型/启用过滤，管理公告不等于玩家通知。建议先明确一个真实展示入口再接角色/排期，显示类型不充作角色。
7. **F0520：到期后已建资源可读/编辑/上架的规则是什么？** 本轮只统一既有生效时间过滤，不删除资源或补发。建议保留历史资源，具体新建/发布资格由现消费者校验，额外限制需明确。

## 验证记录

- 后台 Node：9 份相关文件，41 tests，41 pass，0 fail；不跑集成全量。
- 小程序 Node：20 份相关文件，170 tests 全过；随后新增未知枚举负控，post-governance 6/6 单独复验。随后核验 202/空回包业务键锁、原引用/核销状态布尔负控复验：23/23 与 10/10；俱乐部调用方收尾复验 27/27；合计覆盖 172 个用例，新增后未重复整个套件。
- Vue：隔离临时目录使用项目已声明的 vue-template-compiler **2.7.16**；14 个变化模板编译，0 errors；未改 package/lockfile。
- VPS Java 首轮：测试树 `cc5b106c919beb7ae092a5916ce933446eddb5ea`，39 tests / 0 failures / 1 error / 0 skipped，exit 1。唯一错误是 H2 对 camelCase SQL 别名的 Map 键映射；已将新字段统一为 `recorded_success`，SQL/UI/测试一起修正。
- VPS Java 复验：**39/39，0 failures / 0 errors / 0 skipped，exit 0**。UserPermissionControllerTest 3、ApiProductDetailBoundaryTest 3、OfficialBroadcastFactsTest 4、OperationsMapperFactsTest 3、OfficialEventContractTest 16、MerchantCommerceServiceTest 9、W2OperationsMySqlIT 1；七类报告已独立回读。日志：`/w2/operations/java-b3d5b241.log`。
- MySQL 首轮：W2OperationsMySqlIT **1/1**，0 failures/errors/skipped；任务专属 MySQL 验证 migration 两次重放、同主体同请求两个并发连接仅一次插入、ADMIN/MEMBER 与不同主体隔离。仅本测试随机请求行清理，没有发送通知。
- 复验源码树：`b3d5b2417859a2b5d58b14a539804d92f2c4e68f`；后端压缩归档 SHA-256：`48d9e4d2b766ed83aeb3caad93e9929d5422439ea3ec93b832804d65d6acd843`。只传 pom 与后端模块，无 Mac bind mount。
- Java/IT 仅专用 VPS，同一 `flock /tmp/w2-java.lock`，Maven 512m、Surefire 384m；没有本地 Java/Docker 服务。第一份 273 MiB 整树上传已获 root 授权精确停止，仅本任务 Docker CLI，exit 143；改为约 6.8 MB 后端压缩包并独立核验 SHA。
- 正常 UI 截图、真实身份/跨身份业务、真机、真实微信发送/接受/送达、付款/资金、更正、OSS 写入、生产部署均未执行。

相关 Java/IT 在专用容器及同一锁内运行的完整 Maven 命令（数据库变量由 root 注入，不写入源码）：

```sh
MAVEN_OPTS=-Xmx512m mvn -q -B -pl chengyinhub-admin -am -Dtest=UserPermissionControllerTest,ApiProductDetailBoundaryTest,OfficialBroadcastFactsTest,OperationsMapperFactsTest,OfficialEventContractTest,MerchantCommerceServiceTest,W2OperationsMySqlIT -DargLine=-Xmx384m -Dsurefire.failIfNoSpecifiedTests=false test
```

## 迁移与提交门禁

新增：`chengyinhub-admin/sql/migration_20261013_w2_official_broadcast_request.sql`。nullable request_scope/request_id 与唯一键保留 legacy NULL 兼容；scope 服务端赋值、JSON 忽略，历史行不伪造请求号。

**采用本后端前须先在相应授权环境执行这份 migration。** 新 Mapper 读取这两列，未迁移的数据库不能直接切到新后端。生产执行不在本次授权内。

提交使用 root 批准的临时 hook 副本，只将 Java 编译执行位置改为专属 VPS 容器与同一锁；对准确 git write-tree 全 compile。原 endpoint/Linux、JS/JSON、既有迁移/生产配置保护与 DS 门禁完整保留；不改共享 hook/config，不使用 no-verify。

- 原 hook SHA-256：`74a873bf05614e1fcd4acdc6462b2bbd84bb069010ed35c57707832d64e5aa5d`。
- 临时 hook SHA-256：`59cfacd92ed281b70e99be8144295248def0f136e3684e42655fdc70b15aa4b9`。
- 源码：`3ae918ff45c75dab5ca62443274a90187606d676`，feat: 补齐原W2运营通知申诉与权益恢复。
- 边界收尾：`97acb8307fa8c6ff2ac45470b657a213fe36afe2`，fix: 原单引用与核验未知状态拒绝伪终态；JS/JSON/DS 门禁通过。
- 源码提交门禁 tree：`fbc19fb318154ebab5121d2d64fe8beccff106c4`，后端归档 SHA-256：`c3a352f8fb28570c21fd76270509f226ce857ac929cd288e7716b41200dd371e`；专属 VPS 全 compile 及 hook exit **0**，DS 门禁通过。
- 共享 hook SHA 收尾复核与原值一致；未更改共享配置。

未 push、未合并、未部署。完整上线验收仍由 root 汇总各 lane 消费者与真实运行证据，不能以本报告或测试替代。
