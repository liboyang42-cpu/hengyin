# 云端B 已有消息隐私审核和数据消费者

从 `liboyang42-cpu/hengyin` 的 `codex/mini-foundation-cloud-handoff-20261013` 开始，使用独立任务分支。先核本目录README、COMMON、OWNERSHIP、EXECUTION的B1/B2/B3/B5以及C5/C9中归你所有的事件/指标/广场消费者、items。你不独占代码库，不回退别人的修改；资料已在仓库，无需用户上传。

先核并应用 `candidates/B-ci/candidate.patch`，纠正NPC静态契约旧方法断言，保持当前SQL身份/支付/主题及deny-before-provider要求；生产NPCController归C，不为测试修改生产权限。局部Node通过后提交并查同提交CI，修实际失败，不改CI压错误。

随后按重要程度完成：

1. 实际小程序消息通知/私信消费者的当前已读、单调readTo、删除后迟到回包、发送原请求身份、缺段增量与账号隔离；群聊type4及旧搭子局不属于本包，不开新入口。
2. 核 `candidates/B-route/` 的合同、manifest和补丁，完成失效/私密历史路线卡及摘要投影：隐藏名、保ID、不改原消息/幂等指纹或普通详情权限；查既有客户端缓存，不漏原消费者。候选未实跑，必须取得你的版本验证。
3. 已有举报汇聚、处罚原决定、删除留证与证据最小权限、敏感数据投影/埋点；新增申诉UI及未定处罚政策后置。
4. 现有analytics队列 accepted回执、原批快照、落盘失败、账号晚回包、同键异内容；原指标分母/真实业务源、既有开关前后端同义。推荐/冷启动/实验底层保原项逐一核，找不到消费者标待核，不归App或删除。
5. 实际小程序广场用现有creativesquare列表；沿真实消费者核offset翻页追加重复、时效和降级，不擅自换成未有消费者的专用推荐接口或新卡型。

消息与审核、事件/指标及对应实际客户端文件归你。报名业务提交和原通知触发RegistrationCheckoutServiceImpl归A，原消息投递层归你；给A接口/收据合同，不越界改报名文件。ApiRoamController/RoamMapper归C；只给事件协议，不改漫游源。既有通用审计Service归你，A/C调用合同保持兼容。

参考Matrix/Synapse、outbox、原指标/开关固定源码；只适配规则，不引入完整IM框架、新通知渠道、未知结果盲重发或新依赖。技术断点自主查，不再问用户已确定规则；新产品/权限政策保持原样并后置子项。

持续完成代码、完整调用方、测试和可执行验证。Java/数据库走批准VPS或既有GitHub CI；不可达时完成候选并明确未验，不伪造身份/送达/通过。消息/资格夹具不是实际身份验收。只写本包 `docs/verification/mini-foundation-cloud/B/` 证据及白名单文件；完成提交与draft PR（base交接分支），不合并/部署，附每个原ID去向、来源、源摘要、测试/独立回读、跨包合同和未验范围。不要只交分析。
