# E：95c held HTTP producer/consumer 完整 SOURCE 收口

结论：5 code + 3 delivery files；新增12项Python、5项Java直接控制SOURCE。全部测试、解析/import、编译、Java/MySQL/Docker/HTTP/CI NOT_RUN。不触B/C/F或rootCI，不改canonical工具、SQL、金额/授权/恢复政策。原run的具体缺key/Pythonframe仍UNKNOWN，不把本次源修补回签为已过五boot。

## 输入与保全

- parent 95c153fb763ce332adaad99e6a096abe48b9a466，tree b083ee7f967e214702cafbc9d9ffb434aac43416。
- root已核run40252086007/job121346002917/artifact12622010360；ZIP SHA256 cfdf913abc3be9f07b2dd5076e8928889bbe48adaac0476e4f1469925984aabe。
- parent实际source/normal各418表的rowCount/rowSHA/nextId零差异、三真实ready/PID1/Quartz、原提现/章回执、三次143与完整私有日志/独立清理是root提供的旧源证据；不是本候选的运行证明。原heldtrue首因KeyError/最后evidence HTTP200不足以确定缺键，不把3ready当5completed。
- 旧E工作区 /workspace/hengyin-e-final-reuse-coupons 仍HEAD037afa2eb546402460738e5767c8df86e246cf1f且干净。新隔离源码 /workspace/hengyin-e-held-contract-95c153。所有原像来自公开95SHA；不移动私有历史。

## 可达源码接缝与参考

1. 当前生产 chengyinhub-admin/src/main/resources/application.yml 明确 spring.http.converters.preferred-json-mapper: jackson2；child却通过spring.config.location=file:/fixture/application.properties替换配置，原替换文件/args没有该选择。framework/pom.xml已带原spring-boot-jackson2 bridge、common已带原Jackson2 databind，本包不增加依赖。
2. child events()返回List<com.fasterxml.jackson.databind.JsonNode>（私有Jackson2 parser树），再放入AjaxResult.data交给MVC。Boot4.1.1在preferred mapper缺省时启用Jackson3 writer；跨版本树不是稳定JDK DTO。当前源码可证该不兼容边界，但原artifact没有response字段形状，实际选中的旧converter及历史缺键仍UNKNOWN。
3. good()返回完整AjaxResult，evidence的两次['data']各只解一次，callback也沿完整envelope。不能把所有good改成data或用.get默认空来修。
4. 实际 /monitor/job/run 调用SysJobServiceImpl.run，原leader/definition lock/manual trigger及异步Quartz行为不变；HTTP ACK不证明任务ENTER/RETURN或sys_job_log成功。events源是原AOP/真实MyBatis interceptor，facts/inbox源是原JdbcTemplate SQL；不另造成功endpoint。
5. WechatVerifiedPaymentFactService原owner按字符串fact_hash验证不可变事实；callback producer仍返回真实Reference.id/hash，后续FAIL/WAIT/经济零变化断言不变。

