# Stranded private work: cand-be

Source: private `feature/w2-release-without-cloud22-20261013` (tip `8472ebae7b`), merge base `8a180437d1`. Carried as a per-file 3-way merge onto `integrate/master-port-20261019`.

| result | files |
|---|---|
| identical | 0 |
| clean | 136 |
| merged | 37 |
| conflict | 20 |
| add_conflict | 0 |
| delete | 0 |
| delete_conflict | 0 |
| reserved | 0 |
| binary_conflict | 0 |

## Files with conflict markers (resolve all; `git grep -n '^<<<<<<< public'`)

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiClubController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamShareController.java`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/AuditWorkbenchController.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/mapper/ShareSnapshotRunSessionMybatisIntegrationTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationEquityMybatisIntegrationTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/SmsCouponDisabledTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/ImServiceImplTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/business/util/JourneyReviewTextsTest.java`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamShareSnapshotConcurrencyMySqlIT.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/AccountDeregistrationMapper.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AccountDeregistrationServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AuditTaskServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsActivityServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ImServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponHistoryServiceImpl.java`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/RoamShareSnapshotService.java`
- `chengyinhub-system/src/main/resources/mapper/business/AccountDeregistrationMapper.xml`
- `chengyinhub-system/src/main/resources/mapper/business/RoamShareSnapshotMapper.xml`
- `chengyinhub-ui/src/api/business/auditWorkbench.js`
- `chengyinhub-ui/src/views/business/audit/index.vue`

## C00-reserved files (not edited; proposed hunks under `c00/`, deliver via CY-HANDOFF)


## Source commits (provenance; includes commits whose changes fall outside this carrier's area)

