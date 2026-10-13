# F06 / F11 评论与帖子互动修复

基线：`98d54b3157e7751e1613954961196d0cd9bb22eb`（在 `7860bfa0f79ad77d02a4e3051465353ec86cafad` 上仅含已审阅 F09/F10）。需求：桌面《城瘾全产品审查文档/reports/样板实跑报告.md》F06、《续跑核验_第二轮.md》F06 扩展、F11。

## 实际改动

- 帖子赞/踩、评论赞、评论与回复在写入事务中锁定当前内容。帖子及整条回复父链必须 `del_flag=0` 且 `status=1`；回复必须属于同一对象。删除、隐藏与互动按行锁串行，旧页面无法越过已提交的删除/隐藏。
- 保留原有点赞切换语义。相同玩家的并发切换串行执行，不产生重复关系行；没有新增索引或迁移。
- 文本检查通过后，`CommentPublicationService` 在事务内完成公开、首评奖励及明细。积分规则缺失、值为零时正常发表、不写零值积分流水。实际积分/明细异常仍抛出，事务回滚，重试不遗留先前已公开的评论或积分。
- API 在公开事务提交后调用原媒体送检，避免奖励失败后已经产生送检，或回调看到未提交评论。没有改媒体送检、审核回调及上传 receipt 业务规则。
- 同一玩家同一对象首评一次奖励的既有规则保留；删除/隐藏不回收已发积分。普通主题/活动评论不在此项重定义其可评论生命周期，只校验参数与回复归属/父链。
- 首评奖励互斥覆盖 `ownerType=1/2/3`：公开事务开始先复用 `UmsMemberMapper.lockMemberById` 锁玩家，随后建立首评查询快照。不同对象仍各有一次奖励；同一玩家跨对象的积分余额及流水快照串行更新。没有新增 Mapper、SQL 或框架。

## 验证

本机 MySQL **9.7.1**，唯一临时 schema，表定义取 `sql/base_schema_20260818.sql`。真实 API、公开 Service、MyBatis mapper、Spring 事务、积分增加逻辑及明细写入。测试登录身份与微信安全外部调用使用替身；积分余额表保留本测试所需列。

RED 记录：

1. 缺积分配置：真实 `addPoints(0)` 抛“用户ID和积分必须为正数”。`/tmp/content-f11-red.log`。
2. 数据库触发器令积分明细写入失败：旧逻辑已公开 1 条评论，预期回滚为 0。`/tmp/content-f11-rollback-red.log`。
3. 删除帖子后点赞仍返回 200。`/tmp/content-f06-red.log`。
4. 删除父评论后点赞仍返回 200。`/tmp/content-f06-parent-red.log`。
5. 奖励失败仍调用媒体送检。`/tmp/content-f11-media-red.log`。
6. 临时仅移除两条 `FOR UPDATE` 的负控：两个删除/隐藏竞争测试均失败，写请求未等待持有行锁的变更；恢复锁后重新通过。`/tmp/content-f06-lock-negative.log`。
7. 独立 Standards 审查提出 P2 后真实复现：`ownerType=1` 和 `2` 两请求分别读到自身首评（`firstSnapshots=2`），两组均写出 2 条奖励流水（预期 1）。`/tmp/content-f11-owner-red.log`；加入玩家锁后同一测试两组通过，`/tmp/content-f11-owner-green.log`。

最终检查命令（根目录执行；后缀每次唯一）：

```bash
CHENGYIN_FUNDS_MYSQL_IT=1 CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX=_f0611_unique \
mvn -q -pl chengyinhub-admin -am \
  -Dtest=ContentInteractionMybatisIntegrationTest,ViewCommentServiceImplTest,ApiCreativeSquareReadModelBudgetTest,ApiCreativeSquareActivityAttachGuardTest,SquareCommentAuditBypassControllerSafetyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖：0/缺配置的首评与首回复、正常发布回复/赞踩/一次奖励、删帖/隐藏/不存在、删除父评论及其后代互动拒绝、跨帖回复拒绝、他人删除拒绝、删/隐藏持锁后并发写入拒绝、并发首评只奖励一次、重复赞切换无重复关系、真实奖励失败回滚与重试、文本拒绝不写入、送检时从另一连接确认评论已经提交。

独立首轮审阅：Spec 0 项；Standards 1 项 P2（主题/活动首评奖励并发）。该项已通过真实 MySQL RED→GREEN 修复，补充不同 ownerType 各自奖励及连续流水快照回归；复审待总控协调。本次仅修改既有 `CommentPublicationService`、集成测试与本记录，扩围原因是首评资格必须覆盖三个实际 ownerType。

复审修复后的完整测试结果：**39/39 通过，0 skip**（真实 MySQL 16 项、既有相关测试 23 项），`git diff --check` 通过。日志 `/tmp/content-all-green.log`。代码与测试已冻结待复审，未提交。

## 尚未验证

MySQL 8.0、生产、微信真实内容检查和回调、UI/真机、真实渠道及资金。测试身份不代替真实登录权限链。未 push、PR、merge、部署；本地修复不代表线上已修。

另一个 `CmsRegistrationMapper.hasActiveTopicStoryAccess` 引用缺失列的问题已单独告知总控，本项未修改。
