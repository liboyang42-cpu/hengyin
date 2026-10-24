# WP04：创建包装在候补 claim 前取得原父 topic

代码/静态完成，Java、MySQL、CI 均未运行。直接父 `b681cf0604d2e039c59ee1ca5034a71ec4525801`，独立分支 `codex/architecture-day-a-wp04-create-parent-fence`。

精确入口是 `IRegistrationCreationTransaction.create` → `CmsRegistrationServiceImpl.create` → `claimForOrder` → `createRegistrationInternal`。旧 claim 的 `ClubWaitlistServiceImpl.validateOffer(forUpdate)` 先取 `selectSeriesPolicyByActivityForUpdate`（联查 activity），探索票后续又可能取 topic；它会与支付 topic→activity 形成反序。本片只加8行：活动请求按 DB 原活动先锁真实 topic，随后仍执行原 claim，再在原 claim 后以 activity 当前锁读验证原 topic/club/member。没有将 activity 提到 series 前，没有改 offer/token、库存、扣款、退款、请求 fingerprint 或成功回执。自玩仍走原 topic owner 路径，独立活动不借用相同数字 topic。

新6项普通单测只验证准入顺序/原归属拒绝，未把受控提前停止当创建成功。原 `RegistrationTopicFenceMySqlIT` 保留13项正文逐字节并新增1项（现14）：真实 topic 持锁时，窄 claim delegate 尚未进入并取 activity；释放后 delegate 取得真实 activity 锁并抛错，原交易整体回滚，原 registration/refund/inbox 行数/支付态不变。该 delegate 不是实际 offer/token SQL验收；合法创建、余额/积分/库存仍须 root 同批跑原资金测试。

实际只执行 Python/冻结 scan_java/git diff --check，见 static-result.json。无 B/G/ClubWaitlist/C 文件、DDL、依赖、CI 变更；不包含 B6d 类型接线。B真实事实另在独立候选施工，不把 pending 当验收成功。本文件同时更正上一报告的方法便称：真实方法为 `create(...)`，不是 `createRegistrationFromRequest`。
