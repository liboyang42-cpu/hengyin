# A：真实退款终态接 G 原分项 owner

代码候选；Java/JUnit/MySQL/CI **未运行**。独立父 `36687c65464ea569ba774c128005a094916771fa`，分支 `codex/architecture-day-a-original-refund-terminal`。root保留已合成的fd350兄弟创建首锁和B出站Qualifier，按delta合成，不能整旧blob覆盖。

实际读取 G `8818837b3717e3340384545d04b81abef4697fc7` 接口/ledger/XML/两份补丁：使用其真实单参 `reverseCompletedRegistrationRefund(Long)` 与 `hasFrozenOriginalRefundComponents(Long)`，两者MANDATORY；未复制或改G/B/C文件、SQL、CI或schema。本头未包含B/C/G依赖增量，不能单独编译或上线；须组合真实类型/Mapper及经批准的受控schema后验收。f31目录旧的双参G提议不再适用，勿应用；B reader/sidecar由B统一修，本片未再加旧单reader注解。

- `reviewRefund` 在原锁和退款资格/精确金额校验后、审核CAS前选owner；新原分项owner不执行旧detail追收。仍保留原报名状态迁移/权益释放/pending明细作废，未把审核批准变成渠道payout SUCCESS。原close缺分项由G抛错，A不降级；确实没有新原close的旧路保留旧owner/金额算法。
- `updatePayoutTerminal` 真实SUCCESS CAS或原SUCCESS终态重放后调用G，再通知，随后由原mutation写DONE；G错误不捕获，原CAS/G本地写/DONE同事务回滚。PROCESSING/ABNORMAL/CLOSED不调用冲回。
- `verifyManualPayout` 新增原topic→activity(如有)→报名→原退款当前锁与原身份复验；原人工双审CAS后调用G。同一原复核人终态重放只交G核验幂等、返回行数仍0；另一复核人不能借旧终态认领新冲回，原handler≠reviewer不变。
- `applyWechatPayCallback` 在真实B事实和锁下金额核对后，当前topic已关账且父/从单任一尚非同一TX的2/2时，返回明确恢复失败；不修历史半态、不入账或盲放款。原标准回调已持久fact/sidecar/inbox，保留原引用、原号和RETRYABLE，详见G-RECOVERY-SEAM.md。

测试源码：`RefundOriginalComponentOwnerTest`新增5普通@Test，证明新旧owner分流/批准非到账/缺分项拒绝/驳回零资金/双人守卫；`RegistrationTopicFenceMySqlIT`本分支20→25，新增G加入事务后失败回滚、人工双审失败/同人重放、关账半态与主完整从半态、原SUCCESS终态重放。真实fact/inbox/领域SQL仍沿原fixture；G是明确的窄mock，用一处可回滚SQL只证明参与事务，**不证明G金额/原分项/钱包算法**。G原14unit/10IT由root组合验收。

原资金96及七个旧退款套件的所有原@Test正文/断言保留，仅补G显式注入；五个旧detail追收fixture额外补原current-read接线及真实表默认pending状态（原mock漏字段），不改余额断言或制造新分项。另有旧退款通知/typed事实fixture尚未适配前批f31合同，见静态余项；未用静态检查冒充所有旧单测已通过。

仍未闭合：G原计划producer、尾差/部分退款/负亏损政策与最终已关账补账；inline原PAYMENT ref持久关联；跨B/G完整同头Java/真库/故障恢复验收。本片不填默认原分项、不启用缺证据新路、不新增退款金额政策或生产DDL。静态检查/精确方法位置/文件hash见static-result.json。
