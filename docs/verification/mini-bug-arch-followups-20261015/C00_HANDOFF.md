# 旧bug与共用后端补完交付

CY-HANDOFF: CODEX；唯一全局控制协调者C00/C99。本批复用冻结PR18，不修改其分支；后续分支 `codex/mini-bug-arch-followups-20261015`，父基线 `1e403b135206844a34219753a36e2dee39c7dd59`。本批业务/测试/门禁提交截至 `d8a0cf8bfa0c1d0cbde930d32152d2c70db80518`；最终公共候选完整SHA以交付PR正文和唯一控制账为准。

原1e403b13的231/231 VPS签收保留，仅证明原冻结候选。新批源码必须独立验证，不能沿用旧结果。没有部署、业务迁移、微信上传或合并。

## 精确改动与边界

| 线路 | 改动 | 合同/限制 |
|---|---|---|
| L01 | 当前回执/投影revision单调；迟到请求只修改匹配pending、账号、活动及会话；刷新保留同身份的内存原命令；在途不变成可重试 | NO_API_CHANGE；storage仍仅脱敏索引，不持久现场正文/照片；既有恢复入口 |
| L02 | 作者edit-detail聚合入口RR只读事务，避免主题旧版本配新章节/节点/票/协作者 | HTTP/DTO/权限不变；新真实MySQL代理交错、负控和后续独立读回4例；待实跑 |
| L03 | 同本人会员行的短事务覆盖准入、删除、lease finish+日志；删除擦safe_text/撤lease但保留请求身份与收费事实；晚到worker读当前持久行 | NO_API_CHANGE/无新状态/无DDL；删除数量仍仅日志数。provider期间不持锁；不重发付费请求、不补偿未知额度。自动留存、ai_generation_log、语音日池政策未变 |
| L04 | 后台日志null/undefined token显示“未知”，真实0仍0，列表/详情标估算 | NO_API_CHANGE；仅显示/formatter，未做真实Vue编译或浏览器截图 |
| L05 | write前同步持久本地UNKNOWN，存储失败不写；首次create丢响应不重复create；旧账号/卸载/重读前callback不清当前草稿 | 无新HTTP/requestId/后端幂等。可选本地publishAttempt无payload/token。读200不自动解锁；仅复用已有冲突框明确采用已保存版本 |

L05已知限制：无ID结果未知需去现有项目列表核对，不能自动认领。已有ID读回revision未变、没有既有确认框时继续保留UNKNOWN。HTTP200但业务码500/503/无结构不能证明没有提交，因此也保持UNKNOWN；不根据错误文案猜测写入阶段。明确400–499业务拒绝可恢复。不能把该前端保护声称为服务端create幂等。

L03线性化边界：删除后持久读取与迟到写不会恢复旧答案；删除前已读取/已发送的HTTP内容无法撤回。进行中墓碑沿既有PROCESSING/REQUEST_OUTCOME_UNKNOWN，旧成功回执保留SUCCEEDED但safeText为空且retryable=false；不创造DELETED wire状态。

## Cloud实际检查

- L01两个目标文件65/65、相关回归122/122通过（范围重叠不相加）；C00重跑目标65/65。
- L04新增formatter及六处实际插值表达式3/3，原相关7项与其合跑10/10；不是Vue render/视觉验收。
- L05相关九文件97/97，包含原行为、真实Page延迟网络回调、本地storage与重进、500/未知结果、同ID重读、切号/卸载；原guard与业务断言保留。未模拟真实服务端commit。
- 全量Node首轮8361项，8350通过/9失败/2既有跳过。8项为发布旧匿名/无UUID夹具与文案，已按真实草稿身份修且相关97通过；1项为动作清单source digest陈旧。清单已按真实源码重建，4480个ID/分类/动作分母完全不变，仅459条evidence哈希更新。第二轮与UI门禁以C00实际结果追加，不预填成功。
- UI基线只等量迁移原25个行号锚点（12项动态setData及1项静默请求等），理由与棘轮/天花板不增加；digest按真实key集合重算。
- Java/数据库未在Cloud执行，静态review不能代替编译。L03真库fixture的MySQL64字符库名上限问题已修，suffix非法/超长在建库前明确拒绝；L02 DDL正则曾被review误报，逐字节核与已通过B03一致，未错误修改。

