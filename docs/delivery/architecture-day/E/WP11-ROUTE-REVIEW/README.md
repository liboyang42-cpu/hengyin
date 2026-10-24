# E WP11 route-card 只读施工审阅

结论：**当前确有逐项详情查询，未找到等价现成批读；本轮只交审阅patch与影子方案，不切消费者。** 固定数据与同一statement时钟下，候选复用原SQL gate，9列足够原IM投影；任意并发/跨到期时刻下的全行为等价尚不能证明。生产源码、club9文件、team/hangout/写操作均未修改。

## 基线与候选身份

公共基线 `41c7e1caef9fdd3548585f1646e267565ca252d7`，tree `982e685f3f175cf590fee6939b862358b43968a0`；独立目录 `/workspace/hengyin-e-wp11-route-review`、分支 `codex/architecture-day-e-wp11-route-review`。基线已含冻结club修复，未借旧回执声称其已验。本次Git增量只在本交付目录。

`review.patch` 是供root/Mapper owner审阅的**未应用**施工稿，仅提议两文件各新增一项：`CmsTopicMapper.java` 的 `selectImRouteCardTopicsByIds(topicIds,viewerMemberId)`，XML的同名窄读。没有Service hunk、旧详情重构、旧gate改写、schema、新依赖、缓存或运行开关。尚需root分配Mapper源写权并统一验证；本文不授权自动应用/启用。

精确patch SHA256为 `1f653dd22c6a90ed4101619df73a732d52c10a075786013aefa42cf6f4c097b8`。仅在临时index应用该两文件后的审阅tree为 `d2c160f2015e4864d08ef03e1859629d38b9d764`，不是已发布/已测生产源。before/after blob、SHA256、检查清单见 `candidate-identity.json`。嵌入patch的纯空白context行规范为Git接受的空行，避免文档新增行尾空格；普通 `git apply --check`及独立roundtrip均通过。实际交付commit仍只改变docs。

## 真实调用与残余查询次数

当前 `ImServiceImpl.listConversations` 先拿一条原批量摘要、过滤实时业务群资格，再对每个最新消息调用 `projectRouteMessage`（图片摘要直接显示`[图片]`）。投影只在 `msgType=MSG_CARD` 且 extra.cardType=`route` 或 content以`[路线]`开头时识别路线；两种识别都不能擅自删。extra坏JSON仍以原摘要前缀识别，但没有合法引用则不可用。topicId经原Fastjson `getLong`，捕获非法转换，只有正Long才查 `selectCmsTopicDetailById(id,viewerId)`；不能换自造字符串/数字解析规则。

每个有正topicId的路线候选做一次详情调用，即便多个会话重复同topic、消息后来会因status非NORMAL不可展示，也先查后判。正常REST没有列表业务事务，不把SQLSession跨项缓存当既有去重合同。R个这样的最新卡片当前追加R次topic SQL；潜在一次批读追加1次（U个不同正topicId），0候选不查。混合列表仍有原业务群/club批读成本；SQL内部有相关子查询，绝不称所有IM查询或工作量O(1)，没有RPS/耗时实测。

同一投影还用于历史 `messages`、receipt当前回读、send原回执/成功返回；本轮不扩展它们。发送前 `sanitizeRouteCard` 另以viewer=null读同一详情并用 `isPublicRouteTopic` 要求匿名可见，落库仅`[路线]`与topicId，不存主题名称。不得为列表优化改变发送资格/回执/历史存储。

## 原可见性合同：SQL与Java都必须保留

详情SQL严格要求：topic `del_flag=0`；topic无club或club=0，或对应club存在且`del_flag=0`；公开状态 `status=1 AND user_status=1 AND primaryCirclePublicGate` **或** 正viewer为topic作者；最后再过 `clubMemberOnlyGate`。club存在闸只检查删除，**不检查club.status=1**；不能复用club群列表的更强状态要求。

`primaryCirclePublicGate`：非五个主圈/FITNESS等、无圈或模板可绕该圈层分支；其它需主题审查距SQL `NOW()`不超过30天，distinct merchant数3至4，offer status=1、valid_end为空或严格`>NOW()`，当前profile且checked_at同样30天内，chapter.circle_enabled=1。作者预览seam在SQL可绕圈层/公开状态分支，不能由优化擅自取消。

`clubMemberOnlyGate`：club_member_only COALESCE=0、无club/club=0，或正viewer是topic作者、club当前会员(status=1/del_flag=0)、未删club owner、支付状态2且报名状态2的topic票或关联activity票。owner_type=1对owner_id或topic_id、owner_type=2经cms_activity回源，退款/取消不得被放宽。该gate不是可删除的“只有private主题才用”注释。

