# 第十一轮 i3-lead-admission 结果

分支 `integ/launch-test-20261103`(lane 私有分支 `i3-lead-admission` 先验,再 pull --rebase 后普通 push,无 force)。
对象:`PlayLeadAdmissionFailureMySqlIT`(real-storage-it.yml,批次 architecture-day-lead-admission / architecture-day-integrated-changes)。
本 lane **只改该 IT 夹具**,未改生产代码、未改金额规则、未跳过/删除/放宽任何断言。

## 结论:夹具漂移,不是带领闸被绕过

### 先更正 i2 的一个事实

i2 记「master `c6eac3b8c` 上同样 4/4 红」不准确。master 运行 [41956731939](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956731939) 的日志是 `Tests run: 4, Failures: 2`,只有两条偏好用例红;
hint / answer 两条只在本分支红(本分支运行 [41957583233](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957583233) 为 4/4 红)。两组红的原因不一样。

### 原因一:hint / answer(本分支红,master 绿)→ B 类,P-13 之后夹具漂移

- 夹具把主题建成 `product_type=2`(自由探索)。
- P-13 `c6b2f8db`(只在本分支,不在 master)让 `PlayNodeAccessService.leadGateError` 遇到自由探索**先返回**,不再读 `club_lead_state`:只看共享路线局是否已结束。自由探索按产品决定不带队,也没有章节闸(master 上原本也在读完带领态后对自由探索 `return null`,不按章节放行)。
- 所以 club_lead_state 读不了的时候,自由探索场次**没有可以被绕过的带领章节闸**,这不是漏洞。P-13 当时已经同步了同类单测(`PlayLeadAdmissionFailureTest`、`ApiPlaySensorResultTest`,都断言自由探索 `never().selectByActivity`),但没有同步这条真库 IT。
- 对线性(带领)场,`leadGateError` 在路线/解锁闸**之前**先读带领态,读取失败统一转成 503「带领状态暂时不可用」,`data={outcome:NOT_RUN}`,并沿原事务回滚。下面修过的夹具就用真库实测了这一点。

### 原因二:submitPreference(master 和本分支都红)→ C 类,身份闸先返回

- `submitPreference` 现在走 `PlayBasicIntentApplication.executeBasicIntent`。它第一步要求原运行身份:`routeSessionId == null` 时直接返回「必须提供动作标识、期望版本和原运行时身份」,不进入任何带领态或路线读取。这是 fail-closed 的拒绝,不是放行。
- 夹具的 `PreferenceSubmitRequest` 从没设 `routeSessionId`,所以在身份闸就返回了,根本测不到带领闸。
- 同一处还有一个次生漂移:生产代码调用的是 8 参 `PreferencePlayService.submit(..., originalPlayInstance, progressFunction)`,夹具桩和 verify 还是旧的 7 参重载。
- GET `preference` 不经过身份闸,所以一直正常抛 503。

## 修复(只改 `chengyinhub-admin/src/test/java/com/chengyinhub/business/it/PlayLeadAdmissionFailureMySqlIT.java`)

1. **改成真正的线性带领场**:真库 `cms_topic.product_type` 和主题桩都改为 1(线性)。hint / answer 在制造故障前插入真实 `club_lead_state` 行(activity 500)。
2. **证明带领闸确实生效(新增负控)**:hint 用例先把带领置为「集合中」(status=1),断言返回「队伍集合中,等队长开始行程」,且积分、流水、进度、回执、报名零变化、零 claim。之后置为「行进中」(status=2),再改表名制造故障。
3. **补线性路线闸依赖**:线性路线闸(`isPrevDone` / 前置节点)要读完成事实,夹具原来没接 `playerNodeProgressService`。现在显式注入一个返回空列表的替身,和真表一致:`vector()` 断言 `player_node_progress` 为 0 行。
4. **偏好提交补原运行身份**:`command.setRouteSessionId(0L)`。没有个人局或共享局时,`basicActionContext` 给的就是 0。桩和 verify 改为现行 8 参重载,并加 `eq("default")` 断言原局实例。
5. **偏好故障段断言保持原强度,并补得更严**:
   - GET 后 `claims==0`;
   - POST 后 `claims==1`(和 answer 用例同一口径:必须到达真实 claim INSERT,不能被更早的身份或授权拒绝挡住);
   - `never().completeReceipt`;
   - `vector()` 不变,证明 claim 被真实事务回滚;
   - 路线 session / action 都是 0 行;
   - `verifyNoInteractions(route, preferences)`。
6. **恢复段**:只多出这次 POST 自己的一条 `game_command_receipt`,另外断言它是 `PLAY_BASIC_PREFERENCE:APPLIED`。积分、流水、进度、报名仍须完全不变。

四条用例的 503 断言都没动:`unavailable()` 仍逐项比对 code / msg / data / cause / detail 和 HTTP 映射。

## 分类

| 用例 | 分类 | 说明 |
|---|---|---|
| realLeadStorageFaultReturnsNoHint… | B | P-13(本分支)后夹具仍用自由探索主题 |
| realLeadStorageFaultRollsBack…ActionClaim… | B | 同上 |
| preferenceLeadStorageFault…Branch… | C | master 上已红:提交请求缺原运行身份,submit 重载已变 |
| preferenceLeadStorageFault…Journey… | C | 同上 |

**没有 A 类(真 bug)**,生产代码未改。线性带领场在带领存储故障下四个入口都 fail-closed 返回 503,并回滚已插入的动作 claim。

## 证据

- 修复提交 `f997cb0d`,architecture-day-lead-admission 批次绿:[41964756564](https://github.com/liboyang42-cpu/hengyin/actions/runs/41964756564)。日志为 `PlayLeadAdmissionFailureMySqlIT: PASS 4/4 (skipped=0, cleanup CLEAN)`,PlayHintUnlockMySqlIT 也绿。
- architecture-day-integrated-changes 批次 @`f997cb0d`:见下方「integrated-changes」行。
- 修复前:master 2/4 红 [41956731939](https://github.com/liboyang42-cpu/hengyin/actions/runs/41956731939);本分支 4/4 红 [41957583233](https://github.com/liboyang42-cpu/hengyin/actions/runs/41957583233)。

integrated-changes: 待填

STATUS: PARTIAL - 待 integrated-changes 结论
