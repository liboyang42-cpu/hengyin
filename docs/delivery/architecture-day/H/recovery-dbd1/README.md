# H：dbd12e 原客户端失败的必要 SOURCE 收口

父 `dbd12e0a0d88e54597c274b56b08f2784c415d0e`，父 tree `b9607cf9d468108531b332f3b87fe1baeaa4a83d`。本片只在这个已含前轮 H/公共 master 的完整源上增量交付，不重放前两轮 donor、不回退任何其他 owner 源。所有执行验收 NOT_RUN；源码完成不代表运行通过。

## 应用边界

donor 实际改动仅原六个 owned unit 和本目录静态交付物。共同 production 的 `utils/verification-readback.js` 必要修补在 production.review.patch；root 独占 baseline 的三个必要数值/摘要 hunk 在 baseline.review.patch。两者须 root 串行采用，donor 本身不会改它们。

owned.review.patch 是六文件完整增量；complete.review.patch 包含 owned + production + baseline 全部源码，不能和分片重复应用。PREIMAGE-POSTIMAGE.json / POSTIMAGE.sha256 覆盖全部八个解析后像。没有删除文件、测试 skip/disable、CI/工作流或生成台账变更，没有新费用、权限、依赖、服务端政策或预算调整。

## 逐项根因及保留的合同

1. **verification-original-request：确有生产生命周期缺口。** 原 confirmRequest 精确确认并删除原记录后只 reset 底层 workflow，writing 仍留旧 in-flight，下一次 run 被它拒绝，pendingRequest 因而为 null。共同 helper 为 flight 绑定原 attemptId/requestIdentityVersion，仅在原号/原版本确认并成功清理后退休匹配 flight、删除其内存索引，然后 reset/abort 原 task。旧回调先 closed，abort 即使同步回调也不能触发旧结果或清下一次待办。原 storage 失败、异账号/异 request、销毁、legacy UNKNOWN、deadline 围栏不改。原迟到测试断言保留，增加真实新 attempt 进入和同步 abort 负控源码。
2. **play-request-recovery 两项先撞在 fixture。** 原 loadPage blanket 给 answer/checkin/arrive 预绑定局，两个仍显式调用 bindOriginalRun 的原场景便把 POST 错当 GET 上下文。只为这两场景显式 prebound:false，保留真实 action-context→原 _bindRouteContext→首次 POST；无关节点场景的首次答题也先完成原 GET。原错误文案/UNKNOWN 写锁/不让另一节点成功解锁断言保留；总请求数计入新增只读前置，额外断言真正 POST 仍恰好两次。其余原预绑定场景不改。本片不将 HTTP500 当明确未执行。
3. **guest-browse-login-on-write：当前真实 caller 已搬至 command owner。** VM loader 只显式加载真实 user-character 和 team-event-command，不返回空对象。夹具走原 Page.onLoad 公开 /team/info 读回固定邀请目标31，真实 createRecovery 落原 actor/targetId/requestId，写断言核对 /team/command 的 join 动作和持久原命令，而非已撤的裸 /team/join。加入原平台16-byte随机输入 double，未改生产身份/恢复合同。合法可见同页仍恰好一次；离页/ABA/原邀请变更/UNKNOWN 记录不变断言保留。“隐藏后新点击”先真实 onShow，不能要求仍隐藏页面执行写。
4. **post-compose 登录顺序：原纯生命周期拒绝是正确前置。** 不移动生产 guard。原静态控制只允许该具体 caller 的 `if (this._disposed || !this._composeVisible) return;` 先于登录；其余 caller 登录前不得有任何步骤。原登录 reason/同函数 resume/owner/稳定 target 仍核对，多行目标声明不要求挤进一行。新增真实方法源码控制：disposed/hidden 均零登录与后续效果，合法可见先经过登录。
5. **U5：冗余 flag 退役是已批准的真实合同。** 保留 citynode7/play1/seat0；未重新加 silentError 或基线。保留两读者精确计数/路径等号、冻结旧基线72、原实名理由和新增/移除/兄弟路径负控。退役 seat 文件不向 reconcile 注入 undefined entry；真实 flag 已零时不伪造一个“移除原调用”的变异。原 receipt 测试保留 fail/abnormal→null→UNKNOWN、账号 revision 失效不清待办、原 GET/只读事实及零再次写。mini-static 控制改为注入同一旧调用行的未登记 silentError 必须红，同时原代码保持 autoErrorToast:false / 完整 callbacks / 无核销写路径；不要求已移除 flag 继续消耗 U5 登记。
6. **seat caller 结构控制：** profile 原同一个 wrapper 现真实行720，控制按该原语句在源码中的唯一真实行定位并要求 baseline 精确键，而非停在旧676；仍不允许 play/detail 的旧 U1 豁免。详情的两个不同方法各自有 const url，控制改在实际 _submitVerification 调用的最近闭包里核对其唯一四路表达式，不能拿别的方法变量糊数。原四端点/相同 scan.data/账号/current/拒绝 guard 及变异负控不放宽。

## U1/U4 三项静态收口，仅给 root review hunk

无新增 U4 缺读事实。原公共 4efbd7ef 的121个债务键在当前完整源已经退役以下七条；现 literal map114项、原观测亦114。不能再把 ratchet121/digest旧值当作需新加七条登记：

| 原键 | 当前真实源码依据 |
| --- | --- |
| pages/publish/temp/index.js#useNum | 当前仅初始 data:0；3626 明确不再 setData 写入，原展示/发布无该写效果，U4 只检查真实 setData 字段 |
| pages/square/list/index.js#longitude | 旧 data 字段已撤；842/854 写真实 mapLatitude，不是旧 latitude/longitude |
| pages/square/list/index.js#latitude | 同上，843/855 写 mapLongitude；定位输入 res.latitude/longitude 不是死 data 字段复活 |
| pages/square/list/index.js#fileList | 当前 JS/WXML 已无该字段；发布框已移现有 post-compose |
| pages/square/list/index.js#popOrig | 同上 |
| pages/square/list/index.js#popShow | 同上 |
| subpackageP3/pages/badge-3d/index.js#rarity | WXML:20 给真实 pin-spin track 绑定 rarity；JS:153/154 构造展示时读 this.data.rarity，不再是零消费 |

只降低 u4Field 121→114；U1 仍原九个已审键，不改键、不改理由、不增加白名单。对现有字面键以原 `sorted keys joined by '\\n'` 字节合同作摘要（只读文本/文件 hash，不 import/执行 baseline 或扫描器）：
- U1九键：`5c64534f654f08234d2c2fe34cadd30d9d6a745af3fee2eb6700fb470fbdb9fa`
- U4一百一十四键：`58d96682b3c4b8573643eaa5be0e71fd73ccb3526880da62e8602fff8dbe596a`

两份 .keys 静态清单末尾自带文件 LF；摘要遵原合同不包含最后文件 LF。所有冻结 ceiling 保持。本源其余元数据本片不动。

## 验证与限制

只做公共冻结源码/原失败相关日志只读、文本差分、apply-check 和后像摘要/限定密钥模式检查；不发布内部日志、运行报告或原证据。完整 patch 对精确父文件前像 apply-check 成功。

项目 parse/import、Node/Python 测试、Java 编译、DB/Redis/Docker、HTTP/微信/供应商及 CI 全部 NOT_RUN。六类源码修补及新负控尚未执行，play 后续业务断言也不能因为前置原因定位就声称通过。实际 helper 与 baseline hunk 仍待 root 应用；root 收齐全部 owner 后统一同源验收。
