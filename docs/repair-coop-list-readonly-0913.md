# F02：合作列表只读修复（本地候选）

基线：`814a3ca2283accf525e0c309073c15c0c4198e8b`。分支：`codex/repair-coop-list-readonly-0913`。未提交、未发布、未连接生产。

## 问题与改动

`ApiCoopController.list` 原调用 `selectCoopInviteList`，后者先全局执行 `expirePendingInvites`。即使当前会员返回空列表，也会提交其他会员邀约的 status/update_time 修改。此链不执行退款或资金派发，但不能当作只读请求。

新增列表专用只读 Service/Mapper 查询；Controller 的 sent、merchant received、club received、game slot、topic slot 五处均使用它。其他业务仍调用原有方法。SQL 将原过期 UPDATE 的条件逐项投影为 effective_status=5，并按 effective_status 过滤：status=0、invite_type!=2，expire_time 到期或 create_time 达到48小时。NULL 条件、边界包含等号、del_flag 与原字段过滤一致。原查询对象不修改；update_time 不伪造。accepted 的 config_deadline 过期不加入列表投影。

时间来源保持原 `DateUtils.getNowDate()` 参数绑定及 SQL `DATE_SUB`；没有改成 JVM 日期运算或另换数据库 NOW。原定时任务、接受/拒绝/撤回的锁、归属和到期提交逻辑不变。

## 负控与回归

- RED：`/tmp/coop-list-readonly-red.log`，旧实现真实 MySQL 8.0.45 空列表查询使其他会员两条 pending 邀约变成5并写入 update_time，只读断言失败。
- 初次 GREEN：`/tmp/coop-list-readonly-green.log`。
- 最终：`/tmp/coop-list-readonly-regression-green.log`，Maven exit 0；136项，0失败、0错误、0跳过。其中真实 MySQL 集成4项、Service既有93项、Mapper既有3项、Controller相关36项。
- 先前回归仅1项旧Mock seam错误；将 `list_marksCompletedHeldDepositAsRefundPending` 的 stub 改接只读方法后通过，原保证金待退断言保留。

复跑命令（在此 worktree 根目录）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am -Dtest=CoopListReadOnlyMybatisIntegrationTest,ApiCoopMerchantAccessTest,ApiCoopControllerPartnerNodeTest,ApiCoopControllerInviteAuthzTest,CoopInviteServiceImplTest,CoopInviteMapperSqlTest -Dsurefire.failIfNoSpecifiedTests=false test
```

集成测试默认本机 `127.0.0.1:3317` root 空密码；只创建随机 UUID schema，取仓库真实 coop_invite DDL，每项 finally 删除自己的 schema。既有容器和审查库未操作。JDBC 会话使用 UTC；固定时间边界测试将同一个 Date 交给投影与原 UPDATE 逐行对照。

4项真实测试覆盖：空列表跨会员数据不变；精确到期/48小时及后一秒、NULL日期、type2、状态与身份字段过滤；商户管理者 owner scope、俱乐部 received、pending/accepted slot；原 job 首次3行再次0行，以及过期接受/拒绝/撤回抛业务异常后仍提交终态。所有列表调用独立连接回读 status/update_time，确保无提交副作用。

限制：Controller 的外部协作者采用受控 Mock，MyBatis、Service 事务代理和 MySQL 真实执行；未验证 HTTP/JWT、真实小程序展示、线上部署或任务注册状态。本地绿不代表产品已上线或验收。

## 总控集成

候选d08cd8f1a已集成总控66624b3ec，Standards与Spec独立审查各0项。根复跑`/tmp/cy-coop-list-readonly-integrated.log`退出0，6份Surefire XML汇总136项、0失败/错误/跳过，包含MySQL8真实4项。未push、合并或部署。

正式邀约详情仍在补验：发现实际响应id与前端inviteId消费可能不一致，尚待真实页面复现，不把只读回归通过当成详情已通过。
