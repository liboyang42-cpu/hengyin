# R9-07 通知人数与场次提示修复（候选）

基线0de03bd17。原第九轮157截图已实际查看：本场已报名已选却仍提示“从具体活动进入后才可选”，初始场次人数缺失。

## 改动

loadAccess与preview原本使用实例_activityId，loadAudienceCounts却读取已移除的data.activityId。人数请求改用同一实例字段。受众行hint从实际有无场次与既有EVENT_OPERATE权限投影：无场次提示从活动进入；有场次无权限提示本场权限不足；有场次且有权限不再显示误导提示。原disabled与选中规则不变。没有修改发送、预览必需步骤、范围授权或渠道。

## 证据

/tmp/r9-07-notify-probe/count-red.log：正式onLoad→权限回包→人数请求，旧参数activityId=null导致断言RED；改一处字段后count-green.log 15/15。hint-red.log证明原行缺真实提示投影；补修后green.log相关20/20，无跳过。测试同时覆盖场次权限、预览同场次、零人数、无场次null不伪造0、无本场权限仍禁选，整条测试没有send/retry请求。

UI-GATE-0 --warn-as-error通过（ui-gate.log）。借原9763，不重启或替换handler。native.cjs实际加载三种页面、原生预览按钮tap、无权限受众tap：有场次人数1/0/1且无错误提示，无场次保留提示/不显示假人数，有场次但无权限保留禁选和准确提示。表单内容使用真实输入handler注入，非真人键盘输入。native-receipt.json记录所有请求只限access/counts/preview，均被拦截，没有发送通知。native-cleanup.json恢复/disconnect true。三张截图native-scoped/no-event/permission.png均已总控实际查看：有权限正常显示人数，无场次与无权限分别保留对应说明；禁用行沿现有灰态，不把截图当真实权限证明。

人数/权限为合成响应，本次不证明真实角色或服务端人数计算、实际消息送达。原报告有真实人数/预览相符证据，本修复仅对齐页面传参与提示。独立双轴审查待完成，未提交/上线。

独立Standards与Spec均0项，两位均独立重跑正式测试文件15/15通过。本地提交只覆盖本请求同源与展示提示修复；通知送达、真实服务端人数及生产仍未验证。
