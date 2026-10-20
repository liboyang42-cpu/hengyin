# E06 平台生命周期补充回归

CY-HANDOFF: CODEX

当前状态：`SOURCE_DELIVERED_UNVERIFIED`。用户最新要求全部暂停验证；下文命令仅供用户下次开电脑后由本地验证方重新授权统一执行，现在不运行、请求运行或等待任何 Node/Java/Maven/数据库/DevTools/业务验证，也不手动重跑 CI。既有历史结果保留，不迁移为新提交的验收。
- 线路：E06 / EXTERNAL_E06_PLATFORM；状态：测试交付待 C00 审阅，源码缺陷未修复。
- 工作树：`/workspace/hengyin-extra-e06`；分支：`codex/extra-e06-regression-20261015`。
- 公共基线及当前 HEAD：`1e403b135206844a34219753a36e2dee39c7dd59`。
- 控制快照：`b8541ad2407d0a360588ab77ad32fe3d1c9eb71e`，读取 `/tmp/chengyin-extra-six-context/` 的 EXTERNAL_SIX_HANDOFF、OWNERSHIP、FOLLOWUP_BUG_LANES、BACKEND_CHANGES、COORDINATION；根及小程序 AGENTS 已读。
- 只新增本 README 与 `chengyinhub-xcx/tests/unit/b01-platform-lifecycle-regression.test.js`；无暂存、提交、push、源码或旧测试修改。
- NO_API_CHANGE；TEST_DEPLOYED=NO。

## 已有覆盖对照与新增差异

| 已有测试 | 已有行为 | 本次差异 |
| --- | --- | --- |
| audio-session.test.js | 单次监听、在播恢复、注销不恢复、暂停恢复、keepAwake 共享释放、缺 API 降级 | 同一 context 注销后重新注册，旧 interruption end 与显式 resumeAll 都不能继承旧恢复资格 |
| b01-lifecycle-account.test.js | interruption→hide→end 不后台续播，页面隐藏/卸载轮询与账号隔离等 | 不再重复页面场景；仅测基础模块 |
| poller.test.js | 节拍/退避/同步抛错/reject、single-flight、stop 后响应不再排队 | 旧请求仍 pending 就 stop→start，新代 timer 已触发后旧 false/reject 到达；验证不叠发、不污染新代退避。旧用例实际在旧响应完成后才重新 start |
| bg-location-tracker.test.js | 前后台降级、引用计数、force、同 key 幂等、释放后迟到成功补偿/失败静默、共享启动/失败、异步 stop 串行 | 旧启动成功满足替换 owner 后新代失败无影响；success/fail 批次第一个消费者在回调中释放其他消费者，剩余旧 waiter 必须取消，success 还覆盖同 key 重获 |
| location-manager.test.js | 选址归一化、坐标闸、取消、权限引导/授权重试、定位失败提示 | 该模块是用户主动的一次性请求，未提供 stop/dispose；不凭空增加生命周期或权限合同 |
| last-location.test.js | 坐标缓存/非法坐标/存储异常回退 | 同步缓存无在途回调与 stop/dispose，本次不重复 |

## 方法、数量与结果

执行实际导出函数。tracker 在每例独立 Node VM 中执行原始完整模块；只有 wx 回调队列被替换，不改写源码、不用 source 字符串匹配。poller 使用注入 timer 队列与手动 deferred promise，微任务确定性排空，无真实等待或网络。音频直接驱动真实 createAudioSession。

| Node case 名称 | 预期 | 基线实跑 |
| --- | --- | --- |
| poller restart before old false result preserves new cadence | 旧失败不退避新代，single-flight 后继续 | PASS |
| poller restart before old rejection preserves new cadence | 旧 reject 不退避新代，无未处理异常 | PASS |
| audio unregister then re-register same context cannot inherit interruption resume | 只有 pause，没有 play | PASS |
| GPS old start success can satisfy replacement owner without reviving released waiter | 只通知新 owner，旧失败不清理新状态 | PASS |
| GPS success batch cannot notify released waiter after same-key reacquire in another consumer | 先只通知 first；新 start 成功后才通知 new-second | FAIL |
| GPS failure batch respects consumer release performed by earlier failure callback | 只通知 first，已释放 second 不再收到 fail | FAIL |

预期发现/执行 6 个 case。实际 Node `v24.19.0`，2026-10-15：tests=6、pass=4、fail=2、cancelled=0、skipped=0、todo=0，进程退出码 1。上述运行发生在 C00 转达“暂缓新的 Node 验证、待用户下次开电脑集中处理”通知之前；通知后未再运行 Node。失败测试保留原断言，无 skip/todo。未沿用冻结 231 项结果。

精确命令（工作目录 `/workspace/hengyin-extra-e06`）：

```sh
node --test chengyinhub-xcx/tests/unit/b01-platform-lifecycle-regression.test.js
```

## 缺陷与交原写者的最小方案

1. `chengyinhub-xcx/utils/location/bg-tracker.js:60-61`，`finishSuccess`：acquire first 与 second；first success 内 force release，再 acquire 同 key second；触发原启动 success。实际 `['first', 'released-second']`，期望 `['first']`。旧 waiter 只检查 key 仍存在，借用了新 owner 的身份。新 owner 此时还在等 stop 完成，不应让旧页面收到 success。
2. 同文件 `:70-74`，`finishFailure`：acquire first 与 second；first fail 内 release second（force）；依次触发 background fail 与 foreground fail。实际 `['first', 'released-second']`，期望 `['first']`。失败批次预先筛选并清空 owners 后，后续 release 无法取消已摘出的 waiter。

已即时报 C00，交原写者处理；本次没有源码修复。建议 owner acquire 与 waiter 共享独立身份/取消令牌，release（含 force）使相应令牌失效，两个批次每次调用前校验。failure 清理 owners 时仍需保留分发批次可取消信息，不能简单加 owners[key] 判断而吞掉全部正常失败。保持现有降级、共享引用计数及晚成功补偿 stop 语义。方案待 C00/原写者审阅。

## 验证边界

未运行 Java/javac/Maven、MySQL/Redis、服务、CI、VPS、生产或网络/SSH变更。未验微信开发者工具、真机 GPS/音频、iOS、Flutter、真实业务/账号或运行包绑定。没有页面 UI 修改，截图要求不适用；无发布、上传、合并、部署或 GPS 用途/权限扩展。Node fixture 只证明上述基础模块状态机行为。
