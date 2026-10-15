# B01 现有交互与账号作用域修复

基线：`d551c6babd018d41aec90dad2c0acaf1c534644d`。2026-10-15，协调者集中提交；本 worker 未切分支、未 add/commit、未修改其他 lane 的文件。源码/Node 行为层完成，未宣称设备或真实业务验收完成。

## 实际修改

- `chengyinhub-xcx/utils/audio-session.js`：主动 pauseAll 即使 context 已被系统中断暂停，也撤销该 context 的 interrupted 标志；保留显式 resumeAll 的既有记账。
- `chengyinhub-xcx/pages/mylike/mylike.js`：主题取消按稳定 ID 过滤当前列表；保留 per-ID 在途锁。列表和取消请求回调按页面账号/代次隔离；切号清本页列表及本页在途标志，旧 complete 不结束新请求；卸载后的回调丢弃。未清全局 storage。
- `chengyinhub-xcx/pages/play/index.js`：队伍进度应用增加页面生命周期和原账号检查，隐藏/卸载后旧请求不能更新进度或重新启动轮询；首屏请求应用按加载代次保护，隐藏时失效，返回重新加载。页面绑定原账号，切号返回旧页面时经既有首页路由退出旧会话；邀请响应不能写入新账号 circle-session key。暂停快照、排队服务端保存、服务端恢复和玩家 pending/角色 key、玩家投影/写回执增加原账号检查。保留原 requestId、回执核对、未知态与计时合同。
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js`：付费取消确认的用户手势内，同步申请 refund/refundRejected。
- `chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js`：同上，沿现有已支付 status=2 分支。
- `chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js`：同上，沿现有 paymentStatus=2 分支。
- `chengyinhub-xcx/tests/unit/b01-lifecycle-account.test.js`：14 个真实处理器/工具行为用例。

未改 utils/subscribe.js、utils/poller.js；复用既有实现。未改 UI、WXML/WXSS、动作清单、门禁基线、公共请求封装或后端。

## 复现和处置

| 线索 | 证据和处置 |
|---|---|
| 系统中断 → 页面隐藏 → 中断结束会后台复播 | 真工具行为基线失败；修复后仅 pause，显式 resumeAll 才 play。 |
| 页面隐藏/卸载后队伍进度晚成功重新启动 poller | 真实 loadTeamProgress + onHide/onUnload 基线失败；修复后不应用旧响应、不启动 poller。 |
| 主题取消因刷新排序而误删另一条记录 | 真 topicUnlike 基线从 `[2,1,3]` 误删为 `[1,3]`；修后仅移除 ID=1，保留 `[2,3]`。 |
| 收藏旧账号列表/取消回执进入新账号 | 基线失败；修后旧 success/complete 均不能改变新账号列表或新请求 loading。 |
| 退款三处均遗漏退款通知授权 | 三个实际确认处理器基线均零授权；修后确认手势同步请求两个真实模板，微信拒绝仍发送原退款请求。 |
| 邀请加入的晚响应写入 B 缓存 | 扩展用例复现 `circle_session_m22_t8=99`；修后无缓存写入。 |
| 游玩首屏、暂停保存/恢复、玩家投影账号串用 | 首屏晚回包和玩家投影旧失败可复现；原账号闸保护首屏、缓存、排队发送和玩家回执。排队保存测试验证切号前写本地 A key，切号后不以 B 身份发请求。 |
| PR13 poller 工具缺陷 | 本轮未复现工具本身缺陷；已有成功退避、不叠发、stop 后不续排用例 5/5 通过，修复放在页面调用方。 |
| 既有迟到回调/游标保护 | 复用 IM/广场 recovery 用例 16/16，通过旧发送、旧追加、账号切换和游标保护；未重复开发。历史 P1-048/P1-069 未另造实现。 |

## 实际测试输出

所有运行均为 Cloud 轻量 Node，不运行全量套件、Java、MySQL/Redis。输出原样保存在本目录。

- `before.txt`：基线新增 9 用例，`tests 9 / pass 0 / fail 9`。失败均是业务断言，非加载异常。
- `before-extra.txt`：初修后的扩展取证，`tests 3 / pass 1 / fail 2`（新抓到邀请跨账号缓存、玩家投影旧回调；排队保存已通过）。
- `after-initial.txt`：初始修复，9/9；保留过程证据，不作为最终覆盖数。
- `final-behavior-tests.txt`：最终 14/14，`fail 0 / cancelled 0 / skipped 0`。
- `related-node-tests.txt`：124/124，`fail 0 / cancelled 0 / skipped 0`。
- `game-refund-node-tests.txt`：82/82，`fail 0 / cancelled 0 / skipped 0`。
- `order-components-node-tests.txt`：36/36，`fail 0 / cancelled 0 / skipped 0`。
- `reused-recovery-node-tests.txt`：16/16，`fail 0 / cancelled 0 / skipped 0`。

`final-behavior-tests` 与 `related-node-tests` 含重复覆盖，不相加宣称独立测试数。`git diff --check` 对本 lane 的源码文件输出为空、退出 0。

复跑命令入口如下（工作目录仓库根）：

```bash
node --test chengyinhub-xcx/tests/unit/b01-lifecycle-account.test.js
node --test chengyinhub-xcx/tests/unit/b01-lifecycle-account.test.js chengyinhub-xcx/tests/unit/audio-session.test.js chengyinhub-xcx/tests/unit/poller.test.js chengyinhub-xcx/tests/unit/mylike-refresh-state.test.js chengyinhub-xcx/tests/unit/mylike-unlike-inflight-lock-contract.test.js chengyinhub-xcx/tests/unit/subscribe.test.js chengyinhub-xcx/tests/unit/subscribe-recipient-contract.test.js chengyinhub-xcx/tests/unit/play-mechanisms-contract.test.js chengyinhub-xcx/tests/unit/play-cross-device-resume.test.js chengyinhub-xcx/tests/unit/play-pause-restore-contract.test.js chengyinhub-xcx/tests/unit/play-request-recovery.test.js chengyinhub-xcx/tests/unit/play-lead-tools-entry-contract.test.js chengyinhub-xcx/tests/unit/play-route-page-behavior.test.js
node --test chengyinhub-xcx/tests/unit/player-game-module.test.js chengyinhub-xcx/tests/unit/player-game-session-entry-contract.test.js chengyinhub-xcx/tests/unit/scene-member-participation-truth-contract.test.js chengyinhub-xcx/tests/unit/order-list-refund-deadline-truth.test.js chengyinhub-xcx/tests/unit/cancellation-feedback.test.js chengyinhub-xcx/tests/unit/play-timer-cleanup.test.js
node --test chengyinhub-xcx/tests/unit/order-detail-parent-refresh.test.js chengyinhub-xcx/tests/unit/order-detail-closeout-t3f.test.js chengyinhub-xcx/tests/unit/order-history-single-source-contract.test.js
node --test chengyinhub-xcx/tests/unit/im-foundation-recovery-b.test.js chengyinhub-xcx/tests/unit/square-feed-pagination-b.test.js
```

## 未验证层与回退

微信开发者工具/真机订阅弹窗、实际来电与锁屏、弱网设备切号、后台定位和音频能力、真实退款/通知下发与独立服务端业务读回、GitHub CI/整合候选均由 C99/设备验收续做。Node 沙箱证明回调/请求次序与本地数据读写，不证明已退款或订阅必达。所有权以最终协调者提交 SHA 为准。

发现旧快照不可读、错误账号读写、重复资产/付款变更时停止推广，回退本 lane 文件变化；不删账本/历史迁移，不回退其他 lane。没有改变资金/隐私/权限政策。

## 集成门禁追加：收藏请求字面配置（2026-10-15 冻结）

根集成人发现 `_favoriteRequest(options)` 动态转发使 U1 无法枚举请求，并使 silentError 的静态回调不可见。按追加授权仅改 mylike.js 和 B01 专属测试：移除动态 sendRequest wrapper，四处恢复 `app.sendRequest({ ... })` 字面配置；用 `_isFavoriteResponseCurrent(owner,generation)` 纯布尔助手，在列表 success/fail/successStatusAbnormal/complete 和取消 success/fail/complete 显式校验原账号、代次及卸载状态。保留主题/帖文稳定ID摘行、在途锁和原请求字段。未加白名单、未改门禁/动作清单/基线。

`b01-lifecycle-account.test.js` 新增 posts/topics × 切号/卸载四项，调用真实加载处理器并依次触发旧 success/fail/abnormal/complete，确认当前页面状态不变。与 mylike-refresh-state、mylike-unlike-inflight-lock-contract 合跑 23/23，fail/cancelled/skipped 均0，实际输出 `mylike-literal-request-tests.txt`。本次 git diff --check 限上述源码/测试无输出退出0。未重新跑全量、Java或DB；设备层仍待验。
