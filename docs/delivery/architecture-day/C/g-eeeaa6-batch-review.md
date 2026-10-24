# C 独立审查：G eeeaa6 完成事实批量读口

结论：发现 **1 个 P2 有界合同缺口**：上限检查早于 List 快照，100→101 的输入变化可把101项送入Mapper。其余所审静态路径未发现跨会员/scope借用、旧成功局回落或将缺PG变完成；但只能确认受限PG投影合同，**不能据此批准替换全量完成统计/任务验证/经济资格**。当前生产消费者尚未接新方法，以下接线差异不是已上线回归。Java/MySQL/CI均未执行。

基准 `eeeaa6a79abcd1a1e3ec930f398a118adbaf1965`，独立工作树 `/workspace/hengyin-c-g-batch-review`，分支 `codex/architecture-day-c-g-batch-review`。仅本C报告有差异，G接口/Mapper/消费者/CI未改。

定位缩写（均为本基准实际行号）：

- R：`system/.../service/support/CompletedRouteFactReader.java`，新入口42–99，事实投影145–173。
- F：`system/.../resources/mapper/business/CompletedRouteFactMapper.xml`，新SQL62–101。
- S：`system/.../service/impl/PlayerNodeProgressServiceImpl.java`，旧instance/逐项读28–38。
- P：`system/.../resources/mapper/business/PlayerNodeProgressMapper.xml`，旧读28–40。
- C：`admin/.../web/controller/api/ApiPlayProgressController.java`，真实companionLine1260–1300。

## P2-C-G01：100项上限在复制前检查，可被复制时输入变化绕过

R47检查`requests.size()>100`，R51另取`new ArrayList<>(requests)`，R65把复制后全部inputs送Mapper；复制后没有再检查数量。List参数未要求不可变；即使使用普通Collections.synchronizedList，另一个线程也可在size与toArray两次同步调用之间合法增项。不是跨账号漏洞：每项member/scope仍逐项检查；影响是承诺的100项有界查询不成立。当前没有生产调用者。

确定性最小Java反例（**仅源码推导，未运行**；Mockito模拟两时点，不启动数据库）：

```java
var all = java.util.stream.IntStream.range(0, 101).mapToObj(i ->
    new NodeProgressRequest("i"+i, 7L, 0L, 10L, (long)i+1, "default")).toList();
List<NodeProgressRequest> changing = mock(List.class);
when(changing.size()).thenReturn(100);
when(changing.toArray()).thenReturn(all.toArray());
CompletedRouteFactMapper mapper = mock(CompletedRouteFactMapper.class);
when(mapper.selectNodeProgressPage(eq(7L), anyList())).thenReturn(null);
// 显式空输出仅用于观察：在调用Mapper之后才因“缺少查询结果”报错。
assertThrows(IllegalStateException.class, () ->
    new CompletedRouteFactReader(mapper).findNodeProgressPage(7L, changing));
verify(mapper).selectNodeProgressPage(eq(7L), argThat(xs -> xs.size()==101));
```

最小修复边界：先拒绝null，复制一次，再对**同一快照**检查size及逐项校验；该负控修后应为IllegalArgumentException且Mapper零交互。现9 unit/11 IT覆盖固定100与固定101，未覆盖size/toArray快照边界。C未改源码或补G测试。

## 旧逐项读与新batch逐项合同

| 核查点 | 实际对照与结论 |
|---|---|
| member/activity/topic/node | 旧S会补instance，P按非null字段筛选，可读宽scope。新R44–60要求认证actor正数、每项member等actor、activity显式>=0、topic/node>0；F96–100同时绑定这四维，R81–85再核PG来源。新口故意不承接旧nullable宽探针；调用者原可读性/报名/角色gate仍不可省。 |
| instance=null 当前局 | 旧S30/P37按同member/activity/topic最新session id，非“最新成功”；新F89–95同scope取max(id)，不先筛status。最小数据：r1/default已完成、r2/replay:1进行中，当前只读replay:1，旧default PG1不复活。 |
| 显式instance | 旧P35直接筛原实例；新F91/F98同一显式值，找不到不退当前/旧成功。无route但有对应default PG时原行仍可读；routeSessionId/version为null，不捏造0。 |
| 完整inputId域 | R52–62拒绝空白/超80/重复inputId；F是UNION ALL+LEFT JOIN，R69–98拒绝漏、重、额外或错scope返回，再按原输入顺序组Map。缺PG也保留inputId；不是空列表语义。 |
| 空页/100 | actor合法时空页R64直接空Map、0SQL；固定100允许，固定101在SQL前拒绝（可变List漏洞见上）。100限制只在reader，直接Mapper没有同等数量门；现生产无绕reader调用。 |
| 错误/一致性 | mapper异常不catch成空/默认；null结果与缺inputId抛错。单条batch语句快照不同于旧S先查session再查PG的多语句时序；稳定已提交数据可比，跨页CURRENT并不冻结为同一局。 |

