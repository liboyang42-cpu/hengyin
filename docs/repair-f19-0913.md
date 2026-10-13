# F19 跨开场暂停恢复后的主办控制修复

基线：`d00bea46290ff2e97bf7b4257596e259fc4c2c7a`（包含已完成 F18）；规格：《第六轮_全产品查漏与官方活动实跑.md》F19。仅修改 OfficialEventV2ServiceImpl、OfficialEventV2ServiceTest 和本文档。

## 修复

旧 resume 根据时间把活动改为 LIVE，但没有同步 ACCEPTED 承接关系。LIVE 中有效主办必须 ACTIVE，因此恢复接口成功后，再次暂停或转交主办被拒绝。

恢复为 LIVE 后调用自然开场已有的 `activateAcceptedParties(eventId)`，同一事务激活主办、候任、协办及承接方；不更改 organizerAuthority，不新增生命周期，不跳过发布/履约检查。保留开场前恢复与跨结束进入 SETTLING 的既有分支。不会把恢复动作写成“定时生命周期推进”的审计。

## RED → GREEN

首先只新增 `resume_跨开场恢复后主办仍能再次暂停` 行为回归，生产代码未改。通过真实 service 公共方法 pause→resume→pause；第二次 pause 返回：

```text
expected: <null> but was: <仅本场有效主办方账号可执行该命令>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

RED 命令 exit 1，日志 `/tmp/f19-red.log`。此前测试夹具字段类型编译错误已经修正，不计作 RED。

增加 LIVE 激活后同一用例通过。新增六项行为用例覆盖：

1. 暂停跨开场→恢复→再次暂停，以及所有已接受关系 ACTIVE、现任/候任 authority 不变。
2. 恢复→转交候任→旧主办被拒→新主办暂停/恢复；旧主办 WITHDRAWN 不被重新激活。
3. 自然开场与跨时恢复的活动和承接状态一致；自然开场后的再次暂停恢复也可执行。
4. 开场前恢复仍 SCHEDULED/ACCEPTED，不提前激活。
5. 跨结束恢复明确拒绝运行、进入 SETTLING，保留 PAUSED、不激活关系。
6. 恢复后到原结束/结算时间，生命周期进入 ENDED 并归档关系。

```sh
mvn -q -pl chengyinhub-admin -am -Dtest=OfficialEventV2ServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终全类执行 exit 0：74 tests，Failures/Errors/Skipped 均0，相关模块编译通过。日志 `/tmp/f19-final.log`；Surefire报告 `chengyinhub-admin/target/surefire-reports/com.chengyinhub.business.service.impl.OfficialEventV2ServiceTest.txt`。`git diff --check` 通过。

总控组织独立双轴审查：`review_standards_wave1` 与 `review_spec_wave1` 对 F19 均为0项发现。总控确认审查完成后授权仅三个 F19 文件 pathspec 本地提交。

## 验证边界

上述为完整真实 Service 逻辑＋有状态 Mapper 夹具的单元回归，时间窗口使用固定相对时间，不是数据库实跑或真实等待开场。夹具只执行现有 SQL 的状态语义，不模拟 Service 授权成功或绕过发布检查。真实接口、MySQL 事务、开发者工具 UI、生产及通知到人未验证；不能据此宣称第六轮或官方活动全链验收完成。F21 补位/退出业务未改动。无 push、PR、merge 或部署。
