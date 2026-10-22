# Doc 2 · O-03 / O-09 交付说明(先验证、只修已证实的缺口)

分支:`feature/doc2-o3-o9-resume-20261022` · 基线:master `c225b462`

> 本文只陈述测试证据。**没有做任何真机验证**,真机项见文末清单,需仓库所有者在真机上逐条确认。

## 范围

- **O-03** 游戏命令结果未知(UNKNOWN)与跨设备续玩。#424 #433 #406 #407 已合入,本次只验证跨设备部分:设备 A 开局,设备 B 接着玩;状态与计时一致,以服务端时钟为准。
- **O-09** 切后台、来电、改系统时钟;收藏护照与勋章计数。#451 #432 已合入部分,本次验证:改手机时钟不影响结果与计时、切后台再回来能恢复状态、恢复后护照与勋章计数不变。

## 核实结论

| 项 | 结论 | 证据 |
|---|---|---|
| 同一会员两台设备(两个登录 token)核对同一条 UNKNOWN 命令 | ✅ 原本就正确:回执按 `(activityId, memberId, requestId)` 存取,和设备、token 都无关。B 先读到 PENDING,落库后 A、B 读回、B 原样重发,拿到的都是同一份回执(同一个 receiptId 和 revision,`replayed=true`),也没有产生第二次效果。B 用同一请求编号发不同内容会得到 `GAME_IDEMPOTENCY_CONFLICT`;其他会员只能读到 PENDING | `GameSessionRuntimeServiceImplTest#crossDeviceSameMemberResolvesUnknownCommandThroughOneServerReceipt` |
| 两台设备看到的游戏状态与时刻 | ✅ 原本就正确:`view` 不接收任何客户端时间,`snapshotAt` 和 revision 都取自服务端时钟,两台设备读到的投影完全一致 | `GameSessionRuntimeServiceImplTest#crossDeviceViewsShareServerClockSnapshotAndState` |
| 跨设备暂停快照(`/api/play/run-session`)的先后判定 | ❌ **缺口,已修**,见下节 | `ApiPlayRunSessionControllerTest#cross_device_ordering_uses_server_clock_not_skewed_device_clocks` 与 `o3-o9-resume-server-clock.test.js`,改动前均为红,改动后为绿 |
| 切后台、来电(onHide)后改手机时间 | ✅ 原本就正确:onHide 会冻结用时并落盘;后台期间把时间拨快一天,回来后用时不变,也不会自己接着走;玩家点「继续」后才从那一刻起算 | `o3-o9-resume-server-clock.test.js` 第 5 例 |
| 设备 A 暂停后设备 B 继续 | ✅ B 恢复的用时就是服务端那份;B 继续玩后再暂停,写回的是接着累加的用时;本机快照记的是服务端时刻 | `o3-o9-resume-server-clock.test.js` 第 6 例 |
| 勋章墙计数 | ✅ 原本就正确:回前台时同一账号不会重新拉取,计数不变;重新回读同一份服务端数据,计数和获得日期也不变(日期取服务端时间字段) | `o9-badge-wall-resume-counts.test.js` |
| 漫游护照读数 | ✅ 读数完全由本机 `roam_sessions` 算出(漫游次数、探店、活跃日、公里),不读时钟,恢复后重算结果相同。没有服务端真源,不在本次修复范围 | 代码核对 `pages/roam/index.js` `_loadIntroPassport` |

## 已修复的缺口:跨设备新旧用的是手机时钟

**现象(已用测试证明):** `/api/play/run-session/save` 和 `/clear` 直接把客户端传来的 `savedAt`(手机的 `Date.now()`)写进库,判断新旧也靠它:mapper 用 `values(saved_at) > saved_at` 和 `saved_at <= #{savedAt}`。

- 设备 B 的时钟拨快一年后暂停,写进库的 `saved_at` 是「未来时刻」。之后设备 A(时钟正常)结束这一局时,墓碑的 `savedAt` 比库里的小,`markEnded` 一行也不更新。结果是:**已经结束的局,在 B 上还能恢复**。
- 时钟拨慢的设备反过来:它新的暂停会被当成旧数据丢掉。

**修复(最小改动):**

