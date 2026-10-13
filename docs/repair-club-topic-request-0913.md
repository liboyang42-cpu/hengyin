# R9-08 主办详情请求参数修复（候选）

基线4a6181f98。来源：桌面第九轮报告R9-08及168截图，图已实际查看，显示“活动暂时打不开”。原审查临时del_flag补列不作为本修复依据；本次不接数据库。

## 原因与最小变更

正式pages/club/topic-detail/index.js fetchDetail用JSON调用/api/topic/info-to-user；现行ApiTopicController.topicInfo(String id)按请求参数绑定，无法从JSON读取id。仅将该请求改为表单Content-Type和对象参数，保持URL、ID、身份、成功/失败处理及导演台状态流不变。其他@RequestBody端点继续原JSON方式。

## 可复现证据

/tmp/r9-08-topic-request-probe/capture.cjs加载正式page并调用fetchDetail，request-red.json是旧请求；Probe.java在/tmp重新编译当前Controller，用Spring MockMvc提交实际JSON后返回500“参数不合规”，业务service未被调用；表单正控以准确topicId=990049/memberId=9003调用原service。getAppUserId及service使用合成替身，service返回null，所以正控返回“主题活动不存在”，仅证明绑定而非完整详情成功。首次输出以错误字符集解码造成断言失败，改为UTF-8后诊断成立，不列产品缺陷。run.log保留结果。

正式测试先RED：club-topic-detail-status-truth.test.js新增真实onLoad→fetchDetail请求与失败→onRetry→正常显示链，旧请求Content-Type失败，原5项正常状态用例通过。最小修复后club-topic-*共33/33通过，client-red.log / client-green.log。原PREPARING、RUNNING、FINISHED状态投影及剧情导航正控保留。

修复后的正式page请求重新捕获至request.json，ProbeGreen.java按表单参数递交当前Controller，精确ID/member调用验证通过，binding-green.log；仍不冒充数据库详情或完整鉴权通过。

双轴审查、原生修复后截图与从场次进入准备/开始/结束/结算的受控完整链待补。当前候选未commit/push/PR/部署。

## 候选原生回读与门禁

UI-GATE-0 --warn-as-error通过（ui-gate.log）。native.cjs借既有9763，正式onLoad发出的Content-Type及对象id实证新运行时加载，没有替换handler；受控首次错误后，真实cy-error子节点tap触发第二次请求，正常数据渲染为已确认，标题与原功能入口保留。native-receipt.json记录两次拦截读取，native-cleanup.json恢复/disconnect均true。native-recovered.png已由总控实际查看。首次缺automator模块仅是探针依赖定位错误，后显式沿用已有绝对依赖路径，无安装/重启。

此UI使用合成详情响应，未执行准备/开始/结束/结算业务。截图的日期显示另有跨时区偏差（夹具9月18日，页面9月17日），对应待核R9-10，未混入本请求格式修复。未据此判整个主办详情业务通过。

独立 Spec / Standards 审查均 0 项；本地提交仅覆盖上述请求格式、回归与证据。完整场次业务验收仍待补，不将单点修复等同全流程通过。
