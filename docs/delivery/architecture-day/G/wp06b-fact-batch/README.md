# WP06-B：复用现有reader的有界本人节点事实页（未验证候选）

结论：已在**现有CompletedRouteFactReader**落地`findNodeProgressPage`，复用其专属Mapper/XML，未新建同义Service或平台。固定1–100项页为一条批量SELECT；每个inputId均返回同member/activity/topic/node/instance的原PG事实或明确缺来源。源码/测试已写，Java与真库由root统一执行，G本批均0执行。**消费者保持原路径，尚未切换，也不报WP06-B全量完成。**

## 基线、隔离与授权文件

- 公共基线：`4197f6a6b11d62cb0a742aae73d7167c93114585`（root4197），从公共origin核实/fetch；独立工作树`/workspace/hengyin-g-wp06b-fact-batch`，分支`codex/architecture-day-g-wp06b-fact-batch-20261024`。根AGENTS与此前已读4cf无差异；按用户明确CI候选例外，本VM不运行Java/DB。
- 生产只改：`system/.../service/support/CompletedRouteFactReader.java`、`system/.../mapper/CompletedRouteFactMapper.java`、`system/.../resources/mapper/business/CompletedRouteFactMapper.xml`。
- 新测试：`admin/.../business/service/CompletedRouteProgressPageReaderTest.java`、`admin/.../business/it/CompletedRouteProgressPageMySqlIT.java`；其余仅本G交付目录。
- 不改ShopDay/acc/29c源码、C Controller、D CmsTopic、其他progress消费者/Mapper、schema/migration、奖励/资金写侧、CI/registry/pom。无依赖/Agent/生产访问/支付或退款政策变化。

## 选定消费者、原链与最小片

候选是`ApiPlayProgressController.companionLine`的**自玩模式本人完成数**（4197:1271–1302），仅影响`PlayNarrativeSupport.pickExploreSlot(done,total)`文案档位。原路径：认证member→主题/自玩scope→PG Service probe(member,activity0,topic)→当前实例PG→只数status1。它不是领奖、核销、结算入口。本片只实现这类原数据的按节点页端口，不接C Controller。

真实oracle为原`PlayerNodeProgressServiceImpl.selectPlayerNodeProgressList`及原PG XML：测试对每个输入构造旧probe逐项读回，比较所有16个原列和缺行，正反输入ID差集均断言为空；另按原companionLine整scope probe读取status1节点集合，对完整输入域做展示集合比较。SQL调用计数只测批量入口，oracle的逐项读本来是线性的，不隐藏在新入口内部。

**不能把任意前端可见节点页当整个主题完成数**：旧companionLine会数本scope存留的PG1，即使节点不再出现在当前active node列表。本批专门加入“partial input universe”反例，要求承认旧全集与部分页不同。分页端口只保证所给输入的完整性，不暗示覆盖整个scope。因此即使root将本批测试跑绿，接线前仍须C证明真实consumer输入全集/跨页去重等价、现权限/可读性不变；本批默认仍为原路径，没有偷偷切换或新增默认开启开关。

## 输入与输出合同