1. 后端 `ApiPlayRunSessionController`:落库和回传一律改用服务端时刻 `serverNowMillis()`,并通过 `AjaxResult.data.savedAt` 返回给客户端。仍然要求客户端带 `savedAt`(兼容老版本、做入参校验),但这个值不再参与排序。没有改表结构,也没有改 mapper。
2. 小程序 `utils/play-run-session.js`:
   - `saveServerPausedRun` 收到服务端回传的 `savedAt` 后,如果本机快照还是这一次写的(用时相同,`savedAt` 不早于本次),就改记服务端时刻。这样之后和另一台设备的暂停、墓碑比新旧,都是在服务端时钟上比。老服务端不回传时,本机快照保持不变。
   - `writePausedRun`:本机后写的快照,`savedAt` 至少比前一份大 1。原因是手机时钟比服务端慢时,断网暂停如果按手机时钟落盘,会比已经采纳的服务端时刻还旧,重进时会被服务端那份旧用时覆盖。
3. `pages/play/index.js` 只改了一处:调用 `saveServerPausedRun` 时多传一个 `wx`。

**负控:** 撤回上述实现后再跑,controller 新用例和既有 3 个用例都变红(它们原来断言的是「透传客户端时间」);小程序新用例中第 1、4、6 例变红。恢复实现后全部变绿。

## 测试证据(本地)

- `mvn -pl chengyinhub-admin test -Dtest=ApiPlayRunSessionControllerTest,GameSessionRuntimeServiceImplTest`:5 + 129 例,0 失败。
- `chengyinhub-xcx`:`npm run test:unit` 共 13580 例,0 失败;`lint:scope` PASS;`lint:ui` PASS;`audit:actions --check` PASS(台账没有变化,没有重新生成)。
- 最终以 GitHub CI 结果为准。

## 已知限制(本次未修,如实记录)

1. **前台运行中改手机时间,显示的用时会跟着跳。** `createSessionClock` 用的是 `Date.now()`。这个用时只用于显示(运行栈、复盘里的「用时」),不影响任何服务端结果。要修需要单调时钟,但 `wx.getPerformance().now()` 在真机前后台切换时是否单调,本仓库无法在 node 里证明,所以没有改,列入真机检查。
2. **断网暂停 + 手机时钟偏差 + 另一台设备随后操作**,三者同时出现时:这份没同步到服务端的本机快照,只能用手机时间和服务端时间比较新旧,仍然受时钟偏差影响。要彻底解决需要客户端联网后重推,超出「最小修复」范围。
3. 零血倒计时(`theme-backpack.js`)用的是服务端 `serverNow/deadlineAt` 加本机经过时长。前台运行中改时间只影响倒计时显示,判定在服务端;回前台(onShow)重新轮询后会重新对齐。

## 留给所有者的真机检查(未做,不得视为已验证)

- [ ] 两台真机登录同一账号:A 开局走一段后暂停或切后台,B 进入同一场,恢复的用时与 A 一致,提示「已同步另一台设备上的进度」。
- [ ] B 的系统时间手动拨快一天以上:A 结束这一局后,B 重新进入应显示 idle(不还魂)。B 拨快的情况下暂停,A 再进入,应恢复 B 的用时。
- [ ] 弱网或飞行模式:A 发出一个玩家选择、提交后立刻断网(显示「正在核对上次操作」),在 B 上打开同一场,B 看到的是服务端的真实结果;A 恢复网络后核对到同一份回执,没有重复生效。
- [ ] 游戏进行中接一个来电(30 秒以上)或切到其他 App,回来后:运行栈处于暂停态,用时等于离开时的值,点「继续」后正常计时,定位仍可用。
- [ ] 游戏进行中(前台)在系统设置里修改时间:记录显示的用时是否跳变(已知限制 1),并确认节点完成、徽章发放、排行没有受影响。
- [ ] 后台期间修改时间再回来:勋章墙点亮数和锁定数、各徽章获得日期不变;漫游护照四个读数不变。
- [ ] 验证 `wx.getPerformance().now()` 在 iOS 和 Android 前后台切换、锁屏、改系统时间时是否单调,作为修复已知限制 1 的依据。
- [ ] 线上部署后端 jar 后,用老版本小程序(没有本次客户端改动)和新版本混用两台设备,确认暂停恢复与结束墓碑都正常(服务端已不依赖客户端时间)。
