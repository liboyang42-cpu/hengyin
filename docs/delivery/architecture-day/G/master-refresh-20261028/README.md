# G：d4 / a50 固定三方源码解析（NOT_RUN）

- Public parent：`d4cc5c59f65dcc0196a88eb556de9a10b663c56d`。
- 治理 source：`a50f661e157bf56b1c40d3f590c8dcc1dd513003`，tree `6339022662277a8c74287f8226066162ca17d312`。
- Git mergebase 实读：`e52eab7e44835ff6440761eebcdd1420a6406384`。
- 独立 worktree `/workspace/hengyin-g-master-refresh-20261028`，分支 `codex/architecture-day-g-master-refresh-20261028`。旧 G donor 与所有其他 worktree 未切换/回退。仅本次 3 路后像与 G 交付目录写入本 donor。

## 三处解析结果

1. `pages/club/detail/index.js`：保留 master P16 概览/勋章/成员等现有结构、`openComposer` / `onComposePublished` 及真实组件 caller；不恢复 master 已迁出的 onPostSubmit 和页内媒体发布器。治理原精确 owner/target 登录续接、原票码核销 proof / 世代 / UNKNOWN 恢复、每个 scan loading 的自身收尾全部保留。原页内发帖唯一新增 guard 移交实际 post-compose 的 review，而不是丢掉或复活旧入口。
2. `pages/square/list/index.js`：保留 master 当前商业身份 `_syncCommunityAccess` 读/写资格、当前账号刷新/撤下与共用 composer、编辑/返回刷新等行为。迁入治理 like/favorite/follow/report 的 owner/目标及 hide/unload 代次，删去文本冲突中的旧 submitFormData（不能双 writer）。原 selectedActivity、正文和媒体的发布保护在实际组件 review 中闭合。
3. `tests/unit/get-auth-matrix-contract.test.js`：保留双方全部 API/角色登记、master 的 nearby 20km 回退读取及治理的 club/lead 原解锁回执读取；保留完整真实 Java owner 委托链、scope/会员/权限拒绝和变体负控。另追加 2 个声明：当前账号/credential 的社区权限投影及资格撤销；实际共用发布器的 club/ref/edit/content 登录续接、广场写资格早于 intent、UNKNOWN 收尾与禁止复活旧 writer。

## 共享 review 与必要装配（root 串行）

`post-compose.review.patch.b64` 包含 **完整可重建差异**、3 个共享后像（不是整类覆盖 root）：

- `pages/square/components/cy/post-compose/index.js`：实际 onPublish 保留原当前会员/编辑/内容/单飞与 canonical intent；迁入原两个页内入口的 owner、clubId、refType/refId、editId、内容/关联、公告权限目标；cancelPublish 使原登录续接代次失效，仍原样保留 pending→UNKNOWN 和 abort。
- 原广场 `typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(true)` 拒绝条件迁到实际广场 writer，早于发布意图与外呼。俱乐部新建路径继续原 `/api/club/post/create` 合同，不借广场 flag 放行或扩大角色；club+editMode 实际走社区 writer 时仍必须过社区 gate。
- `square-post-compose-context.test.js` / `square-post-compose-edit-contract.test.js`：只补明确原账号的 server-open features、owner 与 credential 投影；换账号不自动重授权，不默认 guard=true，原所有内容/ID/并发/编辑/失败/UNKNOWN 断言不变。其余直接调用 fixture 已有明确 canUseCommunity 端口或只走原俱乐部授权，不重发整套 fixture。

此 review 必须与 3 owned 后像一起由 root 合入；公共 app ensureLoginFor 的三参合同依赖原 a50 治理源，未改 app/权限 API/组件注册/样式/分包。

## root 独占计数的精确触点

本片 **未改生成物、baseline、CI、台账与实际冻结计数**。get-auth 的冲突两侧都写 40，但 master 新增的是 `/api/roam/entry/nearby` radius20000，而治理新增的是 `/api/club/lead/unlock-chapter-receipt`，是不同真实调用。两条 MATRIX 均已保留。`root-wrapper-count.review.patch.b64` 仅给 root 审装 count 40→41 的一行建议；这是两冻结源的登记差额，不是执行扫描。最终以 root 同源实际生成/验证为准，不删任何读取或改范围去迁就旧数。

## 运输与验证边界

owned-source.patch.b64 只含 3 owned 路径；shared patch 由 master d4 精确重建；count review 以本 donor 的 auth 后像为前像。pre/post 的 SHA256、原三方 blob、patch 字节数/摘要均随附。没有删除文件，3 路后像均有摘要（即使某一路与任一供体相等也不会漏报）。

没有运行项目 parse/import、Node/Python/Java/JUnit/MySQL/HTTP/Redis/Docker、CI 或微信工具。未增加依赖/表/接口/费用/权限政策，未改旧 SQL、root CI 或预算/堆/线程。Git diff/apply-check 与文本/摘要仅是 SOURCE 运输核对，不是任何验收通过；新 donor 不承接旧 SHA 的 PASS。
