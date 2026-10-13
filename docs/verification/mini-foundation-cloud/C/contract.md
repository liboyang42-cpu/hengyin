# C 包实现合同

起点：交接分支 `codex/mini-foundation-cloud-handoff-20261013`，提交 `e67e72a`；业务基线 `0f0c32f1edc955f6117108daecbb492652212931`。三包以 OWNERSHIP.json 为编辑白名单。2026-10-13。

## 配额身份与调用方

- `AiGatewayService.consumeQuota(Long,String)` 返回 opaque reservation；`refundQuota(Long,String,String)` 只接本次原身份。计数池及既有限额不变；原日 K、nonce R、generation G 必须同一原子预留返回或从该 nonce 收据解析，禁止用当前 G 猜原代次。
- 调用链：Gateway.generate 的普通/NPC配额；PhotoCheckVisionService 的 photo 池；AiCreatorServiceImpl 的 player-draft 池。明确输入违规保配额，确定检测/模型服务故障退；未知/REVIEW_REQUIRED、输出及解析沿用各消费者原规则。
- 持久准备必须先独立提交再触碰 Redis。服务故障先提交 REFUND_DUE 后退款；缺 counter/代次不匹配仅收敛旧身份，缺 receipt 且 counter 存在不得裸减；提供方已启动且无权威结果仅 UNCERTAIN。
- 新 AI domain/Mapper/XML/service/task 与 chengyinhub-admin/sql/ 新 migration 归 C；支付 outbox、公共依赖、RedisConfig、JWT 和 CI 只读。Worker 单条故障不阻断后页，DB claim 短事务，Redis 在事务外，完成以 owner/fence CAS。

## 漫游恢复与奖励

- C 所有者：ApiRoamController、RoamMapper.xml、RoamSessionWriteService、roam 页面、roam-recovery、roam-tile-memory-sync 及其域测试。新增域文件归 C。
- dirty 仅在相同 revision 成功落盘后清理；账号及原 SID 绑定恢复。结束后仅补结束前采集记录，不发新奖励，不改原结算；服务端归属与采集时间/结构校验。
- 既有清缓存动作删除本范围未同步/待核记录，失效飞行中回包；不得删除无关鉴权，不撤销服务端写入。实际漫游清理在 roam 页面归 C；B 的 shezhi 退出登录入口需接漫游 epoch 失效合同，不修改无关鉴权。
- 原券来源幂等/补发方法由 A 提供，C 只接触发点；自报坐标不当真实到店证明。会话写入锁原 owner/SID，再写发现/结算。

## 草稿、玩法及 NPC

- 复用 configVersion 和原请求/session；普通草稿保持章节节点语义。C 原控制器与运行 Service 可修，CmsTopicMapper/PlayTeam/报名资格真源归 A。
- 已有玩法门禁/冻结/归属/次数/结局/设施/HP 与秘密投影逐原项核查；新玩法桥、人工离线账及可达性模拟器后置。
- NPC 生产 NpcChatService/NpcMemoryBuilder/NpcChatRequestMapper.xml 归 C；B 只改旧静态契约测试。保持当前资格查询与历史读取；租约 renew/finish 必须 token+未到期，旧 worker 失权后不再启动 provider 或写终态。当前事实只由服务端原账号/原局生成。

## 验证边界

C-ai 9 个已有文件 base SHA256 和 candidate.patch SHA256 已核，git apply --check 通过，未当作测试绿。只在云端跑 Node/静态；专用 VPS 初始 TCP grants 为空；用户后续提供的 SSH credential 由 root 独立校验，仍等待 TCP 配置，不能据 HTTP refused 判定 SSH 故障，Java/MySQL/Redis 需明确待验，不启动预置云端 DB/Java、不改依赖/CI。GitHub 原 CI 的同提交结果单列。无生产 DDL/调度启用/部署/真机/提供方验收。

## 新使用资格调用合同补充

A 确认已付为 `paymentStatus=2`（1 是 PAYING），报名成功 `registrationStatus=2`；锁序为 activity→registration，与取消活动一致。C 的 ApiPlayProgress 新写入口、AdvancedGameRuntime start/act/completeGate、TopicRouteRuntime 新建/推进，仅原本依赖报名/通行证的链用既有 Activity/Registration Mapper 当前锁读验证 owner/member/原资格和原日期策略；普通/免费报名保持原registrationStatus=2，不统一强制payment=2，原pk3已购订单和原本hasPaid的链才验证已付；历史快照/ending/已完成事实读取保留。ApiPlayRunSession.save/read/clear 沿用本人暂停记录协议，不新增订单或付款闸。C 同域 ApiPlayRunSession/ApiAdvancedPlay/ApiAiNpc Controller 与 Play 域 Service 由 C 单一所有者；CmsRegistration/CmsTopic/PlayTeam Mapper 始终归 A。A 给出的两个接口先导提交已原样引用，默认明确 throw 未接入，真实原券交付仍依赖 A 正式实现。

共享依赖例外：root唯一所有者的先导提交 `4db987a14b53cbda2d11587e89cda5a36150ee34` 只登记新migration元数据 `ci/migration-dependencies.json`，已原样cherry-pick。C不编辑该文件、CI gate或workflow；白名单将这条root作者固定依赖单列。