- `bc1b961d94` 10-12 02:32 fix: 删改站点后保存草稿不再被章节到达关系闸卡死
- `e89823a258` 10-12 03:34 fix: 主题编辑页按稿重排章节规则、额外站点改在故事流标记、弹层灰底铺满
- `83604f2fb7` 10-12 03:50 fix: 玩法库先列横滑模板卡片，章节规则行内输入与故事流标记宽度修正
- `b40e1855fc` 10-12 03:59 fix: 章节规则分区移到章节名称之后，无封面玩法卡显示方块占位
- `c63790b0ef` 10-12 08:31 fix: 线索证据不随后续效果撤回，他队回包不含私有线索正文与图片（X008）
- `866dcc0289` 10-12 08:26 feat: 俱乐部直付计酬台账行显示平台结算单号并补争议客服入口
- `4972518b05` 10-12 09:00 feat: 传感器自报成绩只发探索值与徽章，开表发服务端挑战身份
- `9840bb060d` 10-12 09:01 feat: 传感器玩法逐条声明校准/证据/降级/前后台规则，提交带挑战身份
- `8744a2a76a` 10-12 09:01 feat: 提现申请固定单独同意文本版本并新增本人提现回显只读接口
- `be3ff58726` 10-12 09:01 fix: 反应速度显示测量单位，切后台整次作废并重新开表
- `4058d2d308` 10-12 09:02 fix: 倾斜弹球补校准说明与传感器不可用提示，切后台中断不计成绩
- `3204ad3d14` 10-12 09:02 fix: 精准停表明示误差判定口径，本机停表数标为娱乐成绩
- `0f1ad5d1f3` 10-12 09:02 fix: 安静保持显示本机相对阈值与校准状态，拒绝麦克风给授权替代
- `2af1913b24` 10-12 09:02 fix: 喊声挑战显示相对音量与舒适提示，拒绝麦克风给授权替代
- `867653c867` 10-12 09:02 fix: 指南针接校准/精度/磁干扰状态，来源不足时明示
- `da9ce2debc` 10-12 09:02 fix: 拍照旧兜底放行不再标成照片验收通过
- `2a8e6b277f` 10-12 09:02 fix: 扫码任务把静态节点码标为任务码匹配
- `dd8e9ae901` 10-12 09:02 fix: 3D 徽章资源超时与静态图失败都保留静态图、名称与描述
- `bd77bdd9c3` 10-12 09:02 fix: 暂停入口按现规则说明只停本机用时
- `55904f0401` 10-12 09:02 test: C1 传感器小游戏可信边界与降级契约
- `9ebc5d9dd6` 10-12 09:02 feat: 账户收益页标明提现渠道为平台客服并回显可提现额、授权版本与原申请状态
- `95fe2d8319` 10-12 09:02 fix: 提现表单可提现余额与全部提现改按服务端可用额，不再用总余额
- `9f96c524fc` 10-12 09:03 feat: 作品图/联系二维码与门店NPC头像按当前版本送审、违规按字段处置(F0435/F0436/F0225)
- `7210a2fb0c` 10-12 09:05 feat: 帖文引用游玩记录校归属与当前可见性,编辑历史按俱乐部可见性投影(F0098/F0119)
- `caf8428ac8` 10-12 09:05 feat: 路线卡发送按公开可见性统一校验,摘要不再留存主题名(F0412)
- `dde22fc64b` 10-12 09:06 feat: 执行俱乐部期次空态区分未承接与条款未开售(X039)
- `eca97395c1` 10-12 09:06 feat: 资料页说明城市签名不改所在城市,保存不带城市字段(F0434)
- `869fe81506` 10-12 09:11 test: 补三种付法预留/成功扣一次/取消释放与共享归零只恢复一次的服务断言（X003/X012）
- `cde80fcde9` 10-12 09:11 test: 补改案释放旧锁、锁定物资占负重且执行失败不吞材料、全场效果封顶只一次断言（X009/X005）
- `a0d2d3301e` 10-12 09:11 feat: D20 回读标明静态加值来源并补客户端加值无效、相册同一提交只结算一次断言（F0301/X019）
- `6297ac1680` 10-12 09:12 feat: 任务卡显示预留中，结算后回执已扣、取消后回执已释放（X003）
- `c0402f030b` 10-12 09:13 feat: 方案面板显示锁定物资仍占负重与改案释放说明（X009）
- `aeb7f3cc44` 10-12 09:14 feat: 团队归零面板说明全队共用一次恢复、只结算一次（X012）
- `7a942a5992` 10-12 12:27 feat: 相册上传失败区分可重试与须重选，网络/5xx 重试同一张图只提交一次，已提交只读（X019）
- `07ae3ef514` 10-12 12:35 feat: 现场主持暂停冻结归零计时、恢复顺延、中止/取消不推进结局（X028）
- `0781ff85d9` 10-12 12:35 feat: 撤销到达核验须有可撤销记录并留操作人/原因审计（X030）
- `2f8a03b8a9` 10-12 12:35 fix: 工作人员确认队伍退出不可重复提交（X031）
- `0cd7bae7f7` 10-12 12:35 fix: 共享局复盘导出剔除人工账签名中的一次性到达凭证（X032）
- `2da0e536f5` 10-12 12:36 test: 主持台暂停/中止/到达核验/迁点/指导完成/队伍退出/导出服务级断言（X028–X032）
- `6240b319eb` 10-12 12:36 feat: 主持台中文状态与动作后果确认、玩家端统一暂停/中止提示（X028–X031）
- `3151afc906` 10-12 12:36 fix: 调整集合时间改为未售条件更新，0 行不报成功并如实标注通知未推送（F0122）
- `04cc75bd78` 10-12 12:36 feat: 主持向指定成员定向广播，名单越界整单拒绝并分列送达/失败/未知（F0129）
- `7d6a575adb` 10-12 12:56 feat: 离开页面与退出本局分名并按模式写明进度/资源/计时/费用后果，已退出的队不能再退出一次（F0351）
- `853837320f` 10-12 12:56 feat: 本地预览标为沙盒与正式判定分开，D20 结果标明作者固定加值来源（F0457/F0301）
- `1d3d83e2a8` 10-12 12:56 feat: 作者运行检查报告合同：问题带跳回定位、D20 规则样例、未覆盖范围常显且不宣称全路径（N011）
- `e3151049d8` 10-12 12:56 fix: 相册已提交提示改用玩家可读文案（X019）
- `0a9e69b3bc` 10-12 12:57 feat: 工具结果卡说明效果作用于本人/本队/已到达参与者/全场，未到达者不受益（X005）
- `138c846862` 10-12 13:17 feat: 平台兑换记录按券/积分订单/礼物分源列示，不做同名合并(F0575)
- `853102f60c` 10-12 13:17 feat: 订单支付事实按积分/现金分源，现金以渠道查询回执定未确认/已确认/冲突(F0535)
- `0d28d61f71` 10-12 13:17 feat: 优惠券模板规则白名单+版本乐观锁，停发只拦新领、平台失效独立原因并列受影响持券人(F0572)
- `3ac9a840cb` 10-12 13:17 feat: 审核通过/拒绝逐类型记录对象实际处置，失败成为可重放待处置意图(F0528)
- `023b38d216` 10-12 13:17 fix: 升级只推进处理层级与案件版本，旧层级迟到提交被拒(F0529)
- `6611dc5997` 10-12 13:33 test: 组局处置接线契约跟随 applyModeration 返回实际处置结果(F0528)
- `fdda8a33a1` 10-12 09:10 feat: 团码接待记录商家回读与结算前撤销，领队出码页显示刷新倒计时(X034)
- `ae577ef7c8` 10-12 09:12 fix: 身份证校验位表补全末位 2，前后端不再误拒合法号码(X036)
- `812e7db413` 10-12 09:12 feat: 发布者实名改绑自助申请与人工审核(X036)
- `6f2d102d59` 10-12 12:41 fix: 恢复申请页被误删的辅助函数，改绑入口改为静态路由(X036)
- `ca6cc4c408` 10-12 13:16 fix: 已接待团列表回读改为 GET，台账不再把读取算成第二次写入(X034)
- `c306e3ecd3` 10-12 20:27 fix: 区分结算通知授权与逐笔发送结果(F0431)
- `2af97ff7a6` 10-12 20:28 fix: 首页精选明确来源与参加条件(F0013)
- `14d1279904` 10-12 20:28 feat: 推荐评分查询展示来源日期与读取状态(F0551)
- `b0f43d2ac1` 10-12 20:29 fix: 标明合作评价阶段并恢复原关系评价(F0219)
- `940c30f195` 10-12 20:29 feat: 分列行为计数口径与数据未知状态(M0025,F0550)
- `fa3427e2a6` 10-12 20:29 fix: 日期筛选明示已加载范围与未知日期(F0003)
- `f30fcbc37a` 10-12 20:29 feat: 补齐共享控件读屏名称与状态(M0154)
- `86e7c6b872` 10-12 20:30 fix: 区分主题普通意见与体验核验范围(F0469)
- `3f2d66d196` 10-12 20:30 fix: 商家权益回读授予记录并区分开通未知状态(F0520)
- `784773e5bb` 10-12 20:30 fix: 搜索价格保留未知且不冒充可买票价(F0004)
- `e526142f68` 10-12 08:26 feat: 撤回漫游定位同意先本机停全部定位持有者，撤回后重启须重新同意并显示采集状态(F0385)
- `57c7952ccc` 10-12 08:53 feat: 漫游分享逐条撤回链接、快照版本与自报回忆标识，敏感地点遮挡编辑与去敏声明（小程序）(M0112/M0117/F0384/N015)
- `667e197097` 10-12 08:53 feat: 清空漫游本机缓存前列出将丢的恢复项，未结算先核对原会话，新增只清展示缓存(F0386)
- `e6987ff53d` 10-12 08:53 feat: 漫游任务入口与列表改名官方活动任务，按本人报名事实标注可参与与完成来源(F0460/F0403)
- `f706476084` 10-12 09:20 fix: 撤回定位由页面显式传入停止能力以进动作台账，清缓存文案去掉工程术语(F0385/F0386)
- `2363888495` 10-12 09:22 feat: 漫游分享快照支持本人逐条撤回、版本条件更新与去敏声明校验，读侧标注自报回忆(M0117/F0384)
- `9c06ff6be8` 10-12 09:24 feat: 成长任务与经验记录下发完成来源，区分到店核销与自报完成(F0395)
- `6fde515ce4` 10-12 13:10 test: 快照版本迁移在集成测试中按同一连接、引号外分号逐句执行(M0117/F0384)
- `2e94b70aa5` 10-12 20:30 fix: 明确门店评价筛选范围和公开统计口径(F0148)
- `ef5d3ea479` 10-12 20:31 feat: 世界目录标明公开状态与版本边界(F0559)
- `e71f1f2bef` 10-12 20:31 feat: 预设路线标明消费缺口与读取失败(F0560)
- `b6bf0f6c94` 10-12 20:31 fix: 未选价格时不启用零元上限(F0004)
- `40c12ff94a` 10-12 20:31 feat: 主题风格显示消费边界并验证配置读取(F0561)
- `921f19e53e` 10-12 20:31 fix: 搜索排序说明与现有默认顺序一致(F0005)
- `165693dda3` 10-12 20:31 fix: 共享控件支持大字换行与完整触达(M0155)
- `1cccdecea6` 10-12 20:32 fix: 邻近候选区分直线距离与未知票价(M0012)
- `192c9eaec0` 10-12 20:32 fix: 商品详情拒读下架与停用门店并收窄公开字段(F0504)
- `e6f4511662` 10-12 20:32 fix: 说明合作方式资格未知与冻结合同边界(F0206)
- `1f7136db25` 10-12 20:32 fix: 完赛候选呈现加载失败与参加条件未知(F0357)
- `dfa76b18a2` 10-12 20:33 fix: NPC未知回答保留原问题恢复(F0231)
- `3435a6d9d4` 10-12 20:34 fix: 路线道路段与直线分开展示且不虚算步行时间(F0466)
- `49e91a7562` 10-12 20:35 fix: 草案未知结果阻止重复生成并保留初稿(F0233)
- `0fc1b00c33` 10-12 20:35 fix: 核验断网保持原请求待确认并阻止重放(M0070)
- `c1a80aa503` 10-12 20:36 fix: 玩家NPC发送遵循真实开关与资格(F0228)
- `1207cbb52f` 10-12 20:37 fix: 收藏取消未知结果先回读并保留原对象(F0018)
- `ed77a971c4` 10-12 20:37 feat: 参与记录直达原报名凭证并区分核销状态(F0494)
- `330476ec42` 10-12 20:37 fix: 字典类别读取失败可恢复且保留隐藏状态(F0578)
- `06379b2f17` 10-12 20:38 feat: 横幅公告区分配置展示与推送状态(F0579)
- `dc6f09945a` 10-12 20:38 feat: 官方通知按原批次读取并分列渠道未知状态(F0513,F0514)
- `d5f6b1c434` 10-12 20:38 fix: 据点提交结果未知时回查原记录并说明证据边界(F0502)
- `6d05d73305` 10-12 20:38 test: 覆盖字典类型首次读取失败恢复(F0578)
- `5d1365afa0` 10-12 20:38 fix: 动态点赞未知结果先回读且防重复切换(F0104)
- `633d9b465d` 10-12 20:40 fix: 分开NPC语音识别文字与音频状态(F0229)
- `1c47661f6c` 10-12 20:41 feat: 共享组件减少动效并保留静态状态(M0156)
- `13952cd2c5` 10-12 20:43 fix: 收藏与推荐兴趣撤回明确分开(M0019)
- `56c0ea5d90` 10-12 20:43 fix: 音色结果核对原录音后才解除未知态(F0226)
- `8ff41ac67d` 10-12 20:45 feat: 经营建议显示事实来源与旧版档案(F0476)
- `8dba265a18` 10-12 20:46 feat: 设置接入本人帖文处置与文字申诉原案(F0521)
- `7204e35932` 10-12 20:46 feat: 推荐偏好入口呈现真实不可用与未知状态(N001)
- `9e68cbc3b9` 10-12 20:47 fix: 系统业务卡区分生成时记录与当前原单(F0413)
- `94d0f19c14` 10-12 20:48 feat: 精选来源解释连接推荐偏好状态(N002)
- `7fdfb525ee` 10-12 20:51 test: 固化邀请归因既有边界与待定规则核对(X037)
- `e811c52e7a` 10-12 20:51 fix: 关闭对话保留未知问题并隔离迟到回答(F0231)
- `fca4da44ed` 10-12 20:52 fix: 邻近候选拒绝缺失与越界位置(M0012)
- `3a97a0b575` 10-12 20:52 feat: 后台只读展示新作者试验资格与回滚合同(N003)
- `805c017956` 10-12 20:53 fix: 分清活动核验团码与玩家组队邀请(F0053)
- `35d87dd546` 10-12 20:53 fix: 帖文申诉准确呈现维持与撤销原处置(F0521)
- `1553444e89` 10-12 20:54 fix: 月历刷新目录并标明范围与未知状态(F0064)
- `c4eadbfb6c` 10-12 20:55 fix: 关闭语音对话保留未知状态并丢弃迟到结果(F0229)
- `4415b09cca` 10-12 20:55 fix: 明确圈层记录不等于权益核销(F0463)
- `92bb512c78` 10-12 20:56 fix: 移除路线旧状态并明确偏好不可用说明(F0466,N002)
- `85348cc6f3` 10-12 20:56 fix: 静态动效覆盖使用原生组件类选择器(M0156)
- `0dd8ddbbb4` 10-12 20:56 test: 记录NPC与AI验证及商家测试合同缺口(F0227)
- `208a5ac3a1` 10-12 20:57 fix: 明示教程版本未知并收束异常加载状态(F0487/F0488)
- `404a8157fc` 10-12 20:57 test: 核对邀约拟议条款展示合同(F0206)
- `680ff604c8` 10-12 20:57 fix: 区分玩法支持情况与反馈工单(F0458)
- `812a02d180` 10-12 20:57 feat: 按账号与俱乐部恢复草稿并核对旧资料(F0498)
- `f2c36d27a1` 10-12 21:30 test: 对齐结算通知授权入口文案(F0431)
- `7f98b01257` 10-12 21:30 fix: 据点与俱乐部核销未知时不重放或冒报发券(M0070)
- `97d7941658` 10-12 21:30 test: 对齐合作拟议条款的现有页面验收(F0206)
- `e4ced676ac` 10-12 21:31 test: 记录共享无障碍验证与页面缺口(M0154/M0155/M0156)
- `49d93c405f` 10-12 21:31 test: 首页标题契约同步编辑精选来源(F0013)
- `f7db4afb66` 10-12 21:31 test: 登记本人帖文处置读取的身份与归属矩阵(F0521)
- `9366b521f7` 10-12 21:32 test: 记录发现页逐项交付与验证断点(N001,N002)
- `69adada983` 10-12 21:33 fix: 正确保存回填和移除活动合作者署名(F0258)
- `47d5b69159` 10-12 21:35 test: 记录合作社交验证证据与未覆盖边界(F0258)
- `cf3c0ab1bc` 10-12 21:37 test: 汇总直营台账证据与经营链交付边界(X040)
- `a6f17761bc` 10-12 21:39 test: 记录漫游隐私移植映射与复验交付(F0385/F0386/M0112/M0117/F0384/N015/F0460/F0403/F0395)
- `07ae95dafa` 10-12 21:40 fix: 角色额度沿用现有边界并明确保存语义(F0554)
- `a68fb572ef` 10-12 21:40 docs: 汇总后台第二波交付证据与业务断点(F0551,F0554,F0513)
- `8a137fb4bd` 10-12 21:46 fix: 动效偏好保存失败保留原状态并提示重试(M0156)
- `56f96af59f` 10-12 21:47 test: 记录动效偏好保存反馈返修证据(M0156)
- `0759cf354f` 10-12 21:49 feat: 保留故事媒体替代文本并完善播放失败恢复(F0263/F0264)
- `d6a651b04e` 10-12 21:49 fix: 防止故事媒体重复上传与跨章节误插入(F0263/F0264)
- `f7c565c18d` 10-12 21:49 fix: 发布后锁定修改引导复制独立草稿(X026)
- `b00d94dd82` 10-12 21:49 feat: 保留待同步心声并在重进和阶段推进前重试(X042)
- `1069db033d` 10-12 21:50 test: 记录故事创作各项验证证据与验收断点(F0263/F0264/X026/X042)
- `6884225865` 10-12 21:57 test: 记录商品专项最终连接失败与集成补验边界(F0504)
- `3f10263cc7` 10-12 22:24 fix: 收口W2合并门禁并保留VPS验收证据
- `5c1fc94934` 10-12 22:25 docs: 保存W2集成验收完整日志
- `868bdc1132` 10-12 22:37 fix: 修复W2全量回归发现的UI与测试接线问题
- `faebded085` 10-12 22:40 fix: 按实名实际调用隔离动作台账读写契约
- `d7cea9139f` 10-13 10:25 chore: 拆出暂缓22项并保留原W2通用修复
- `8472ebae7b` 10-13 10:26 docs: 记录22项拆分验收与提交后证据复验

