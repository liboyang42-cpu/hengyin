# B04 排期时刻及候补保护

共同基线：`d551c6babd018d41aec90dad2c0acaf1c534644d`；工作分支：`codex/mini-bug-arch-integration-20261015`。本记录为源码及回归用例交付，不代表 Java/数据库/设备验收通过。

## 复核及改动

- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubEventOpsServiceImpl.java`：`getSeries` 原来只有 futureDates；`listSeries` 原来不读未来场次。现在复用 `selectFutureOccurrences`，从真实 occurrence_at 按既有 Asia/Shanghai 还原共同 HH:mm，详情及列表统一。无未来场次或混合时刻返回 null，不捏造09:00。
- `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/ClubEventSeriesView.java`：仅增加可空 startTime 读字段；series实体、表和写请求不变。
- `listSeriesOccurrences` 原 editable 仅判断成功报名数，零成功报名仍可能有候补。现在复用写口既有 `countProtectedActivityHistory`；该查询覆盖任意 cms_registration 历史、结算及 WAITING/OFFERED/CLAIMED 候补。新增 lockReason 文本，原字段保留。未知报名数仍关闭编辑。
- `ClubEventOpsMapper.xml:190` 的保护查询和 `updateFuture` 的删除保护既有且未修改；物化器已有 TreeSet 日期去重，系列版本CAS及事务保留，没有新增排期/候补政策。列表每个系列新增一次未来场次查询，每条场次新增一次保护事实查询；未新增mapper或批量接口。
- 客户端 `chengyinhub-xcx/pages/club/event-ops/index.js:659` 原编辑打开硬编码09:00，确证会忽略真实时刻。协调者另交 B01 独占消费新增字段及Node行为回归；本lane不写该文件。客户端接线和设备验收未完成前不能关闭端到端缺陷。

## 回归及实际验证

新增 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B04ClubEventScheduleReadbackTest.java`，调用真实service和物化器、mock持久层，6个用例：上海00:30跨UTC日读回并添加日期保持时刻、同expectedVersion重放不得二次生成（与首个用例一起验证）、混合时刻null、无未来null、候补/保护历史零报名不可编辑且写口拒删、空场次可编辑、未知报名数不可编辑。候补状态枚举由既有SQL决定，mock测试不能证明真实数据库各状态命中。

实际执行：`git diff --check -- <B04两份源码及专属测试>`，exit 0、无输出。该检查仅证明diff无空白错误，不能证明Java编译或业务正确。另用Python标准库静态解析既有Mapper XML并校核两处toSeriesView调用及共享保护查询引用，输出 `B04 lightweight static checks: PASS (XML well-formed, read-model call sites, shared protection query reference); no Java execution`；这同样不是行为验证。

依授权不在Cloud执行Java、Maven、数据库或替代基础设施。以下全部未跑：新增Java用例；既有 ClubEventScheduleMaterializerTest、ApiClubEventOpsControllerTest；真实MySQL读回/并发；VPS联调；微信设备保存重开。Java交现有CI/VPS集中验证，建议测试选择 `B04ClubEventScheduleReadbackTest,ClubEventScheduleMaterializerTest,ApiClubEventOpsControllerTest`。没有CI运行链接或通过声明。

独立业务读回应在测试库创建00:30系列，读detail，追加日期，再独立查询 occurrence_at 与活动start_date；分别植入WAITING/OFFERED/CLAIMED、退款历史及结算，检查零成功报名editable=false且删场拒绝。重复expectedVersion应冲突且无额外活动；不得仅以mock或200关闭此验收。

## 兼容及回退

权限ACTIVITY_MANAGE、owner/scope、requestId、version、既有错误包装不变；无DDL、依赖、配置、授权模型或UI修改。回退只撤本lane源码及专属测试，不碰其他lane改动和历史数据。详情时刻未知仍需客户端显式选择；不能把空值回退09:00保存。
