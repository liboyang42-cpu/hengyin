# 原用例迁移与负控对应

本表的 case 名对应实际加载真实 Page、真实 journal 4195、真实 session-store 的 Node 测试。wx/storage/app 请求是可控本地 doubles；不是微信、HTTP 或数据库证据。新文件也是两个原测试共享的本页 fixture，未改共享工具；被其它测试 require 时不重复注册其 59 case。

## 原 6 协议用例

| 原用例 / 必须移除的错误预期 | 新用例与不变量 |
|---|---|
| 1 起草请求带 UUID requestId | 原1：UUID 是持久 prepareKey；server RID 可靠保存后 execute 仅带 RID |
| 2 网络失败锁住后回读原 requestId | 原2：prepare 丢包 GET 原 key；execute 丢包 GET 原 RID；成功读回不 execute、不采用旧额度 |
| 3 额度变化不是生成证据 | 原3：保留；额度变化不收尾/不换身份 |
| 4 无法确认且改灵感才另起 UUID | 原4：删除自动换号；PROCESSING/UNKNOWN 改灵感仍只 GET；另起走专用真实 modal + archive/CAS 负控 |
| 5 明确失败/没送达解锁，重开回读 | 原5：重开只读原 RID，匹配 FAILED 保留事实；generic404/NOT_FOUND 不证明没送达 |
| 6 明确成功/失败响应直接收尾、不留锁 | 原6：只有匹配原 view 持久终态可显式新起；原身份不删除；generic500 不收尾 |

## 原 9 UI 正控

| 原测试 | 保留的断言 |
|---|---|
| 加载玩家剩余额度 | 原 POST quota 展示有效 remaining |
| 成功响应用剩余额度刷新 | 改为 prepare→execute 的真实 v2 fixture，原 productType=1 与新鲜剩余额度 |
| 超过三站 | fail closed、旧 plan 不被覆盖、额度不被坏正文覆盖 |
| 起草失败保留旧 plan/节点 | 网络失败不清用户原输入、草稿、POI |
| 进度/错误互斥 | 原 wx:if / wx:elif 链及原不透明进度文案不变 |
| 不复活模式切换/就地编辑死分支 | 原不存在方法断言保留 |
| disabledtap 给理由 | fixture 先通过真实 v2 响应证明原模式，再断言未确认 POI 的原理由与绑定 |
| 可达 WXML 链路 | 输入→生成→POI→fabu；无暗色死子树 |
| 本页不发布 | 原零绑定孤儿方法/旧状态不存在，无 publish/create 链路 |

## H 12 旧源负例的原意义

| H | 当前实际正/负控位置（simple-ai-v2-guards case 前缀） |
|---|---|
| H1 storage throw | H1/H2，另有 RID 保存失败、execute-stage 保存失败；零模型写 |
| H2 silent no-op | H1/H2、部分写/readback 丢失；不能当成功 |
| H3 A 迟到删 B pending | H3，实际 success/fail/complete，B journal/data 不变 |
| H4 A→B→A 同 token/毫秒 | H4，实际 session-store 单调 revision |
| H5 unload | H5，onHide/onUnload 后旧 success/fail/complete 不生效，POI 独立迟到负控 |
| H6 legacy NOT_FOUND | H6 + legacy NOT_FOUND：保留 UUID、永久只读，不升号 |
| H7 UNKNOWN 改词换号 | H7 + 专用确认负控：默认只 GET；明确取消零写；明确确认归档后新动作 |
| H8 actual request-client ABA | H8：真实 request-client 可投递旧包，但真实 Page guard 拒绝；不声称修改/修复 transport 自身 |
| H9 旧 complete 清新 busy | H9 prepare→execute + recovery→所选 GET + modal→新 prepare，父 complete 不清子 busy |
| H10 read error 当 ABSENT | H10 + history enumeration failure；失败不当空、不发 POST |
| H11 长期 raw idea | H11 + legacy migrate，精确 8 字段身份、原 idea/token/result 不落 pending/history，无身份 TTL |
| H12 SUCCEEDED 正文失踪删原号 | H12 两原因分别保留成功/原 RID/旧 plan，不伪造过期，不重跑 |

## 新批准合同的组合负控

- 真实 modal 确认：原 8 字段归档先于新 active；取消/切号/同 token ABA/刷新/hide/unload/原 pending 变化/hop 变化均零写零新 POST；旧 modal complete 不能清新 prepare loading。
- archive throw/no-op/readback、active CAS 被其它记录改变均零新 POST；4195 专项组合：archive 成功但 active 写失败→原 key 查回 RID→UNKNOWN→再次明确确认，归档只绑定原 RID 后才新 prepare。
- 同页 prepare 重放固定原 key/idea/mode；改变输入不影响原 payload。冷 prepareKey 无原输入时只 GET。
- 所有 5 状态 metadata、20 条 paging、接近 Long 最大值原 String 往返、乱序/重复/错误形状/错误 owner extra field/不安全数字/非规范 decimal 均 fail closed。
- ABSENT 先 owner recovery；冷 SUCCEEDED 也展示和可选择；终态选历史先归档/CAS；未决 active 选其它历史仅页内只读，不赋 execute 权。
- 原未绑定 RID 的归档可以冷只读按 prepareKey 找回；history 失败不当空；active 异常消失但保留未决归档时不猜新准入。
- 原 server mode 与 snapshot/metadata/view/同 RID 已知模式不一致，原记录不改、不可另起/进编辑器。冷恢复入口1而原模式2仍保存/导航2；null 模式保留正文只读，不能 POI/编辑器；后来同 RID 原模式获得证据时不覆盖用户已编辑正文。
- 非200原 PREPARED 可记录，但无自动 execute 许可；prepare200 重放 PROCESSING/UNKNOWN/SUCCEEDED/FAILED 也不执行；冷 PREPARED 需显式继续同 RID。
- 父子 hop、迟到 quota、旧 POI、新 plan、延迟 setData、两页共享 pending、真实401刷新沿原身份使用新头，均有独立实际 Page fixture。

这些 case 不证明服务端事务/扣额/owner SQL/TLS/真实模型，也不替代微信 UI 验收或 root 联合候选 CI。
