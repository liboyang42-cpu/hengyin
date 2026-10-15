# B01 → Claude 稳定交互合同

本 lane 仅修既有 JS/工具行为，无新增页面、字段或后端接口。切勿另建音频/轮询/订阅 helper。需要编辑本 lane 的同一 JS 时，经协调者串行整合。

## 退款点击

三个既有场景入口分别是订单详情 cancelRegistration、订单历史 cancelOrder、参与详情 cancelParticipation。保留原确认弹层。在用户确认的同步调用栈内调用 `subscribe.request(['refund', 'refundRejected'])`，不 await、不以结果阻断退款请求。

- 付费取消：`POST /api/registration/cancel-refund`，原请求 `{ id }`。
- 待支付取消：`POST /api/registration/cancel`，原请求 `{ id }`，不会额外申请退款通知。
- 当前身份、本人订单归属、退款资格仍由既有服务端校验；客户端授权只用于消息提醒，不能作为退款授权或退款成功证据。
- HTTP/业务回执语义未变；成功继续使用 `cancellationFeedback` 并重读现有订单事实，不本地猜成“已退款”。未新增 requestId 或版本字段。
- 订阅 helper 仍返回 `accepted / partial / rejected / not_configured / unsupported / failed`；授权成功不承诺送达。模板空值保持 not_configured，clubInterest/roamRecall 未配置，未借用任何模板。

Fixture：用户点击 paid fixture 订单 ID=7 → 确认 → 请求 refund/refundRejected 两模板 → 微信 fail/reject → 仍且仅一次发送 `{ id: 7 }` 到 cancel-refund。这是测试数据，无真实账号和资金。取消确认不发订阅、不提交退款。设备端需验证自定义确认弹层手势资格。

## 收藏稳定身份

主题取消仍 `POST /api/topic/like`，`{ id, type: 0 }`；帖文取消仍 `POST /api/creativesquare/bookmark`，原 `{ id, bookmark: 0, request_id }`。既有 per-ID 在途锁保留。只按稳定 ID 更新当前列表，不凭旧 index 摘行。分页、响应和请求身份格式未变。

Fixture：点 ID=1 时 index=0；等待期间列表刷新为 `[2,1,3]`；取消成功后为 `[2,3]`。账号从 11 切到 22，旧列表/取消/complete 回调不修改 22 的列表或 loading。切号仅清本页面数据，未全局清缓存。新 UI 排序/筛选应继续使用业务 ID。

## 游玩生命周期和身份

页面绑定进入时账号；切号后返回旧游玩页先停轮询/定位/音频并通过已有首页路由离开，需以新身份重新从业务入口进入。首屏 `/api/play/nodes`、队伍 `/api/club/lead/team-progress` 和暂停 `/api/play/run-session*` 等现有 HTTP/数据字段保持原样。gameSessionClient 的 requestId、版本、明确失败/未知回执和重试规则不变。

- 隐藏/卸载前发出的队伍进度不能重新启动 poller。正常慢响应仍按已应用请求序列接受，保留原防饥饿规则。
- 音频系统中断结束只恢复仍拥有中断恢复资格的 context；onHide 的主动 pauseAll 会撤销资格。显式 resumeAll 行为保持。
- 暂停 key 仍按 member + activity/topic 分隔；旧页面不把 A 的快照、邀请 session、玩家 pending 写入 B 的 key。已排队但尚未发出的 A 保存不会在 B 身份下发。
- 玩家投影/写回执/回读在切号或卸载后丢弃；旧命令不改写新账号 UI/存储。未知结果继续先核对原 requestId，不构造新请求身份。

Fixture：A=11，activity=9；A 首屏在途 → 切 B=22 → A 的旧响应不应用；A 暂停只允许写 `play_paused_run_v2:m11:a9`，切号后队列不发送保存；A 邀请成功也不能产生 B 的 `circle_session_m22_t8`。测试仅模拟客户端调度，不证明服务端权限/设备行为。

验收 fixture 和完整输出见 `b01-lifecycle-account.test.js`、本目录 REPORT.md。新恢复面板、通知文案/布局、筛选入口属于 Claude；不要改动 Codex 的原请求身份和权限/资金事实。
