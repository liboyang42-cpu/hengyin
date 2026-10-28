# H：原核销 caller 的有限路径与冗余静默选项（SOURCE）

唯一父源码 `4efbd7ef02f3e2d30cd055865098426fa6017da0`，父 tree `2e6dd3172e8e91a0f3294612badc46856a499416`。仅本次已报两处 U1 / 三处 U5 接缝，不改门禁、基线、台账、app/request-client、权限、核销目标或原恢复政策。

## 交付方式

- donor 实际源码仅 `utils/registration-seat.js` 和新增直接 unit `tests/unit/verification-finite-transport-feedback-4efb.test.js`，另含本目录静态交付物。
- `shared-caller.review.patch`：共享 play 页、参与详情组件、citynode 页三文件完整窄增量，由 root 串行装入实际工作树。没有把这些共享后像直接写入 donor 的生产路径。
- `test-contract.review.patch`：现有 `ui-integrity-u1-seat-callers-ee815.test.js` 的结构合同适配，由 root 同时采用。保留原安全负控；不改其原基线/行号锚断言（后者由 root 最终对合成源码统一处理）。
- `owned.review.patch`：donor 两个 owned 文件的源码增量。
- `complete.review.patch`：上述全部源码增量的总包，不可和分片重复应用。完整前像为父源码，可重建全部六个后像；PREIMAGE-POSTIMAGE.json/POSTIMAGE.sha256 记录精确内容身份。
- 本片只相对 4efbd7ef 增量；上轮已集成源码不重交、不回退。无删除记录。

## U1 原目标不变

play:4950 原 Object.assign({}, request, callbacks) 改为直接请求字面量：
`url:'/api/registration/scan_group_member_ticket', method:'POST', data:request.data`。
原 request 继续用于 seatTarget 和三路结果分类；key、actor、activity、login revision、scan sequence、原 workflow 和 seatRecovery 未改变。

参与详情（实际路径 `subpackageMember/components/scene-member-participation-detail/index.js`）保留 resolveVerificationScan 的 invalid 拒绝，并把其四个原端点展开为受控 const 条件表达式。发送前明确拒绝四端点以外的 URL。key 仍是原 URL + ':' + 原 code，data 仍是 scan.data，方法仍 POST，未新增可支持码或端点，也未把未知 URL 默认送到优惠券端点。

原有限分析器源码（tests/lint/ui-integrity.js:546–571）已能枚举 const/ConditionalExpression 的有限字面量；没有修改检查器。具体是否同源门禁执行通过仍 NOT_RUN。

## 三处 redundant silentError 的源码证明

删除且仅删除：
- play 原:4951 的席位提交 silentError:true；
- citynode 原:519 的 /api/verify/citynode/receipt silentError:true（原 redeem 本身的既有选项保留）；
- registration-seat:62 的 /api/verify/seat/receipt silentError:true。

三处都继续保留 `autoErrorToast:false`、success、fail、successStatusAbnormal 与原 hideLoading。以下是精确父源码链，不是把两个 flag 全局视为等价：
1. app.js:376–390 的真实装配把 shouldAutoToastRequestError 交给 request-client；app.js:523–528 在 autoErrorToast===false 时关闭 HTTP200 业务错误的自动 toast。
2. request-client.js:231–242：重登失败已有 fail 就先交 fail，只有没有 fail 才检查 silentError 决定全局 toast。
3. 同文件:260–279：HTTP 非200先交 successStatusAbnormal，或已有 fail，均早于无回调的 toast。
4. 同文件:282–286：业务错误由上述 app policy 抑制全局 toast，再交 success。
5. 同文件:288–314：网络失败已有 fail 就交 fail；三处没有 retry，未引入重发。
因此删除三处冗余 flag 不新增全局 toast。没有给回调增加即时 toast 或把 done(null) 改成 failed；原时序、单次 workflow 回调和迟到守卫原样保留。

外层可见承接仍在：
- play 解析后:4957–4962：当前登录/场次守卫后 diegetic 显示原 UNKNOWN 或分类结果。
- citynode 原:495–514：原 result callback 当前页 modal 展示待确认，成功才原 toast/haptic；失败 modal 沿原分类。
- verification-readback.js:214–239：原 readReceipt 的 null/错误/不匹配结果只产生 UNKNOWN；只有原目标匹配、replayed 事实才允许移除原待办。登录/页面失效先 return，不清新旧待办。
- 参与详情原:152–164：UNKNOWN 调用原 callback(202) 和既有只读提示；goScanQR 原 callback 继续显示结果。

U5 NON_INCREASING_CEILING.u5Silent=43 不变；不增加 baseline 或 allowlist。root 最终元数据收口须同步去除 ci/silent-error-allowlist.txt 原实名三条（citynode receipt / play group_member_ticket / registration-seat receipt，父文件第3/4/5行），保留其他条目及理由，不为已删除 flag 留过期计数。root 自行处理旧 report-sheet/play 锚迁移；本 donor 没有碰这些文件。

## 配套测试源码 / root 仍拥有的锚

新直接 unit 使用真实 request-client、从实际 app.js 提取的原自动 toast policy、真实核销 workflow 和真实 caller 方法：
网络/HTTP503/408/通用业务错误/畸形成功保持 UNKNOWN；HTTP403/重登失败保留原已知拒绝分类；无全局重复 toast；原 UNKNOWN 再扫只读原席位；账号 revision 变化不清待办/不反馈新页；citynode receipt 超时不重发 redeem；详情四条真实扫码路由同原 key/data/method；未知 URL 与迟到登录不发送。

旧结构测试保留有限端点、原输入来源、三路回调、未知码拒绝和变异负控；将 lead 的 clone 形态断言改为等价的直接 literal（重复键也拒绝），将详情 scan.url 形态断言改为四个 const 条件分支及拒绝 guard。原 profile wrapper 的反馈不得覆盖身份断言保留。原第一条行号/baseline 锚用例没有修改；root 需在最终合成源码上移除已由有限表达式证明的旧 U1 豁免/重定位其余原锚，并使对应锚测试反映这个原事实，不能新增宽泛豁免。

## 验证边界

只读源码、文本编辑、Git diff、补丁 apply-check、文件摘要与限定密钥模式检查。complete patch 对精确父文件前像 apply-check 成功；限定模式未命中，不替代 root 全量秘密扫描。

全部项目 parse/import、Node/Python 测试、Java 编译、数据库、Docker、微信、HTTP/供应商与 CI **NOT_RUN**。新 unit 和旧结构适配只是源码，未宣称通过。shared patch 尚待 root 应用，donor 单独运行不代表完整组合。本目录不存内部运行日志/报告或任何秘密。
