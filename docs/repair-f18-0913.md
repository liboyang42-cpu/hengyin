# F18 同投诉并发结案重复扣款修复

基线：`7860bfa0f79ad77d02a4e3051465353ec86cafad`。依据：桌面全产品审查报告《第五轮_资金与业务实跑.md》F18。仅修复这一投诉资金状态竞争，不改追偿比例、部分扣款、冷静期或真实渠道支付规则。

## 复现与修复

旧代码先普通读取未关闭工单、扣款、写流水，最后按 id 关闭。同一工单两个事务均能读到开放态，故重复扣款。新代码在任何资金操作前以 `id + 已读取的status + status<3 + del_flag=0` 条件更新抢占 CLOSED。败方 rows=0 明确抛出“工单状态已变化，请刷新后重试”。CAS、资金、流水、最终责任结果仍在原 `@Transactional(rollbackFor=Exception.class)` 同一事务内，失败回滚可重试。

`advance` 已有 ACCEPTED→ADVANCED CAS。精确旧状态保证先行补偿改变工单后，基于旧快照的 close 被拒绝；刷新后可继续正常结案。若 close 先完成，旧 advance CAS 失败，不会关闭后又发补偿。

## 本地证据

新增 `CoopComplaintCloseMybatisIntegrationTest`：UUID 专用新建 localhost scratch schema，真实 MySQL 9.7.1、Spring 事务代理、真实投诉/余额/流水 service 及 MyBatis XML。栅栏只控制旧快照读取顺序；不伪造 CAS/余额或流水结果。每条断言通过独立连接读取已提交的库状态。测试结束删除自己创建的 schema；不连接原审查库或生产。

命令（仓库根目录）：

```sh
CHENGYIN_FUNDS_MYSQL_IT=1 mvn -q -pl chengyinhub-admin -am \
  -Dtest=CoopComplaintCloseMybatisIntegrationTest,CoopComplaintServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- RED：先新增并发测试，生产代码未改；1 test, 1 failure, 0 errors。独立余额回读 `expected 99.00 but was 98.00`，命令 exit 1。日志 `/tmp/f18-red.log`（本地临时证据，不提交）。此前 Mockito proxy 夹具错误已修正，不计为 RED。
- GREEN：同一双结案用例只一请求成功，另一个拒绝；余额100→99、仅一条流水100→99、终态3，重复结案拒绝。
- advance 先胜：玩家0→5，责任方仍100，旧 close拒绝；刷新close后责任方99，两条不同方向流水。
- close 先胜：责任方99，玩家仍0，仅一条扣款流水，旧advance拒绝。
- 余额仅0.30：按既有规则只扣0.30，结案实扣0.30；余额0：零流水，实扣0。
- MySQL trigger 注入扣款失败、流水插入失败：余额恢复100、零流水、工单恢复开放；撤掉故障后可成功重试。
- 最终工单结果写入抛错：已完成的扣款、流水和CAS全部回滚。

## 自审

code-review 独立双轴审查：Standards 0项、Spec 0项。审查为静态检查，运行证据由上述实际测试提供。`git diff --check` 通过。

## 验证边界

最终命令 exit 0；真实 MySQL 并发 IT 8项、普通 Service 单测10项，共18项，Failures/Errors/Skipped均0，Maven相关模块编译通过。日志 `/tmp/f18-final.log`，Surefire报告在 `chengyinhub-admin/target/surefire-reports/`。普通单测含新增 CAS败方无资金操作断言。集成测试中的冷静期服务是 mock，未借此宣称冷静期持久化通过；本次未更改该逻辑。真实生产接口、小程序UI、MySQL 8.0指定版本、真实微信/银行到账均未验证。尚未push、PR、merge或deploy。

余额快照在不同工单或其它资金路径同时操作同一会员时的广义一致性，并非“同一工单重复结案”的验收证据，不能由这8项测试推导全资金系统通过。既有余额不足差额仍由人工追偿，不自动发起退款或打款。
