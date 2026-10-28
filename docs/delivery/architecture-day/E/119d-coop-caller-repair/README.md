Root composition: this proposal is WITHDRAWN and NOT_APPLIED. The final 3c8e source removes the two extra quiet reads; the existing invite allowance stays3. Never reapply this historical proposal.

# 119d 合作邀约实际 CI 首因修补（SOURCE）

父提交：119d3f87bbf89162d47db837c404b361915d1b81（公共 hengyin，tree 1d96922c384e9fd1cbacb447ef0dc2e9eb8bf407）。
5 个代码文件，原业务规则、授权、金额单位、UNKNOWN/站点冻结及全部原场景保留。两项新增回归 SOURCE；GET 矩阵新增一条精确分类（矩阵循环产生一项）。

## 已改及原断言
- coop-list-inbox.test.js：E-03 本地 load 夹具补 role 默认参数。生产 identityKey 有自己的 role，未改生产角色行为；handleReason 正例和 message 负控均保留。
- coop-invite-flow-contract.test.js：CU-M-98 两种原计价字符串/单位保持；每例新页面，type0 经真实 owner 主题响应→据点响应→显式选站再打开确认，不再把上次 type1 弹层当当前结果。串行断言改查确认时 payloads 冻结并逐项发送，原失败回执/partial success 不变；type2 UI/请求禁令不变，nodeId 可执行标识和 payload 仍被拒绝，屏蔽的仅注释/字面量扫描误报。
- get-auth-matrix-contract.test.js：新增唯一 /api/coop/supply-stations required 分类 owned-topic-target-merchant-stations；直接 GET 76→77，MATRIX 总数精确相等/原安全 matcher/负控不变。
- invite/index.js：仅 confirmInvite 原 scope 变化拒绝提示缩短为“对象或据点已变，请重新确认”，完整原说明写现有 sendResult 页内；仍拒绝且零写，不调整错误类别/允许范围。
- coop-supply-station-contract.test.js：原站点/账号/生命周期/UNKNOWN/历史缺归属场景不删；原变更拒绝例补页内说明/短 toast，新增站点 GET 和 owner 主题类型读取错误可见/重试/零写/无双 toast 两例，包含传输、异常状态、非法正文。

## 原 producer 与范围（未改后端）
ApiCoopController class /api/coop 下实际已有 @org.springframework.web.bind.annotation.GetMapping("/supply-stations")，参数 topicId、merchantMemberId。
先取认证身份，主题 owner 或 merchantAccessService PROJECT_MANAGE 派生 owner 并重验主题归属，再走 CoopInviteServiceImpl.listSupplyStations。
后者重验 classic TOPIC 商家适用性、原招商窗口、活跃商家；CoopInviteMapper eligibleSupplyStation 为同 topic_id/member_id + audit_status=1 + del_flag=0，id ASC，业务层排除有效原单已占用站点。
不新增 route，不改权限；root 单独将原注解改短名供现有扫描器识别。

实际 caller refreshSupplyStations 用 app.sendRequest GET、未传 auth:false；app→request-client 原默认 Authorization。
请求按 identityKey(member/role/type)、topic、viewEpoch、target 和 topicEpoch 验证迟到响应。账号/主题/隐藏/销毁变更不得附着旧响应；原 H login-reload 会话恢复保留。失败只写原页面错误态，不创建邀约。创建 UNKNOWN 保留原 payload/站点，不重新执行。
GET 不用公开活动站点列表，接受页仍读原 invite ID/name，未调用 creator-only 选站口。

## 共享一行 review（root 决定应用）
shared-silent-error.review.patch 不直接修改共享 allowlist。
此页实际5处：原 loadMyTopics、loadTargets 的 merchant/clubs 与 club/merchants 共3处；新增 loadSupplyTopic 分页 POST + refreshSupplyStations GET 共2处。
原3处有 topicsErrorText/targetsErrorText 和 cy-error/retry；新增分别有 supplyTopicError、item.supplyError，经 WXML cy-error + retrySupplyTopic/retrySupplyStations 显式展示，失败禁止继续发邀约。401/sessionChanged 沿原 H helper，非吞错。无新静默理由或通配。
root 的 UI baseline、action-ledger、API scanner/Controller 短注解和 official-inbox 不在本供体代码内。

## 交付身份及未运行
source-manifest.json 逐项记录119d preimage blob/SHA256、postimage blob/SHA256，五个 preimage 与公共119d树精确一致。
owned.patch.b64 仅5代码文件：decoded SHA256 d1c4d5b68c0467d61e4c1f534a8e4f1fa7d93e99ea43965194fd72c853828062。
doc/共享review不在此代码patch hash内。
已做的仅源读取、字节/身份哈希、git diff 与 apply --check；初次打包器 NUL 转义错误被更正后preimage身份匹配，非测试结果。
原旧工作树未修改；Shell Git proxy:8080不可用，使用现有公共GitHub工具精确读取/建tree/commit/ref，不改代理/凭据。
本环境没有可用 gitleaks；仅补丁固定敏感签名检查，不替代root完整secret scan。
Node/微信/Java/编译/DB/HTTP业务调用/故障/CI：全部 NOT_RUN。现有失败日志仅用于定位，不能作为本候选通过证据。
