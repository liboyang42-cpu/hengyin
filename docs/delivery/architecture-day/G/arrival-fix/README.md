# G05-01：一次到达固定一次原游玩实例（未验证候选）

结论：代码修复已写完，只改 `ShopDayProofServiceImpl.java` 一份生产文件；通过现有 `IPlayerNodeProgressService` 在入口带完整topic/member/activity解析一次实例，主节点检查/插入/duplicate回读、同地兄弟检查/追加都使用该实例。本地只跑静态检查，Java编译、JUnit/H2/MySQL、Redis、CI dispatch均0，不能称已修绿/已验收。G05-02、其他统计和奖励政策仍只读。

## 基线、隔离与候选关系

- 唯一公共仓库liboyang42-cpu/hengyin；公共基线`4cf548c3e8b05c6d23f3252c7e5d063691ef0eaa`，原目标生产/Mapper/IT相较919无差异。已读根AGENTS，用户明确授权隔离未验证CI候选/禁止本VM Java与DB，本批遵守该例外，不把静态检查说成提交前Java已通过。
- negative完整候选：`acc4bf5ea10bbefabb9d5db52dd17dc800b3a955`，分支`codex/architecture-day-g-arrival-negative-20261024`，目录`/workspace/hengyin-g-arrival-negative`。只加原两反例和G交付；原34 case/类正文逐字保留→36。**负控分支禁止merge/deploy，未取得真库红例。**
- positive目录`/workspace/hengyin-g-arrival-fix`，分支`codex/architecture-day-g-arrival-fix-20261024`，父提交就是上述negative；后续单独commit不改写负控历史。完整候选需带上父提交原两反例，不能只cherry-pick正向delta而遗漏测试。
- 仅一个生产文件、四份明确获准测试、G新增docs；C Controller/其他progress消费者、所有Mapper/XML/schema、D authoring、CI/registry/root pom均未改，无新依赖、无新owner API、无新增Agent。

## 失败机制与最小修复合同

旧 `markArrived` 用不含topic/instance的probe直接查Mapper；有member/activity的SQL自动取当前latest route instance。新行却不填play_instance_id，DDL默认default。replay生成非default，导致当前局读不见新到达，或撞旧default键后当前回读仍空。旧兄弟节点同样可能查询最新、插入default。

现在：

1. markArrived拒绝缺member/activity/nodeId/topicId，避免不完整scope进入原实例解析；activity0仍合法，调用方原owner/GPS/扫店码/报名校验不变。
2. probe显式topic后，调用**现有真实进度Service.selectPlayerNodeProgressList**一次。该owner原`instance`逻辑填probe.playInstanceId并执行主节点存在查询；没有重复实现latest算法或引入新Reader。required `@Autowired`，缺bean不退化；解析结果空/blank不能插入default。
3. 保存本次final instance，已有主节点共享兄弟、新主节点insert与兄弟insert、duplicate读回都显式用同实例。新private arrivalProbe只携带scope，不再解析latest。
4. 新主/兄弟仍status0、无照片；已有行/旧状态/时间/照片不覆盖。同地点、章/主题/商家可见性仍原逻辑。软删唯一键冲突原行为保留，不恢复/覆盖旧行。
5. hasArrived、submitPhoto、markGameDone、同地判据、原3参数probe等方法体逐字未变。没有status1写入、发奖或核销接线；没有新增事务或锁协议，继续依赖原调用方外层事务。

## 调用链与依赖（行号绑定4cf基线）

`ApiPlayProgressController`实际调用位置：1578 checkin自由探索扫码；1602 checkin个人/共享Generic；1778 answer个人/共享Generic可信答案；2230 arrive纯线上探索；2316 arrive个人/共享Generic。定向生产源码另有接口与实现声明，不能把声明计成调用。五个具体点完整保留，未新增旁路；外层原basic intent版本/session/epoch、权限、动态码/GPS/原票等校验均未动。

