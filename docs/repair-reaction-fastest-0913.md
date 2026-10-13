# R9-03 后续：反应挑战按最快成绩判定（候选）

基线 `ff9e12ac9`（轮数补丁已提交），独立工作区 `repair-reaction-rounds-0913`。本轮未提交、未发布、未连接数据库或开发者工具。

## 产品依据与最小实现

正式产品文档 `/Users/arlan/Documents/城瘾/文档整理_20260711/03-进行中/节点玩法模板_十九个玩法与编辑页_施工文档_20260909.md:285` 明确变色就点「三轮取最快」，并要求随机等待和抢跑单独判。正式客户端已按配置轮次收集 times、按最快值显示胜负，再经 serverPayload 转为 roundsMs。服务端原先使用平均值判分，合法 `[200,500,500]` 在 goalMs=320 时前端显示 win，后端却 passed=false。

本轮生产只修改 AdvancedGameRuntimeServiceImpl：在现有逐轮合法校验内累计最小值；校验总用时后保存新增 bestMs，以 bestMs 判定 passed；averageMs 仍存真实平均值。公开投影仅在状态存在 bestMs 时输出它。没有新增迁移、客户端参数、配置字段或依赖，不信请求传入的 bestMs/passed。

## 历史与重试兼容

- 仅尚未 submitted 的新成绩计算 bestMs。旧会话缺各轮原始值，绝不从 averageMs 猜最快值、伪改旧 passed/awarded 或补奖。
- 已提交会话读状态只投影已有字段；旧状态无 bestMs 就不输出，真实旧 averageMs 保留。
- 同幂等键直接回放 PlayAttemptLog.resultJson，不重新判分；不同键重复提交仍由 submitted 守卫拒绝。
- awardOnce 仍只置本局 awarded 标记；实际发奖属于原完成链路。本测试不声称证明生产奖励发放或真实 MySQL 并发。
- 轮数、120ms 抢跑下限、整数检查、服务端真实总用时、版本 CAS、权限与已有状态机不变。目标上限 2000/3000 差异不在本补丁内。

## RED → GREEN

正式回归走真实 Service 公开 act/state，Mapper 为 Mockito，时钟固定；无网络和数据库。

- `/tmp/r9-03-reaction-probe/fastest-service-red.log`：28 tests / 1 failure / 0 errors / 0 skipped。合法三轮 `[200,500,500]` 期望 passed=true，实际 false。
- `/tmp/r9-03-reaction-probe/fastest-service-green.log`：最小修复后同测试转绿。
- `/tmp/r9-03-reaction-probe/fastest-final-green.log`：55/55，0 failure/error/skipped（Runtime 33、ConfigValidator 14、PublicProjection 8），编译与 diff-check 通过。

本轮新增6项测试，覆盖最快达标但平均未达标、bestMs/averageMs回读、120/320/321边界、伪造客户端bestMs/passed被忽略、未开局和各类非法单轮不落结果、同键原回执与异键重复提交、旧成功/失败状态回读不补bestMs且不写账。此前合法1–10轮、少/多轮拒绝、抢跑和超服务端总用时测试继续通过。

```sh
mvn -q -pl chengyinhub-admin -am -Dtest=AdvancedGameRuntimeServiceImplTest,AdvancedGameConfigValidatorTest,AdvancedGamePublicProjectionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 独立待定项

编辑器提示及前端验证允许120–2000，后端配置验证允许120–3000。独立只读探针 `/tmp/r9-03-reaction-probe/goal-2500.cjs` 确认2500在parse/normalize/playKit保持原值，编辑器保存明确报错，不会静默截断。统一新配置上限及现存2001–3000配置的编辑兼容政策另行决定；本轮不改。

## 总控集成验证

三文件冻结哈希核对一致，双轴独立审查0项（Standards独立55/55）；source `04a3edc23` 已集成root `6e652a178`。root重跑上述三组55/55，无失败/错误/跳过，XML副本与汇总 `/tmp/cy-reaction-fastest-root-summary.json`，日志 `/tmp/cy-reaction-fastest-root.log`。

root正式客户端handler探针覆盖1/3/10轮与 `[200,500,500]`，只在收齐轮数后发一次提交，payload正确且最快200判胜（`/tmp/cy-reaction-client-root.log`）。这是受控计时handler测试，不是真人/真机反应速度验证。2500配置保留及编辑拒绝已在root重验（`/tmp/cy-reaction-goal2500-root.log`），产品上限问题已提问、尚无答复。未部署，旧成绩不补算或补奖。