## 聚焦后端39项（期望数，不是通过数）

| 类 | 期望 | 环境 |
|---|---:|---|
| NpcChatServiceTest | 22 | Mockito/事务helper替身 |
| ApiAiNpcChatDataTest | 2 | 入口身份范围单测 |
| NpcChatUserDataMyBatisIT | 3 | 真实MySQL/Mapper/已有导出删除入口 |
| A02NpcDeleteBarrierMySqlIT | 8 | 真实MySQL/独立连接/锁与回滚/迟到provider替身 |
| B02TopicEditDetailSnapshotMyBatisTest | 4 | 真实MySQL/Controller事务代理/真实五组Mapper |
| 合计 | 39 | 不含Redis或真实收费provider |

从仓库根：

```sh
mvn -B -pl chengyinhub-admin -am test \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=NpcChatServiceTest,ApiAiNpcChatDataTest,NpcChatUserDataMyBatisIT,A02NpcDeleteBarrierMySqlIT,B02TopicEditDetailSnapshotMyBatisTest \
  -DargLine=-Xmx384m
```

有已批准离线依赖缓存时验证者可加`-o`，不绕其资源/网络入口限制。

前提：Java21/Maven3.9.11、专用MySQL8.0.45（必须任务独占、可建删scratch schema）。显式设置`CHENGYIN_FUNDS_MYSQL_IT=1`、`CHENGYIN_NPC_DELETE_IT=1`；缺flag导致skip不算通过。

- B02使用`CHENGYIN_TEST_MYSQL_{HOST,PORT,USER,PASSWORD,DATABASE_BASE,DATABASE_SUFFIX}`；host及非空唯一suffix必须显式，PASSWORD可以显式空，完整库名≤64/安全标识符。
- 两个NPC真库类使用`CHENGYIN_MYBATIS_IT_{HOST,PORT,USER,PASSWORD,DB_SUFFIX}`。全部显式指任务测试实例。A02新IT随机12–16位身份，suffix只准字母/数字/下划线，最多20字符；建议`_<runid>`。现有userdata类仍使用原固定suite加此唯一suffix。
- 仅在自有临时库构造/删除fixture，不执行业务迁移、不访问共享/生产库、不起真实应用、不需要Redis/微信/provider。
- 真实报告位置`chengyinhub-admin/target/surefire-reports`；逐类核数量/独立方法/failures=errors=skipped=0及新XML时间，不混旧XML。Maven退出0只是必要条件。

## 实际执行入口与资源

新增 `.github/workflows/mini-followup-regression.yml` 只在GitHub-hosted ubuntu-latest执行上述39项，checkout明确head SHA；专属MySQL8.0.45服务，Maven/test heap640/384MiB，校验新报告和逐类零跳过后上传独立artifact。该通道尚未运行时只标计划，不写PASS。

本地验证者已通知VPS新共享212守卫/锁/离线构建预算；不得继续旧裸Docker驱动，不把无网络离线build入口用于MySQL集成，不猜新的集成测试批准入口。VPS集中由本地协调者按适用规则排队，worker不连VPS。GitHub托管CI是独立可用验证通道，其MySQL服务端口仅位于临时runner；不是VPS宿主开放端口或应用部署。

## 交付状态

代码已提交待聚焦CI/适用VPS通道。接口层CONTRACT_FROZEN；执行证据未齐前不升级整体SOURCE_READY。TEST_DEPLOYED=NO。运行时真实身份HTTP业务、保存后真实重开、设备/微信/iOS、真实审核/provider、原有业务迁移、合并/部署/上传均未验收。

外部E01–E06只持各自新测试/专属README写权，须读控制分支的EXTERNAL_SIX_HANDOFF；不与本批L01–L05/L10或Claude/iOS共享写。原所有者在本批提交前已停止/冻结，C00按明确路径串行收口。
