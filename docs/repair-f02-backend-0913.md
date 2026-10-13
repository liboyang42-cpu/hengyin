# F02 商家聚合读取修复

基线：`7860bfa0f79ad77d02a4e3051465353ec86cafad`。本项只处理后端聚合的业务写副作用，不修改小程序白名单或全局邀约业务。

## 问题与修复

`relationHome → invitesFor → CoopInviteServiceImpl.selectCoopInviteList` 原先在两次读取前分别调用 `expirePendingInvites`，更新全局超时邀约。把这个 POST 直接标记为生产只读查询会放行隐含写入。

`MerchantAggregateReadServiceImpl` 改为直接注入 `CoopInviteMapper` 读取发出、收到的邀约。统计待办时按原 SQL 投影过期，不修改数据库，也不修改 mapper 返回的邀约对象：

- 只有 `del_flag=0 AND status=0 AND invite_type != 2` 属于过期范围；NULL 类型遵循 SQL 不匹配语义。
- 显式 `expire_time <= now` 或 `create_time <= now - 48h` 任一成立即不计待办，即使显式截止晚于创建后 48h。
- type 2、无日期、已接受关系保持原语义；联系人脱敏、主题限定、父对象可见性和去重逻辑保持既有行为。

## RED 与验证

第一条测试通过真实 `CoopInviteServiceImpl.selectCoopInviteList` 驱动旧路径，断言 mapper 不发生 `expirePendingInvites`：修复前失败，记录两次实际调用；直接读 mapper 后通过。

第二条测试通过公开 `relationHome` 驱动过期、满 48h、未到期、type 2、NULL 类型、无日期、已接受关系，并检查原始状态未被改写。只替换 mapper 尚未补过期投影时失败：待办 expected 4 / actual 6。

```bash
mvn -q -pl chengyinhub-admin -am test \
  -Dtest=MerchantAggregateReadServiceTest,MerchantDiscoveryMemberIdProjectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

最终结果：两类测试共 7 项通过（6 + 1），0 失败、0 错误、0 跳过，Maven exit 0。`git diff --check` 通过。

运行日志：`/tmp/f02-backend-red.log`、`/tmp/f02-backend-tracer-green.log`、`/tmp/f02-backend-expiry-red.log`、`/tmp/f02-backend-green.log`。编辑期间曾被 JDT 写出的中间态 class 干扰，出现 `isPendingAt undefined`；仅删除该生成 class 后由 Maven 重编，最终结果取重编后的 surefire 报告。

## 两个 POST 的业务副作用审计

审计范围包括 Controller 的商家授权以及聚合实际调用的 service、mapper SQL，递归展开 SQL include：

| 聚合 | 查询链 | 结果 |
|---|---|---|
| relation-home | 商家发现（普通/距离）、俱乐部发现、发出/收到邀约、父主题/俱乐部可见性、合作方脱敏信息 | service 均直接委托 SELECT；邀约已绕开原有全局过期写入 |
| marketing-home | 招募主题、章节档位与经济条件、优惠券、自己主题/活动、营销时间窗指标 | service 均直接委托 SELECT，无惰性创建、统计落库或状态推进 |
| 商家权限 | MerchantAccessServiceImpl.require/resolve：会员、商家、商家操作员读取 | 未发现业务写入；未修改鉴权规则 |

实际 SQL 均为 SELECT（含子查询和内建统计/距离函数），没有 UPDATE、INSERT、DELETE、REPLACE、CALL、FOR UPDATE 或导出文件。两聚合完成此修复后可以作为**业务只读 POST**分类；全局 `/api/coop/list` 等仍有过期副作用，不得顺带放行。普通认证中间件和生产版本部署观测不由这份业务读取审计证明。

尚未验证：正式部署的 jar 是否含修复、生产 HTTP 实读、小程序真实入口。未连生产、未 commit/push。
