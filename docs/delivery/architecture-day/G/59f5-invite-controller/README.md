# G：59f5 Admin 邀约编译首因与原 owner 接缝（SOURCE / NOT_RUN）

基线/直接 parent：`59f5ee7e76f335b3a1e9b3564009a7f5f8d44e77`；tree `3067211bb9d743d215cd8ba7e9ad5330ad6f0f88`。独立分支 `codex/architecture-day-g-59f5-invite-controller` / worktree `/workspace/hengyin-g-59f5-invite-controller`。初建命令出现 exec-server transport disconnected；未重建/覆盖环境，通过同 SHA GitHub 文件继续读取；原环境恢复后实读已存在 worktree 的 HEAD/干净状态再继续。没有回退任何 owner 的工作树。

## 精确生产 review（root 唯一串行合入）

`controller.review.patch.b64` 只给共享 `ApiCoopController.java` 的原 invite / private prepareFirstInvite hunk；实际 controller 路径未编辑。完整前后像 SHA256 在 controller-pre-post.tsv，解码后可从原前像重建完整后像。不要仅取 private 方法而漏两个 caller。

真实链：

1. `invite` 从登录取 `principalMemberId`；原 type0 MERCHANT 用 PROJECT_MANAGE、type1 merchant 用 COOP_MANAGE，解析商家合同 owner；club 发布从原 topic.clubId→club.memberId。原 self-invite 与 `OwnershipUtils.notOwner(topic.memberId, contractOwnerMemberId)` 拒绝仍先执行。
2. 在这些闸之后把该原授权结果冻结为 final `checkedContractOwnerMemberId`。原 requestId 路径仍先 `textRisk(principalMemberId, message)`（事务外 SDK），然后 `createOrReplayInvite(principalMemberId, body, beforeCreate)`；回调携带独立 principal 和合同 owner。legacy 路径传同一组已核身份。没有用 operator 代替商家 owner、没有信客户端 fromId/fromType、没有把 clubId 当会员 ID。
3. private helper 仍 `String`：null 表示原 DB 校验通过，非 null 由原 caller 转 error / 回调抛 IllegalStateException 触发原事务回滚。新增站点分支四处错误返回恢复为字符串；listSupplyStations 的第三参是显式合同 owner（服务真实签名 creatorMemberId），其原 scope/window/station/occupied 校验仍执行。无 message 的 IllegalStateException 返回明确拒绝串，不能意外把 null 当校验成功。
4. 原报酬、game/targetLevel、挂起数量、分成和、expireTime、node 清理和通知次序不变。原 request fingerprint、current-lock、回执服务、SDK/消息接口未修改。notifyNewInvite 仍在 service 返回后调用；helper 原 originalRequest 条件仍确保回调内不二次 textRisk。

局部引用检索：Admin 原源内 prepareFirstInvite 只有 invite 内两个调用和原 private 声明；review 同时迁这三个符号。根因是 String/AjaxResult 类型拼接与未传入合同 owner，不是改 public 返回类型或删除校验。

## 必要直接测试源码

所有原场景和断言保留；新增 3 个普通 @Test 声明（内部 2、12、1 个分支，**不是 15 个实跑 PASS**）：

- InviteAuthz：委托 principal 与 owner 不同，在 legacy 与原 request 回调两路径都实际执行 helper；核 listSupplyStations(topic,merchant,owner)、从不使用 principal 充当 owner，原主体重建/node 清理、typed receipt scope/station、两路径正确持久入口和通知。仅 stub 原 domain service 端口，未用空回调代替 controller guard，不声明真实 MySQL 事务证明。
- InviteAuthz：两 caller 下缺据点、非原候选据点、服务异常、有异常无原因、非经典与 CLUB 不适用共 12 分支。保持具体原错误含义、无 legacy insert/通知，缺字段/不适用不查据点。原“内容安全在 request 事务前且回调不再调用”测试逐字保留。
- SupplyStations：商家委托权限返回的 owner 不属于该主题，仍以原错误拒绝，不调用原 station reader/insert。
- 原 receiptResult fixture 只补其真实 scope 和可空 station DTO 字段；旧无站点仍 null，不回填历史、不假造数据。PartnerNodeTest 全文未改，仍列其后像，原 type2 退役/授权/contact/type0/type1 等断言保留。

## 完整性与执行边界

本轮是完整 59f5 上的一个增量。前两轮已被基线吸收，不重套：G `1b88c986daca5b18ac3fc4787a33b617a2e0bc14`（parent dbd12e0a0d88e54597c274b56b08f2784c415d0e / tree 781d77c0c4029abcba5f8b75b7a903f9571b12fd）；此前 G `7e980494034416d4df9a29d8de4c1f238d67f605`（parent d4cc5c59f65dcc0196a88eb556de9a10b663c56d / tree 69fe798a910d613e2b19704d8b1b20e737e667a4）。

只运行 Git/source reads、diff/apply-check、摘要与限定密钥文本检索；项目 parse/import/tests/Java 编译/DB/Redis/Docker/HTTP/CI **全部 NOT_RUN**。不得把本 donor 单独的仍未装 controller 的树当可编译候选：root 必须把生产 review 与 tests 同批集成，再统一同源验证。没有删除、disable、skip 原测试，没有改权限/金额/表/旧 migration/依赖/预算/CI/堆线程或真实渠道。
