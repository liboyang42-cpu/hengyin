# P9 队内成员名次源码交付

结果：SOURCE_COMPLETE，运行验证 NOT_RUN；不是产品或发布验收完成。

## 修改文件

- chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/OrientTeamBoardServiceImpl.java
- chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/OrientTeamBoardServiceImplTest.java
- docs/questo-teardown/ui-inputs/P9-members-rank/UI-input.md
- docs/questo-teardown/ui-inputs/P9-members-rank/OUTCOME.md

## 实施与依据

读取实际 AGENTS.md、IOrientTeamBoardService 的 detail/ranking 合同、ApiOrientController.teamDetail、OrientMapper 的 active-members/completion-facts 查询、OrientTeamProgressCalculator.compute、现有聚焦测试与 UI-P9 合同。复用原成员权限和完成事实：selectCompletionFacts 对 member/task 聚合去重，compute 仅投影当前在岗成员及当前 listed tasks；在 detail 的原 memberDone 上计算计数与名次，不新增 SQL、表、服务、端点、依赖或评分引擎。

新增 doneTaskCount/rank/ranked/mine 和详情顶层 rankBasis/rankScope/rankedMemberCount。仅正数排名，零完成返回 rank=null/ranked=false；同完成数竞争排名 1/1/3，完成数降序、memberId 升序。所有旧字段、登录/在岗/内容/开关门槛及队伍之间 ranking 方法保留；controller 无需修改。

## 测试源码与静态检查

新增4个聚焦测试：正数排序与当前查看者、竞争并列与角色/待审隔离、零完成和全部零完成、跨来源/实例去重及下架/其他 owner 完成排除。扩充原进度矩阵测试的字段兼容与 metadata、退队后的榜单范围、未登录/非成员/退队/禁用门槛；测试类现共9个 @Test，均未运行。

git diff --check 针对两个 Java 文件通过。未启动 Java/Maven/Docker/Redis/数据库/HTTP 测试；未 stage、commit、push、改 CI 或部署，未访问生产。

## 剩余与风险

root 需要在批准测试 VPS 统一编译、运行聚焦测试及相关集成验证，并将 UI-input.md 合并到唯一 UI 清单。成员数组从旧角色/加入顺序变为排名顺序，客户端需按 memberId 识别成员并使用服务端排名字段；源码不证明已部署、UI/真机或生产验收。计数继续遵守原 team/detail 的主题/场次和现存完成事实语义，不引入额外局筛选或新资格政策。
