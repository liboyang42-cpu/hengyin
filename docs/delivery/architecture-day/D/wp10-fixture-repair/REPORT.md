# WP10 真实失败后的夹具/测试接线修复

仅测试候选，生产算法零变化。新侧基于 d49c12b820419308236010e0710f96d7bf48c4c9，旧侧基于 ed5e5626a7278371c886fe3b14a0aa85d9cfa935；不改写任何冻结提交。旧侧只接共同两份fixture修复，不带新owner/TestWiring或三份新owner单测接线。WP08暂停。

## 真实失败与定位

D独立读取GitHub原job日志：新 run40062177785/job120777918298/source d49，旧 run40062347927/job120778437091/VALIDATED_SOURCE_SHA ed5；两侧均32 tests、2 failures、30 errors、0 skipped。root随后逐XML确认相同结果。`failure-evidence.json`保留全部64个case记录、artifact ID/digest及证据来源。D已取得GitHub artifact文件引用，但云端读取该ZIP被proxy 403拒绝，未取得XML字节；不冒称D完成逐XML验证。Backend原日志接口两次Transport closed后停止读取，其精确NPE根栈和两项源码测试失败来自root原XML回报。

| 唯一原因 | 原实际结果 | 本次最小修复 |
| --- | --- | --- |
| JSON mapper缺JavaTime模块 | 每侧29个主error，另2个failure各有同因suppressed；cms_topic.create_time为JDBC LocalDateTime，receipt.valueToTree失败 | 使用仓库已有JavaTimeModule，并禁用日期timestamp输出；保留全部字段和时间精度为ISO文本，无新依赖、无忽略/删时间、无变更归一化 |
| runtime模板声明material却未使用 | 每侧runtimeBindings...主error；随后empty outcomes为正确的失败连带断言 | 给原合法ITEM槽补resourceCosts(itemId=material,quantity=1)，与既有槽位单测一致；原binding/digest/ID映射断言不动，不改生产schema/validator |
| V1节点辅助查询排序错误 | 两侧同为expected[first,second,third] / actual[first,third,second] | 原writer每章重置nodeSortId；fixture全主题ORDER BY sort_id错误地混排局部序号。辅助查询以LEFT JOIN章顺序→节点顺序排序、保留n.*和全部行；期望不改，生产mapper不改，完整snapshot仍原ORDER BY id |
| reader受控JDBC改值未使MyBatis会话缓存失效 | 两侧同在preference-reader断言失败（行184）；前一d20配置仍被缓存 | fixture保存原SqlSessionTemplate，在既有outcomes事务适配器里clearCache再调用真实reader；仅使本用例直接JDBC配置更新可被实际mapper读到，不改生产缓存配置/mapper |
| ChapterFlowTransaction审核失败场景漏owner接线 | root XML：rebuildTopicContent:4078 contentGraphAuthoringService=null→existingNodeUnlocks，外JDK事务正常 | 在ProxyFactory构造实参中先wire真实target，再包装原事务代理，两个case均走原算法/原mapper doubles；移除首例重复wire(proxy)。原IllegalStateException/审核失败/原行回滚断言全部保留 |
| ChapterAtmosphere源码合同扫描旧owner | root XML：预期2处显式规范化，旧门面0处 | 正/负控都扫描真实GraphImpl；原2处/负控0处断言原样保留，增加V1/V2两个确切规范化写点及原门面委托检查 |
| ProductSourceWiring扫描旧owner | root XML：共享权益门槛规则已迁到Guard | 原共享规则恰1处断言保留，改读真实Guard；同时检查门面两处委托、两写路径到Guard、Guard到原共享形状算法的链路 |

同样失败不是等价通过，当前没有修复后Java/MySQL结果。修复判断来自旧新实际同失败、原mapper/原算法及fixture接线对照，不以改成当前实际结果、删断言或新增skip过关。

## 精确授权与文件

共同fixture仅TopicContentGraphMySqlFixture.java、TopicContentGraphMySqlIT.java；`shared.patch`在旧/新原源上逐字节相同且均git apply --check通过。旧新修复后IT同字节、SQL仍907eb2b9e7a5e809d4f3cfe497fc3284feba85aba71b257a071d3c4756627d4d，fixture仍仅owner默认legacy/extracted一处不同；旧侧ClassNotFoundException四类强断言保留。

新侧另三份原单测，仅新owner装配/源码位置与委托合同适配：

| 路径（均chengyinhub-admin/src/test/java/） | 原blob | 原case数/必要性 |
| --- | --- | --- |
| com/chengyinhub/business/service/impl/ChapterFlowTransactionTest.java | a9d553189c69d3a71b8497573a62f2580f7019d8 | 2；第二个真实事务场景新依赖为空 |
| com/chengyinhub/web/controller/business/ChapterAtmospherePersistenceContractTest.java | 1a84d623ccd889221005ce8814c70e6571fc70bc | 5；正负源码合同必须定位真实两写口 |
| com/chengyinhub/business/service/ProductSourceWiringTest.java | b09da3b7ac5634107bf6b83206d19c10c658470d | 19；唯有已迁共享规则检查需要变更，其余用例保持原字节 |

`new-owner-unit-wiring.patch`只供新侧，禁止带回旧侧。SOURCE.sha256列旧/新候选逐文件hash，repair-identity.json列preimage/candidate hash。原57文件manifest继续作为原冻结候选历史证据，不重写它来冒充修复后身份。

## 实际验证与未验证

实际静态：共同patch旧/新两侧相同且可应用；新侧单测patch可应用；32原case名、62scenario保留，全部原IT断言表达式逐字节保留；三份原单测全部26个case名/原业务断言保留。ChapterFlowTransaction去掉owner装配表达式后与919原文件全字节相同，因而仍兼容原141case装配审计。新增授权两类含24旧case，原366旧case未删除/跳过。receipt整个函数/归一化/SQL/冻结比较器均未改；源码静态执行两规范化点、移除规范化负控0命中，以及门面→writer→guard→共享规则的精确计数检查均通过。时间序列化仅启用现有模块，完整行/根异常/调用顺序/时间值仍严格比较。

没有在VM运行Java、MySQL、HTTP或CI；没有新增依赖、DDL、registry/harness/production改动，也没有dispatch。root需在两个新source各实跑32/0failure/error/skip、各完整32 JSON、各独立CLEAN及62scenario严格对照，再跑三类关联单测/full；不拿本次静态结果或旧新同失败签T15。新增合法runtime样本/缓存适配/排序辅助查询均需两侧实际复跑证明，不能声称修复后已绿。