`ShopDayProofService.markArrived`→`PlayerNodeProgressServiceImpl.selectPlayerNodeProgressList`34/`instance`28→原`TopicRouteSessionMapper.selectCurrentByOwner`→原PG select（显式instance）→PG insert/duplicate select；sibling仍原`CmsTopicNodeMapper.selectCmsTopicNodeList`可见性/samePlace→显式scope PG查询/追加。没有新的跨模块接口。

## 测试源码与装配

全部case名、原/新增分类见CASES.md与source-checks.json：4类原57→候选66（**源码枚举数量，不是执行结果**）。

| 类 | 原→候选普通@Test | 改动 |
| --- | ---: | --- |
| ShopDayProofServiceTest | 5→9 | 原5方法体逐字保留；装配真实PlayerNodeProgressServiceImpl + 原PG double + 明确无route/default的route Mapper double；新增一次实例冻结、duplicate回读原实例/照片、缺topic拒绝、无法解析不得default插入 |
| ShopDayGameThenPhotoH2Test | 9→9 | 仅装配真实实例owner与明确default/noRoute Mapper double，原H2/XML/表/原所有case断言不变。H2未运行，不当真MySQL证据 |
| PlayerNodeArrivalSharingH2Test | 9→9 | 同上仅装配，原同地/跨章/跨人/跨活动/退出/照片/幂等断言不改，原表DDL不改 |
| ThemeRuntimeMySqlIT | 34→39 | 原34 case/fixtures/wrapper/SQL全文保留；negative两case只注入新增required真实owner，断言不改；再加同局重复照片不覆盖、同instance siblings+旧default全列值保留、新局已有兄弟完成/照片不覆盖3项 |

新增MySQL用例复用原合法END/replay、原SQL Mapper与迁移；真实owner注入真实PG/route Mapper，没有mock新服务成功或mock runtime返回。原runtime fixture的既有依赖double不改，不声称新用例是Controller到API全链。sibling fixture只在已有cms_topic_chapter插入一条合法chapter，并把两个既有host node关联到同章同地址坐标；无新表、无假merchant/报名/VERIFIED/奖励。旧default一行status1+照片、一行status0+照片，在新局后所有列逐列getString比对不变；新局两行status0/picNULL/相同到达时刻。不是MySQL物理页字节或线上历史采样证明。

## 实际静态检查 / 明确未跑

- `python3 docs/delivery/architecture-day/G/arrival-fix/verify_static.py`通过：仅固定五个Java文件的源码比对；原57个test方法体逐字保留，原IT类正文全文保留；negative两方法只增加真实owner装配；7个受保护生产方法体不变；只一个实例owner调用、无新optional bean/default兜底/status1。输出存source-checks.json，含五文件原/候选SHA256。
- `git diff --check`、提交前staged whitespace/授权路径范围、现有secret-file-gate与artifact hash检查。STATIC_ONLY，绝不是Java解析/编译或业务测试。
- Java编译/单元/H2/MySQL/Redis/CI dispatch/生产访问/DDL/数据回填全部0；root统一按两个精确source跑原红/新绿。本批不改CI/registry/测试筛选或跳过case。

## 剩余inflight限制、迁移与回退

- 本片保证**入口解析完成后**主/兄弟/duplicate都固定同实例。原接口只有member/activity/node；调用者最初看到的session不是入参，因此不能保证“入口解析前已经切局”的请求总绑定原UI session。原controller receipt/version/session/epoch fences及外层事务仍是必要保护，本片没有绕过或替代它们，也没有新增线性化锁或声称并发replay全协议已验。
- unit的受控latest切换覆盖同一方法不二次解析；不是实际MySQL并发调度证明。真实全链/inflight行为仍须root受控CI；需要额外session参数或更广锁协议应另行授写，不能在这一文件中猜实现。
- 不变更hasArrived/submitPhoto读合同：它们仍读调用时latest，另一个请求切局后的独立读取不承诺回到旧操作实例；若要扩大为跨请求原session API，另立切片。这里未发现必须修改它们才能实现当前入口内冻结。
- 无schema/迁移/回填，没有改任何历史资金/到达/照片。代码回退不删本次或旧实例行/唯一键/回执；完整退回旧实现会重新暴露G05-01，不能清数据作回退。负控历史单独保留，正候选未真验不得正式集成/合并/部署。