之后Java `isPublicRouteTopic` 还要求topic非null、status=1、userStatus=1、delFlag为null或0、**clubMemberOnly不等于1**；消息也须STATUS_NORMAL。故即使作者/当前会员/club owner/已付款人能读private详情，IM卡片仍统一不可用。SQL已排除delFlag NULL，不能仅凭Java允许NULL省掉SQL。不要把“不等于1”改成“等于0”或将历史其它值猜成新业务状态。

## 字段、格式、输出集合与时效

候选只选9列并沿原 `CmsTopicResult` 映射：

| 原投影用途 | 必需列 |
| --- | --- |
| 按原topicId关联 | id |
| 原Java公开性复核 | status, user_status, del_flag, club_member_only |
| 原当前路线正文/安全extra | name, img_url, total_mileage, location_count |

member_id/club_id、圈层字段与关系仍在SQL谓词内使用，但无需向IM投影装载。其它DTO字段为null，所以该方法**不是通用详情读的替身**。`location_count` 必须取当前原列，不用publicNodeCounts重算；name/图片/里程/count保留原NULL与类型，不能补默认值或把空name猜成其它标题。

投影BeanUtils复制原message；safe extra只保留cardType、可解析topicId，visible时加当前imgUrl/totalMileage/locationCount。不可用文本统一`路线已不可用`，不能回退会话旧名称/旧extra。list只取投影content，历史/receipt还有safe extra，但均未切换。消息存储原文、requestHash、create/visible序、列表visibleOrderAt/id排序、NULL字段形状不能随批次改变。批读返回顺序不作为列表排序；缺行必须在原位置投影不可用，不从结果列表删除会话或回退另一个topic。

**时效差距阻止当前消费者切换。** 逐项SQL各采自己的statement NOW()/读快照；一次IN查询只有一次statement时钟/一致性读点。主圈reviewed_at/profile.checked_at的30天边界、offer.valid_end到期，或其它连接在两次投影间提交的下架/私密/删除/改名，可能使早批次读与原后续单项读不同。cache=false/flushCache=true只防MyBatis复用，不把MySQL RR快照变成current locking read，也不保证严格撤权截止。本轮不新增锁、冻结时钟合同、lease或后台缓存，不默认接受更早的请求级快照。

精确反例与真实影子方案见 `SHADOW-PLAN.md`。需要root明确剩余“每条当前”与“每请求一次快照/时钟”的既有合同适配，再决定消费者阶段；在此之前继续原逐项安全闸，不拿稳定数据影子相等冒充任意时序等价。

## 已有批读为何不能直接复用

| 现有方法 | 缺失/额外语义 |
| --- | --- |
| selectPublicNodeCountsByTopicIds | 只取节点聚合；不校验topic详情/当前viewer；聚合location_count不是原列合同 |
| selectClubMemberOnlyHiddenTopicIds | 仅一个gate的反集ID；无当前名称/九字段，也无删除club、公开/作者、主圈完整闸 |
| selectPublicCmsTopicList | 模板必须is_template=0；额外可写筛选、排序/分页、无同一作者预览seam，不是指定ID详情集合 |
| selectCmsTopicList / selectCmsTopicById | 管理/原始读，没有同一viewer可见性合同，不能批量裸读后用Java补猜权限 |
| selectPublicCmsTopicById | 单条且缺clubMemberOnlyGate；不能因名字public就代替原调用 |

## 检查、未验证、owner及回退

实际执行仅Git/Python：当前精确blob读取、全部候选XML旧条目定义保留、post-id原详情谓词字节复用、两个既有gate include相同、九字段与原resultMap对齐、空集合1=0；对精确基线临时index `git apply --check`并独立roundtrip，tree一致。还执行docs diff/范围检查、secret-file-gate和有限秘密模式扫描。未执行Java编译、MyBatis/JUnit、真MySQL/EXPLAIN/CI/模型/DDL；“最小可编译接口形状”不是编译通过。固定gitleaks留root最终扫描，未加豁免/依赖。

本轮E只拥有并写本交付目录。未来Mapper Java/XML需root协调owner；未来列表消费者仍只涉及E范围，须在独立候选中经新的测试门禁，不覆盖club冻结身份。所有SQL候选无DDL/迁移/永久正文，回退删未接线方法即可；本轮撤回只删docs，不改运行行为。既有9条route unit、7条scope（其中route是mock）不能冒充真实圈层/付款资格影子验收；现有club5也不是route验证。