事实投影与旧PG展示判据（R162–172；C1295–1297、781–791）：

| 原PG状态 | ARRIVAL | TASK_VERIFIED | NODE_COMPLETED投影 |
|---|---|---|---|
| 缺行/仅有route完成 | UNKNOWN | UNKNOWN | UNKNOWN；不能把route终态当PG1 |
| status0、checkin有值、照片review0 | OBSERVED | UNKNOWN | NOT_OBSERVED；pending不是完成 |
| status1、checkin有值、review0 | OBSERVED | UNKNOWN | OBSERVED，和旧status1展示一致，不凭review0反向覆盖 |
| status1、checkin无值 | NOT_OBSERVED | UNKNOWN | OBSERVED；不从完成推造到达时间 |
| status null/其他值 | 只看原checkin是否有值 | UNKNOWN | UNKNOWN |

原16列均有SQL映射/防御复制，含照片、reviewStatus、score、SOLVED/REVEALED、delFlag、时间；del0才有来源。审核位、SOLVED或REVEALED均不被当作可信task receipt。`sourceKind=LEGACY_PROGRESS`、subject=MEMBER，不合并TEAM/WORLD/Game/Generic事实。注意mode2原P93–103/C788明确status1只表示游戏答完，核销另看entitlement；因此此`NODE_COMPLETED`只能解释为原PG的status1展示投影，不能据名称升级为探店节点履约/兑奖真相。

## 三个确定的接线差异及最小复现

1. **Map条目数≠来源数/完成数**：无任何PG，输入一个`("x",7,0,10,11,null)`；旧逐项读空List，新Map有x但hasSource=false、三观察UNKNOWN。用size/containsKey判断“已完成”会从0变1；只筛OBSERVED才是原展示完成数，UNKNOWN仍须保留来源不明语义。
2. **inputId去重≠事实去重**：仅一行PG `(7,0,10,11,default,status1,del0)`，输入a/b两个不同inputId且scope完全相同；新返回两条OBSERVED，旧companion整scope探针只计一行。直接累加values会从1变2。跨页复用同inputId再putAll则相反，会覆盖另一个节点。逐项oracle“每个输入都有原行”并不证明整体计数等价；聚合身份至少保留member/activity/topic/resolvedInstance/node。
3. **页不是全集，跨页CURRENT不是同一快照**：PG有node13/14/16/999四个当前status1，当前active列表只给13；旧C1296数4，新该页数1。G现有IT193–201已明确这个反例，不能换成仅当前active列表并声称等价。101节点拆100+1页时，若两页间开新replay，null实例可混两局；必须保留/核对返回实例再证明相同输入全集，不能把“每页完整”当整主题完整。C当前只读审查，未修改消费者或加入自动切局逻辑。

这些是未来consumer接线阻塞，不是新batch漏ID；G本提交没有任何生产`findNodeProgressPage`调用者。旧`findCurrentCompletedRoutes`的完成session集合仍独立存在，不能由PG UNKNOWN页替代。尤其无PG的已完成路线，可被旧route reader发现，而本页只返回带session上下文的UNKNOWN。

## 实际核查与未验证

执行了rg全caller搜索、源文件/父提交对照、XML解析和普通@Test枚举：旧PG service/XML与Controller逐字未变；原`selectCurrentCompletedRoutes`属性与SQL正文未变；新方法生产引用仅定义1处，0调用者；新增9 unit+11 IT仅为源码声明，实际Java调用0。未运行G的verify脚本冒充业务验证，也未运行Java/DB/CI/EXPLAIN。

报告中的反例均为源码推导/待root受控复现，不声称已获得红/绿Java结果。无G代码、consumer、schema或CI差异；本报告可单独撤回。
