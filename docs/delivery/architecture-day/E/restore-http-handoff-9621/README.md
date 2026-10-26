# E：9621 后续启动交接与报告读取 SOURCE

完整源码增量：3 code + 3 delivery files。全部测试、解析/import、编译、Java/DB/Docker/HTTP/CI NOT_RUN。本说明只记录公开源码合同与静态交付，不包含内部运行状态、原报告或日志，不为历史执行归因。

## 身份与边界

- parent 9621da223f18cbddfdb7973e613fa406f8afaa54，tree 1f42e78b0b2d14fb0634ee29e9411dc41deabf3b。
- 只改 ci/restore_application_http_runtime.py、probe.py、test_restore_application_http_probe.py；不改Java child/原unit、B/C/F/helper、workflow、SQL、canonical工具、业务金额或权限。
- 公共parent原像在隔离源码目录 /workspace/hengyin-e-handoff-9621；旧E工作区不变。owned.patch与manifest给完整原/后像和SHA。

## 源码可证的修补

### 同一停止观察点的完整报告快照复用
原held boot在通过ready-stop后，为protectedRowsAfter读取一次完整snapshot，又在三个LATE_TABLES allocator报告项中各读一次。此时原应用已停止，没有两次报告字段之间的业务命令。新completed_held_rows在原停止点读取一次完整snapshot，为原全表报告及全部三个before/after allocator字段供值。

保留所有原全表、typed rows、nextId和callback replay断言及报告列；不删表、截断或填默认。缺原字段仍失败，不修复目标。只减少该报告段的三次完整重复读取：对于T个受保护表，原每snapshot的SET/readback/metadata三条加T个行查询不变，报告从4份变1份；不声称RPS/SLO/实际耗时。其余实际前后效果检查、normal/canonical已存在流程不重写。原1500秒总budget、180秒ready、45秒Quartz窗口不增加。

### 原启动交接状态与有限guard分类
start仍按原顺序执行：budget→原mode/seed准入→旧stop/完整日志/原CID删除与独立absence→旧process清理→原DB身份复核→config原子写→ready/startup旧文件清理→原trace准备→Docker create/isolation→原ready身份/boot提交。

新增仅内存的start_transition记录固定阶段和有限side/mode/原前后CID，不是事务、回执或成功证明。成功ready后才写READY；create之前不制造newCid。异常时原JSON及原安全diagnostic可附此阶段，避免把旧child READY误认为下一模式已通过。invalid和empty仍是原合法held mode，不删除/重解释。

runtime.require只把本范围已审固定literal guard映射为有限原因码（budget、matrix/seed、owner/history/private-log、DB、trace、isolation/env等），消息和拒绝谓词均原样保留；不读exception文本推测原因，不放行unknown，不绕budget或改timeout。

### 可定位的外层source frame
仍只允许两个owned Python相对路径/line/sourceSHA；跳过require、budget、write_private及probe的通用value/query/serialization helpers，选真实外层reviewed caller。继续限制32个traceback节点，不输出locals、函数实参、任意KeyError值、message、cause、SQL或私有绝对路径。无可用外层frame则不编造。

## 直接控制 SOURCE（新增8个Python方法，全部NOT_RUN）

使用原ApplicationSlot.start/wait/stop/log/absence方法、真实临时私有文件和原require_absent；Docker/proc/child字节由明确test-only driver提供，不执行Docker/Java/DB，不声称实际五boot业务通过。

1. source false→target false→true→invalid→empty完整调用链，旧ready/status先清，mode按原env原值传递，5个不同CID/boot，日志先读回再rm，最后原CID保留。
2. 原1500秒边界耗尽时不得进入invalid/create，上一true停止证明保留；诊断指start的实际budget调用行而非通用require。
3. 原log/config/ready/process identity漂移拒绝handoff，不rm旧CID、不创建新boot。
4. endpoint变化或遗留config.next拒绝，保旧stop/log记录与准确阶段，不能用旧ready冒充invalid。
5. invalid/empty的故障注入ready不成为completed；pre-ready原进程可按原可信proof停止，但不允许quiescent SQL。
6. 原rm失败不前进，原CID/完整log保持，不能自认已移除。
7. 原一次完整post-stop snapshot同时供完整表及全部allocator字段；原数据不改，缺字段不能补默认。
8. 通用HTTP value helper异常的frame指向真实evidence caller，原错误code/隐私约束不变。

原所有case身份和断言保留。既有KeyError隐私控制改经真实专属snapshot caller触发，继续断言同类KeyError/原隐私canary；只适配“跳过通用query helper”的定位合同，并增加不回显新实际SQL文字的断言。原Java源码、已存在启动/converter控制不改。

## 保留围栏与限制

保留五distinct completed boot、false/false/true/invalid/empty模式、原actor/授权/回执/noeffect/fact+WAIT/replay/highwater/provider identity、真实Spring/security/MVC/Quartz/mapper、20秒stop/仅0或143/拒137-OOM、移除前完整私有日志及独立absence。全域fence仍NOT_CLAIMED。有限新阶段/原因码不替代任何实际身份或退出证明。

本包没有新框架/endpoint/依赖/表/权限/费用政策，不执行DDL或真实渠道，不发布运行报告/日志。git diff/applycheck/hash/有限secret scan只属静态元数据检查，不算测试；完整gitleaks与同源受控验收由root统一进行。所有新运行证明NOT_RUN，不能根据源码改动指定过去失败的具体guard。
