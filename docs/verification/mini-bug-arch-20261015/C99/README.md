# C99 集中验证与交接

源码候选：`3ee0fd017c964d11525bc44f70aa23f425e591ff`，草稿 [PR #18](https://github.com/liboyang42-cpu/hengyin/pull/18)。共同worker基线 `d551c6b` 已组合公共PR11父链及PR12–15；PR16任务文档保留。没有合并GitHub PR、执行DDL、部署或上传小程序。

## 当前结果

- 源码：B01–B05、A01–A03最小修复及专属测试已提交，按lane保留独立提交/报告。B05最小读取规则变更已获聊天中明确批准，见B05/AUTHORIZATION.md。
- Cloud：仅执行必要Node/静态检查；没有运行Java/Maven/MySQL/Redis或真实付费模型。B01真实处理器最终14/14，恢复字面请求后相关23/23；系列时刻8/8，相关54/54；其他必要相关结果见B01原始日志，存在重叠，不累加冒充独立覆盖。A01既有客户端16/16。最终UI-GATE-0 PASS，动态59/59、静默理由28，未增加债务或白名单。
- 动作清单：原生成器最终 `ACTION-LEDGER WRITE PASS: 4480 controls`。B01改变调用位置后刷新实际源码位置/摘要；4480个ID无新增/删除。四个退款控件因真实requestSubscribeMessage能力由write转external（local tap1796→1792），仍逐项断言退款POST、duplicate-trigger、服务端权威读回和真机读回；总验收范围未减，非把外部能力伪装local。三处UI登记点（play 1487→1512、6451→6510；participation174→175）逐条上下文相同，理由与ratchet数不变，digest用原算法显式重算。
- 集中相关合同：71项初次70通过/1失败，失败只在四个退款控件write→external后的分类期望；现已逐项核ID/POST/读回保留并纠正期望，action-ledger单独复跑40/40通过，GET权限和route freshness31项已过。原失败日志保留。
- 托管CI：源码SHA对应 GitHub CI [38727768982](https://github.com/liboyang42-cpu/hengyin/actions/runs/38727768982) 与资金MySQL IT [38727769021](https://github.com/liboyang42-cpu/hengyin/actions/runs/38727769021)。本文记载提交时验证快照，最终运行结果以 [PR当前Checks](https://github.com/liboyang42-cpu/hengyin/pull/18/checks) 及PR正文精确SHA/运行链接为准；不将运行中结果冒称通过。早期基线CI的迁移声明门因PR正文缺“影响分类③”失败，已补明确继承的六条迁移和未来落库顺序；没有改门禁代码。早期基线资金562/0/0/skip0只证明d551c6b，不冒充新候选通过。
- VPS/设备：当前环境没有secret、runtime variable、outbound identity，TCP domains/IP为空，未配置专用VPS目标路径。没有尝试生产或Mac/Cloud替代设施。VPS实际版本/数据库与候选一致性、业务独立读回、微信模拟器/真机均未验证。

最初在共享源码仍写入期间运行的C00合同检查出现inventory freshness失败；该结果保留为过程证据，不放宽断言。源码冻结、集中生成清单后再复跑最终合同。每个worker只做必要相关测试，未各自重复全量。全量Java/小程序由既有GitHub托管CI集中执行。

## 交付与真实限制

| 任务 | 可审阅交付 | 尚不能关闭的验收 |
|---|---|---|
| C00 | 公共父链、PR状态、独占文件和门禁变更审查，见../C00 | 运行环境/部署版本独立读回 |
| B01 | 原账号/代次回调隔离、稳定ID取消、音频/轮询生命周期、退款授权、排期JS时刻消费 | 真机音频/订阅、真实退款及通知送达 |
| B02 | 更新缺席字段保留已有作者配置，保留原规则/排序/发布CAS | 首次create无持久requestId的丢响应不能保证幂等；没有新增全作品revision；真实保存发布读回 |
| B03 | 积分当前锁读、应付推进SQL原子正额/未冻结保护 | 新B03 opt-in MyBatis套件未被既有CI清单点名，未执行；NULL eventId原幂等语义未重定义；真库并发账本独立读回 |
| B04 | 上海真实startTime读回与JS消费、候补/报名/结算保护同口径 | 混合或无未来时刻保持未知，须显式选择；真实日期编辑读回 |
| B05 | 经用户批准的已删作者旧链接不可读及反证用例；不切新内容链 | 原帖/媒体/CDN/缓存撤回与两身份HTTP读回 |
| A01 | 保持game requestId指纹冲突和routeActionId原重放差异；新局冻结结局正文 | 结果未知仅能读现有服务端事实，不能伪造上游结果；第二设备/照片/资产真实读回 |
| A02 | 拒mock成功、全量输入输出审核、配置失败补偿、模型开始后未知不退款重发、NPC保留原身份、未知token为null | 删除仍有safe_text与晚到saveLog屏障缺口；语音ASR/TTS日池无已批准政策；后台token `||0`需Claude显示未知/估算；无上游答案对账能力 |
| A03 | fallback严格布尔新写、保留各runtime旧选择策略、新局结局正文快照读侧 | 旧快照没有endingChapters且正文已删时无法恢复历史内容；旧/新局VPS对比 |
| C99 | 草稿PR、实际日志、逐lane合同和本交接 | CI以PR当前Checks及正文最终SHA为准；VPS/设备未验，不标业务READY |

PR14认领PHOTO+URL仍缺资产归属/审核完整fixture；clubInterest/roamRecall模板仍空；group-code无明文签名降级；PR15官方路线只读、积分全量POST、状态键仅run。以上原限制保持，未借本轮扩大新功能。

## Claude接口交接

每lane的CLAUDE_CONTRACT/CLAUDE-CONTRACT列实际HTTP方法、DTO/状态/错误、权限范围、请求身份/版本和测试fixture。A02 PHASE_TWO.md追加未知结果、估算用量、语音池与删除屏障具体方案；这些未闭合项不得被UI展示为成功。UI设计、新功能及其专属后端仍归Claude，原生iOS/Flutter完全排除。没有向外发送通知或私自修改Claude工作区。

回退时按本候选lane提交撤销源码，保留原账本、历史migration、旧局JSON和其他lane改动；不得通过删除事实/重写旧数据换取测试通过。发布、任务启用、DDL和微信上传均留待用户后续明确操作。

CI修补记录：b8e94fd的backend真实testCompile失败，A03专属测试误用无参构造；3ee0fd0按真实六参数依赖注入修正，业务断言未减。同期本地71项分类失败已按四个具体退款控件逐项核实。新SHA由既有CI重新验证，未把前一轮结果标通过。


## 三方协议同步及CI补偿用例修正

控制分支 `codex/mini-bugs-backend-cloud-plan-20261015@b191d560` 独立checkout由C00唯一维护，本候选同步其三方提示词、OWNERSHIP、BACKEND_CHANGES和十任务执行状态。已读Claude PR21 d9dc65a并核PR17/20冲突；23个无冲突既有UI文件精确分配，接收回执PENDING。iOS仓库暂无开放交付PR，活动所有者未知，不释放原任务。认领材料/AI删除屏障/语音政策继续DRAFT；没有测试部署证据。

c236f131的GitHub CI run38728912026后端实际10095测试、2失败、0错误、530跳过；失败为两个旧补偿测试模拟provider开始后异常却期望退款。c24202f6仅将触发改为本地缺key且chat从未调用，保留原日/代次/nonce及原子补偿/禁止独立delete/decrement断言，不改调用后UNKNOWN政策。新候选待现有CI验证。c236f131 Funds MySQL IT run38728912034全部job成功；不包含新B03 opt-in真库用例，不代替VPS/设备验收。ci/backend-test.sh仅改日志展示，保留mvn -B clean test及原退出码，已用shell桩核成功0/失败7均原样返回，未在Cloud运行Java。
