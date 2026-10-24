# 逐席位出示与原事实恢复

代码已完成；本批基于 `4619caa39bdf491667f0a0263c657117ac4b12f4`，独立目录 `/workspace/hengyin-c-seat-verification`、分支 `codex/architecture-day-c-seat-verification`。父链保留 `1fbd8d8`、`735b9f4`，只有 `4619caa` 开始包含退款交错的完整当前行 ForUpdate 修复；不能只取旧两提交。没有改 A 下单/退款、G 金额算法、CI、baseline 或生产库。

## 实际调用与合同

- `scene-qr-ticket` 调既有 `/api/verify/dyncode/issue`：多人订单先得到无码的选择信息；明确输入合法席位才带 `seatNo` 签发。刷新、过期重试保留该席，换席必须明确操作。响应中的原报名/席位/身份不符拒绝展示；实际已核销状态明确显示。仍用原 QR/v1 HMAC，不引入票码体系。
- `pages/play.ensureTicket` 是第二个出码 caller：收到选择信息移交同一场景组件，不默选首席，不展示整单码。
- 通用扫码入口实际有商家页、个人页 profile、参与详情三个 caller：活动席位码走原 `scan_dynamic_code`；主题席位码明确要求本场带队入口，不能省略 activity。三个 caller 均接同一既有持久核销 workflow。
- `pages/play.leadVerifyMemberTicket` 对席位码走原 `scan_group_member_ticket`，冻结 actor、原场次、会话和扫描代次。其余旧码路径保留原行为。
- 原 workflow 的磁盘 UNKNOWN 增加可选、不可换义的 `seat={registrationId,seatNo,activityId?}`；键为账号摘要与 `seat:registration:seatNo` 目标摘要，**不含 QR nonce/TTL，也不因换 activity 绕开原 UNKNOWN**。不落 token、原二维码、姓名。写前存储及读回失败零提交。
- 响应丢失/销毁/重启后，再扫同席只调用只读语义的 `POST /api/verify/seat/receipt`，传原 target；异场次不能替换原待办。不重发核销。520、无回执、异报名/席位/场次、缺核销时间/核销者、任意成功列表都保持 UNKNOWN。
- 唯有原完整已提交回执且 `replayed=true`、磁盘 attemptId 仍属于原操作，才删除原记录并释放原内存锁。迟到回执不能删除新的 attempt。磁盘删除失败继续保留原 UNKNOWN。
- 登录主体、原 authorization 引用和登录代次（`_featureFlagsEpoch` + `authorization_issued_at`）以及页面/场次代次共同守卫相机、写回和读回。A→B→A 旧回包零 UI/零清理。只读请求销毁时中止并清定时器，磁盘事实保留。
- 席位分支不占全局 loading；三个旧核销 adapter 的 loading 逐操作记录所有权，离页清理过的旧 token 不能再次隐藏新操作的 loading。未改全局 loading 工具。

新增 `utils/registration-seat.js` 仅负责原码关联、规范席位回执/选席及当前登录上下文检查；真实性、权限、支付、核销和唯一性继续全由后端已有服务负责。原非席位 workflow 的 UNKNOWN 人工记录核对策略不变，未赋予通用“成功列表解锁”。

## 实际验证

`frontend-node-results.json` 逐文件记录实际条数：15 文件 **375/375 PASS，0 fail/skip**。每文件独立进程执行：

```
node --test --test-isolation=none --test-reporter=tap chengyinhub-xcx/tests/unit/<file>
```

其中新增 23 条工具/组件 VM 场景、7 条真实 caller 方法场景、既有 adapter suite 保留原 236 条并新增 12 条席位反例（248 条）。原 scene 静态合同只将过时的 token guard 写法对齐为完整原上下文守卫，保留原入口/票面/过期保护断言。所有本批 JS `node --check`、`git diff --check` 通过。Node 默认文件隔离输出只汇报文件数，此处数量来自各文件独立 `--test-isolation=none` 的 TAP，不以 15 文件冒称 15 业务测试。

未运行：Java 编译/测试、原 42 条后端席位用例（含 RR 退款交错 Service/MySQL）、HTTP/JWT/真实数据库、DevTools/真机/截图、完整门禁或 CI。未上传、部署或执行 DDL。提供的小程序 skill 和 Change Safety Protocol 已按既有人类授权应用；人类禁止 DevTools/发布，因此不以本机工具替代真机证据。

## 集成和回退

本批没有新增 schema，依赖父候选的席位表及活动 scope additive migration。回退客户端不能删除席位/核销审计或让多人整单码恢复默认核销；未确认意图保留磁盘待办，原服务 receipt 读口需保留。

root 最终需按组合源码重建 action ledger，并精确重定位受 `pages/play/index.js` 新接线影响的旧 UI baseline 行号；C 不并改 root 的台账/基线。真实存储和统一回归仍由 root 调度。A 原订单反核销 review.patch 和 G 按实际席位计费接线仍需原 owner 串行集成，不能把此客户端候选视为资金闭环验收。
