# VPS C06 夹具修正交接

状态：SOURCE_DELIVERED_UNVERIFIED / 修正后 NOT_RUN。仅改三份测试夹具和证据，不改生产源码、mapper 或 validator，不执行 Java/Maven/DB/CI，不提交 Git。当前串行修复槽由 C00 明确授予，冻结后停止写入。

## 失败事实与修正

本地唯一 VPS 验证者报告 f7e 候选首轮 483 项：470 PASS、5 FAIL、8 ERROR。本文件仅处理其中五个具名 C06 相关方法，不把其余失败/错误声称为本项已解决；这些数量是收到的运行报告，不是 Cloud 重跑结果。

1. OWN_C06_25Test.checkReceiptWriteFailureStillThrows 与 journeyReceiptWriteFailureStillThrows：公共 updateActionResponse Answer 访问 saved；使用 when 重新 stub 时，Mockito 会先调用旧 Answer，动作尚未插入所以 saved=null。两处仅改为 doReturn(0).when(...)，保留原 saved 写回 stub、两个精确异常文本断言、成功回执/重放和 CAS 失败负控。生产写回零行仍须抛错，不能降级成功。
2. OWN_C06_29Test：无 schemaVersion 的 {} 原本就不合法。改为最小合法冻结规则 {"schemaVersion":1}，方法名明确 versioned，两个 assertFalse/checkEnabled 默认值与不读实时主题的断言保留。缺键 fallback、present-null 拒绝、错类型原 validator 负控均保留。不放宽 JourneyRulesValidator，并同步撤回原 OWN-C06-29.md 错误表述。
3. GameSessionRuntimeServiceImplTest.existingPhotoSubmissionReplayDoesNotReverifyStorage：已有 RECORDED submission 但无 outcome 的夹具会实际恢复一条新完成事实；公共 insertPlayerNodeOutcome 返回 1，因此必须执行 session CAS。仅此方法局部 stub CAS=1，保留 submissionId=206、never insertSubmission，以及一旦重验存储就抛错的 verifier。其他 CAS 失败断言不改，生产新增事实后 CAS 失败仍抛错回滚。
4. GameSessionRuntimeServiceImplTest.concurrentRoleInsertCollisionReadsActiveWinnerAndCurrentRevision：原夹具只给普通 select 的第二次调用配置 current，但 C06-19 已改用 ForUpdate 当前锁读。仅此方法把普通读固定 initial、ForUpdate 返回 current；expected revision=3、changed=false、insertRole 和 never updateSessionCas 全部保持。不能退回普通读或降低 revision 期待。

## 待本地验证

修正后仍需由本地唯一 VPS 验证者在 C00 新公开 SHA 上重编译、运行相关用例并读回新报告。Cloud 不运行验证；本次不宣称五项已 PASS。原首轮失败、源断言和冻结历史均保留。
