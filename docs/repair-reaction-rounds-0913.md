# R9-03 反应挑战轮数校验（候选）

基线 `2e9350d5e`，独立分支 `codex/repair-reaction-rounds-0913`。本轮仅修轮数约束，未提交、部署或连接数据库。

## 合同与根因

第九轮审查报告 R9-03：配置 rounds=3，单个 200ms 结果也被接受。`AdvancedGameRuntimeServiceImpl.act` 从 session.configSnapshotJson 取冻结配置；`submitReaction` 原先仅判 roundsMs 为非空 List，没有对比轮数。正式客户端 playkit-reaction 的 onTap 收齐配置轮数才触发 submit，playkit-view.serverPayload 将 times 原样转换为 roundsMs。配置验证器已限制 rounds 为 1–10，因此新增提交数量必须等于冻结 rounds 的校验，无需改变正常协议。

生产修改只有三行：非空检查后对比 size 与 cfg.rounds，不符时抛出业务参数错误。未改变抢跑、服务端真实总用时、平均值判定、鉴权、幂等、版本 CAS 和奖励规则。

## RED → GREEN

- `/tmp/r9-03-reaction-probe/backend-red.log`：诊断探针编译实际服务源码并调用真实成绩处理方法；1 轮与 4 轮均意外 accepted/submitted/passed/awarded，合法三轮通过，抢跑和超时长拒绝。
- `/tmp/r9-03-reaction-probe/service-red.log`：正式测试通过公开 `act` 入口，预期少轮拒绝却未抛异常，26 tests / 1 failure / 0 errors。早期测试代码曾拼错 mapper 方法导致编译失败，已修正；这里保存的是行为 RED，不把编译错误当 RED。
- `/tmp/r9-03-reaction-probe/service-green.log`：最小修复后转绿。
- `/tmp/r9-03-reaction-probe/final-green.log`：49/49（Runtime 27、ConfigValidator 14、PublicProjection 8），无失败、错误、跳过。新增正式测试拒绝 1/2/4 轮并验证无状态 CAS、无 attempt 写入；逐一确认配置 1–10 轮的合法成绩正常 submitted/passed/awarded。旧抢跑/总用时回归保留。

命令：

```sh
mvn -q -pl chengyinhub-admin -am -Dtest=AdvancedGameRuntimeServiceImplTest,AdvancedGameConfigValidatorTest,AdvancedGamePublicProjectionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`git diff --check` 通过。测试使用真实 Service 与冻结时钟，Mapper 为 Mockito；不证明 MySQL、生产奖励执行或原生交互。诊断客户端真实 handler 在 `/tmp/r9-03-reaction-probe/client-probe.cjs`，1/3/10 轮均只在完整轮次时发一次 start/submit，payload 与后端一致；模拟计时，不代表真机。

## 独立未修复问题

正式产品文档 `/Users/arlan/Documents/城瘾/文档整理_20260711/03-进行中/节点玩法模板_十九个玩法与编辑页_施工文档_20260909.md:285` 明确「三轮取最快」，339 行规定 120ms 抢跑下限。客户端取最快，后端却按平均值：合法三轮 `[200,500,500]`、goalMs=320，客户端 win，后端 averageMs=400/passed=false。已对轮数修正版编译并运行独立 `/tmp/r9-03-reaction-probe/FastestProbe.java`，`fastest-red.log` 为可判红证据。下一补丁应独立修服务端判定与投影并覆盖失败/重试，不得据本轮绿宣称完整玩法正确。

目标上限也未统一：正式编辑页 `chengyinhub-xcx/pages/publish/temp/index.wxml:482` 提示 120–2000，前端 advanced-game-config.js 验证同范围；后端 AdvancedGameConfigValidator 常量允许 120–3000。上述产品文档未指定最大目标值；本轮不静默截断配置、不更改上限。需另行核定兼容合同。