- `findNodeProgressPage(authenticatedMemberId, requests)`：actor必须是入口认证主体；所有request.memberId必须与actor相同。activity必须显式非负（0自玩）、topic/node正数，inputId非空且唯一；最多100项。全部输入校验在SQL前完成，空页0SQL；拒绝不读宽scope、不写任何事实。
- request.instance **null=原当前局语义**：按同owner/activity/topic最新session id，先取latest再读PG，不先筛成功。明确instance只读该局；未找到不回退当前/旧成功。无route的历史default沿用原PG读合同，但frozen version/session保持null，不伪造0或快照。
- Mapper保留每个inputId（LEFT JOIN）。Reader检查返回输入域双向完整、无重复/额外ID、requested scope和原PG scope一致、del0及原instance一致；冲突/遗漏抛错，不给“空结果成功”或宽表fallback。SQL异常原样传播。
- 只观察`LEGACY_PROGRESS`、subject=`MEMBER`。ARRIVAL来自原checkin_time；NODE_COMPLETED仅原status1；status0不是完成，未知status为UNKNOWN；TASK_VERIFIED始终UNKNOWN，因为PG行/审核位/REVEALED不能替代原可信任务回执。缺PG时各观察为UNKNOWN，不能推断Game/Generic没有完成。
- raw source保留id、member/activity/topic/node/instance、status、checkin/create时间、照片、审核位、坐标、score、SOLVED/REVEALED、del标志；photo status0/review0与status1/review0不混同。没有把checkin时间改称可靠首次完成时间。
- routeSessionId/configVersion仅作为相同实例的原冻结上下文，缺失即null；路线终态不会被转换为节点完成或奖励资格。个人/共享基础到达即使同topic/node，也按各activity/member/instance分开，不自动赋给TEAM全员；TEAM/WORLD的owner完成、Game outcome、Generic slot、核销与已发奖均**不在本最小PG片的归一范围**，未合源冒充完成。
- 此内部服务不新增HTTP授权；调用者仍执行原主题/活动可读性、paid/角色/商家/consent等gate。不能用它替换任何经济/解锁资格检查。

## SQL、性能与一致性边界

1–100项按参数化UNION ALL输入表，关联同scope原route与PG；始终一个Mapper调用/一条JDBC SELECT，不执行逐项Service查询。当前局选择与原owner/PG的latest id规则一致，明确instance不回退；直接Mapper的空输入/主体不匹配也不会成为全表结果。没有`${}`，没有新DML/DDL或表。

测试计数器在真实MyBatis Executor入口记录1项与100项各1条查询；这是待root执行的断言，G没跑，不称已测得。单条SQL内仍有与页大小/历史局数相关的数据库工作；不能将固定语句数说成固定CPU/延时。EXPLAIN/真实数据负载/N+1端到端尚未验证，不新增索引掩盖。

一条批量语句读同一数据库语句快照；旧oracle在独立多次读取间可能遇到真实并发切局，本fixture只比稳定已提交数据。显式instance可固定读取身份；本片不新增跨请求session锁/receipt/并发重玩协议，更未处理资金fence或late fact。

## 测试源码、实际检查与未验证

完整case名/声明类别见CASES.md和source-manifest.json。新增**9普通unit+11普通MySQL=20预期调用**；两个原G类3 unit+4 MySQL逐字不变，四类合计27预期调用。四类均定向核对无Parameterized/Repeated/Factory/Template；这不是全Backend计数，其他类原24参数等不受影响。所有数量为源码预期，**JUnit/H2/MySQL实际执行0**。

新增MySQL类复用旧PG真实fixture DDL、原route迁移和原实例unique键；真实PG Service/原PG Mapper/route Mapper作为oracle，没有mock服务成功。必须显式HOST/PORT/USER/PASSWORD/SUFFIX，限定回环测试实例；一类UUID隔离schema，精确记录createdDatabase，AfterAll仅DROP自身匹配schema，DriverManagerDataSource无池，连接均关闭。不执行生产DDL或清理其他测试库。

本批实际只执行定向源码/调用者搜索、XML解析、旧route语句/方法和原测试字节比较、case枚举/源码hash、diff范围/whitespace、现有secret-file-gate；结果与指纹见source-manifest/artifact清单。Java编译、所有Java/DB测试、EXPLAIN、微信/API链路、CI dispatch、生产操作均未跑；待root同SHA验证，未通过不得正式集成/合并部署。

## 迁移与回退

无schema/历史回填，旧route构造器/查询方法与Mapper语句保持原样；已搜索没有此Mapper的lambda/匿名实现需改装配。原消费者默认路径仍在，可独立撤回本G reader/Mapper增量；不删PG/route/照片/回执，不补奖。后续接线若发现输入域差异，继续原路径并交差集；不能用投影回填发奖、将团队事实复制为个人资格，或牵连手续费/退款/正金额未决政策。
