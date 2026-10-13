# 云端C AI漫游草稿和玩法恢复

从 `liboyang42-cpu/hengyin` 的 `codex/mini-foundation-cloud-handoff-20261013` 开始，使用独立任务分支。先读本目录README、COMMON、OWNERSHIP、EXECUTION的C1/C2/C3/C4/C6/C7/C8和items。C5/C9的事件/指标/广场消费者由B，本包只改其与你文件相连的既有调用方，不越界。你不独占代码库，不回退别人修改，资料均在仓库。

先核 `candidates/C-ai/` 补丁、manifest、基线摘要、CONTRACT及durable-refund-contract。它们未实跑，不把静态检查当绿。先冻结配额返回身份/调用方合同，再完成：

1. AI原请求防重复计费、原日/原池/原代次配额、nonce及取消、跨日/同日键重建/重复退款/并发上限/TTL；明确服务故障退，明确违规不退，REVIEW_REQUIRED及未知保既有规则。
2. 确定退款的持久预留/待退意图、崩溃和Redis持续故障后的原身份补偿；复用现有Mapper/Quartz模式，不挪用支付资金outbox；提供方结果未知不能自动判退款，过期不能裸DECR。允许测试migration，生产DDL不做。
3. 漫游落盘dirty/revision、账号/原SID恢复、结束后只补原采集记录，不重复奖励或改结算；清缓存全部删本范围本地待核/未同步恢复记录并放弃恢复，迟到回包不能重建；不删除服务端历史或无关鉴权数据。
4. 现有轨迹漂移/异常过滤、到店半径与坐标体系、采样生命周期、原行程结算及徽章/奖励失败补交付；原结算/XP副作用一次，不开旧搭子局。
5. 既有草稿保存、configVersion冲突、章节/节点绑定及原语义回读；不复制其他任务未提交server-draft，不新增作者UI。玩法支持门禁、冻结局、次数/归属、HP/幕末配给、一次设施、章节约束、唯一收场和秘密投影，逐原项核完整配置至运行链。
6. 微信运动密文/appid/时间及同键重试、NPC事实/记忆账号局范围/已解锁剧情/上下文重进/语音原请求及并发租约；到期停止新问，保历史。新玩法桥、新人工离线模式及新UI后置。

ApiRoamController/RoamMapper、漫游客户端和原奖励触发归你；券发放Service/Mapper归A，按A给的原来源合同接调用。订单/报名/PlayTeam资格查询归A，NPCController及上下文归你；保已提交当前资格查询，不另造auth策略。NPC静态契约测试修正归B，不改其文件；提出需要补的断言给B。消息、事件队列及指标归B，只接原数据协议。

参考原表固定源码规则，优先本项目实现，不把localForage/Discourse/游戏引擎或WxJava整套复制进来；现有WeRun校验比参考SDK严格的部分保留。新依赖、鉴权或Redis/JWT配置变化不做。

持续完成代码、所有原调用方、测试和可执行验证。真实Java/MySQL/Redis走批准VPS或既有GitHub CI；访问未具备时仍完成候选和可做验证，明确Java/真实Redis/身份/真机断点，不索要生产凭据或伪造通过。只更新本包 `docs/verification/mini-foundation-cloud/C/` 及归属文件。完成后提交及draft PR（base交接分支），不合并/部署，附逐原ID的变化、源摘要、失败/绿证据、独立账回读、跨包合同和未验范围，不停在分析计划。
