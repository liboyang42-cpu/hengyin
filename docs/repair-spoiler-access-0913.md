# F23 剧透偏好与 F09 同源剧情访问 SQL 修复

基线：成功 fetch `github/master` 后从 `7860bfa0f79ad77d02a4e3051465353ec86cafad` 建立独立分支 `codex/repair-spoiler-access-0913`。规格来自第七轮 F23，以及总控确认的 F09 同源查询修复范围。

## 现码调用与最小改动

- F23：`ApiSpoilerController.preference`（`POST /api/spoiler/preference`）读取登录会员和 boolean 参数，调用 `SpoilerProtectionServiceImpl.updatePreference`，按 boolean 返回“设置成功/失败”。原 `upsertPreference(...) == 1` 错判 MySQL 更新返回 2；同值返回也受 `useAffectedRows` 和更新时间影响，可能为 0。改为 upsert 正常返回即成功，数据库异常自然向上抛出。没有改 Controller、登录、字段或揭晓规则。
- F09 同源：`CmsRegistrationMapper.hasActiveTopicStoryAccess` 唯一直接生产调用为 `ApiTopicController.topicInfo`（`POST /api/topic/info-to-user`）多章节剧情访问判断。删除该查询引用的不存在列 `r.del_flag`。保留登录会员、主题/活动关系、支付和报名状态、自玩有效期及自由探索权益条件。报名的既有删除 Mapper 是物理 DELETE；不补造列、不改迁移。
- 旧 `CmsRegistrationStoryAccessMyBatisTest` 人为建了 `del_flag` 并模拟逻辑删除，掩盖真实结构问题。改为没有该列的 H2 夹具，并调用既有物理删除 Mapper 验证撤销访问。
- 第八轮 NPC 报告中的 `hasPaidRegistration` 是另一条 `PlayTeamMapper` 查询，已由总控的 F09 修复处理；不能把本项剧情查询改动冒充 NPC 或俱乐部可开场列表的完整复测。

小程序运行 JS/WXML 中未找到 `/api/spoiler` 偏好设置入口；未新增入口。这只描述当前静态代码搜索，不证明线上没有动态入口。

## RED → GREEN

1. F23 原实现：真实 MySQL 首次保存 false 后改为 true，断言“changed upsert must report success”失败，1 项测试、1 failure、0 error、0 skipped。日志 `/tmp/spoiler-f23-red.log`。最小修复后同一测试通过，日志 `/tmp/spoiler-f23-green.log`。
2. F09 同源原 SQL：直接提取仓库 `sql/base_schema_20260818.sql` 的完整报名、活动和权益 DDL，独立查询确认报名表没有 `del_flag`。真实 Mapper 执行报 `Unknown column 'r.del_flag' in 'where clause'`，4 项中 1 error，其余偏好测试通过。日志 `/tmp/spoiler-access-red.log`。移除错误条件后4项通过，日志 `/tmp/spoiler-access-green.log`。
3. 扩展边界后，6 个测试类共 **44 项通过、0 failure、0 error、0 skipped**，含7项真实 MySQL 测试。日志 `/tmp/spoiler-access-final-green.log`；相关模块编译通过。

MySQL 服务端通过本机 `SELECT VERSION()` 回读为 **9.7.1**，不是 MySQL 8。每项 IT 使用带 UUID 的独立 schema，连接由既有 `MysqlIntegrationTestConfig` 提供；仅删除自身创建的 schema。没有接触审查样板库或生产。偏好表使用既有迁移中的真实 DDL，其他三表使用权威基线 DDL，数据库事实均从独立连接回读。

真实 MySQL 覆盖：

- `useAffectedRows=false/true` 两种驱动计数模式，固定 SQL 会话时间确保同值请求不会靠更新时间伪装成变化；首次插入、false→true、true→false、重复同值均经过实际 Controller→Service→Mapper，响应200与保存/读取偏好一致，不产生重复会员行。
- 临时 schema 内触发器主动拒绝 UPDATE，Service 保留数据库异常、已存偏好不变；非法会员拒绝且不新增记录。
- 会员/主题不匹配、空会员/主题、未付/支付中/失败/退款、取消/过期/待付报名均拒绝；已支付零现金票与核销后有效票仍可访问，已核销但取消仍拒绝；实际物理删除后拒绝。
- 主题直接票、自玩 topic_id、活动反查主题映射；不存在/不匹配活动不以报名冗余 topic_id 放行；自玩未来/过去/存量空有效期保持原规则。
- 自由探索无权益、他单/他主题权益拒绝；应得0与已核销1可访问，失效2不可访问；有效权益不能覆盖已退款或取消的报名。

复现（在本 worktree 根目录；仅本机隔离测试库）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am \
  -Dtest=SpoilerAccessMybatisIntegrationTest,CmsRegistrationStoryAccessMyBatisTest,SpoilerProtectionServiceTest,SpoilerProtectionMigrationContractTest,SpoilerIsNotAnswerGateTest,ApiTemplateSecretsGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 验证边界

Controller 测试替换登录会员读取，不代表 HTTP/JWT 集成测试。主题详情既有28项秘密内容门禁测试属于 Mock 回归；真实数据库资格矩阵不等于整条详情 HTTP/客户端链路验收。MySQL8、生产、NPC真实模型、俱乐部可开场列表实跑、小程序/真机均未验证。无新依赖、鉴权修改、生产动作、commit、push、PR、merge 或部署。冻结交总控双轴审查。