官方一级源码参考：
- [Boot4.1.1 Jackson默认条件](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-converter/src/main/java/org/springframework/boot/http/converter/autoconfigure/JacksonHttpMessageConvertersConfiguration.java)
- [Boot4.1.1 Jackson2选择及实际Spring converter装配](https://github.com/spring-projects/spring-boot/blob/v4.1.1/module/spring-boot-http-converter/src/main/java/org/springframework/boot/http/converter/autoconfigure/Jackson2HttpMessageConvertersConfiguration.java)
- Spring官方5651ee2f97edc64ca480c2764ca990516446e321的RequestMappingHandlerAdapter.getMessageConverters、MappingJackson2HttpMessageConverter/JacksonJsonHttpMessageConverter构造及write合同已只读核对，不作为新运行证明。

## 完整实际调用替换

### 原child与真实MVC
- test-only main补回生产已存在的preferred-json-mapper=jackson2，不改生产YAML/默认全局配置或依赖。新增HTTP_JSON_CONTRACT startup阶段，同步runtime固定stage白名单；ready前检查真实RequestMappingHandlerAdapter中首个可写AjaxResult application/json的converter是MappingJackson2HttpMessageConverter，而非只信env字符串。
- events()仍读同一个真实私有events.jsonl；改为严格校验的List<Map<String,Object>>，保原每条bootId/symbol/stage/held及THROW的errorType/blocked真实值，不跳行、不填默认、不重演结果。JDK值不依赖Jackson树版本。
- 已有ENTER/RETURN/THROW/SQL producer不变。MyBatis合法insert!selectKey等ID保留，非值型symbol不接收带空白的SQL正文。异常消息/cause不添加到journal或HTTP。
- 原FixtureEndpoints路由、class级权限、security/MVC/Quartz、真实mapper/事务/Reference consumers保持。新Java测试类不匹配RestoreHttpApplicationChild*.class运行glob，parser新增TypeReference innerclass仍由原glob/classmanifest精确绑定。

### Python held消费者/报告
- 只在evidence/callback/jobs/sys_job_log原合同位置做严格字段/类型校验；完整good envelope语义不变。code/status不是严格200或未收到object即拒绝；success ACK可以没有data，但evidence/callback不能。
- evidence只接受原7字段及当前ready boot/held/UUID/schema，events必须真实列表且每条满足原类型；verifiedFacts/inbox必须原列名、整数ID/state与原hash。callback保原single data层和真实factId/hash，原originalOutcome必须FAIL的业务断言仍独立执行；不把shape正确当业务成功。
- 两个固定seed job target及正整数不同ID明校验；不发明任务/重发经济POST。Quartz保45秒原窗口、实际当前boot的ENTER/RETURN与原sys_job_log baseline后两任务成功；完成谓词仅原样抽成assert_quartz_completion供直接控制调用。
- assert_owner_events所有原Blocked/held/RETURN/禁真实pending selector判据保留。报告只将这6个确实证明的owner标entered，不再把SQL-only mapper symbol也误标为owner ENTER。全域fence仍NOT_CLAIMED_SPECIFIC_REAL_OWNERS_ONLY。
- 每个held模式附逐实际已过断言的completedStages，细分回执/拒绝写/通知/Quartz/latefact/WAIT/readback/replay/provider/network-stop；轮询只保实际事件/日志计数，不用计数替代原断言，不把partial ready写成completed。
- 拒绝只附固定合同kind/字段presence+类型/未知key计数/首个坏record index或identity匹配布尔值；不序列化任意key名、value、KeyError.args、HTTP body或SQL。原未知异常可附唯一内层reviewed Python相对路径/行/sourceSHA，限两个owned文件和最多32个traceback节点；不输出私有绝对路径、函数局部、stack或cause。
- actor/foreign refusal、原receipt/changed command、refund/withdraw零效果、原notice PENDING、late SUCCESS事实+WAIT inbox、exactcallback replay/原allocator、provider original journal和transport-zero-send、全表/nextId、5distinctboot及true/invalid/empty模式、20秒/0或143/137拒绝/日志先取后删与独立清理全部不减。canonical工具和已过metadata/log实现不重写。

## 直接控制 SOURCE

原全部Python/Java场景和断言保留；原5项RestoreHttpStartupContractTest源码逐字不改。

新增12 Python方法：
1. good保原envelope与ACK无data合同。
2. true/invalid/empty三模式严格shape→真实owner原谓词→Quartz日志→原fact/WAIT/replay纯控制链。
3. 缺data/重复data/缺events/树bean形状不默认空，安全presence/type。
4. foreign boot/held/schema/UUID不得消费。
5. callback typed fact identity与原FAIL规则分离。
6. 真实Blocked/禁pending selector原闸保留，MyBatis !selectKey兼容且不冒充owner ENTER。
7. ACK/失败/重复target/旧日志不证明两原任务完成。
8. 原两seed target/ID严格合同。
9. KeyError只给真实reviewed sourceframe，不回传key/SQL/privatepath。
10. 3ready不替代5completed。
11. verifiedfact/inbox列名及整数/布尔区分。
12. HTTP200/business failure/错误JSON类型不等于有效evidence且不回显正文。

新增5 Java方法使用原journal parser和实际Spring JSON converters（非网络/DB/模型）：
1. ENTER/RETURN/SQL/THROW原字段经Jackson2、Jackson3 writers逐字段一致。
2. 实际首个AjaxResult JSON converter选择必须符合原生产Jackson2。
3. 缺/多/错类型/未知stage/非法symbol原journal不跳过或伪补。
4. THROW classification真实布尔/原class字段保留。
5. callback真实fact标量与single data层两writer一致。
这些直接控制数据不是实际HTTP/Quartz/MySQL业务接受证据；完整新5boot和全部latefact流程仍须root同源运行。

## 交付边界

owned.patch为5 code完整增量；提交另有README、manifest、patch，共8文件。逐原/后像/总bytes/SHA在manifest。执行仅源码读取、源编辑、git diff/applycheck、hash和有限secret pattern scan；不冒充测试或完整gitleaks。无新增框架/表/依赖/endpoint/鉴权/费用政策/真实外呼/CI；root统一最终集成、扫描与必要验收。历史缺键仍UNKNOWN；未运行项全部NOT_RUN。
