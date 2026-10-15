# B05 调查交付：待授权，非修复通过

基线：`f72ddb14ab883e12e65fe2910f44d184725a2fd2`；共享候选上的未提交改动另计。未切分支、未提交、未运行 Java/数据库构建，未修改授权源码。根 AGENTS.md 明确要求“Ask first：改鉴权/RBAC 校验逻辑”；本项先提交具体方案，待用户明确批准。

## 确认的差异

`CommunityPostLifecyclePolicy.canView` 对作者的 DELETED 帖返回 false；`CommunityPostMapper.xml` 的 visibilityPolicy 作者分支只有 author_id，未排除 DELETED；`CommunityPostServiceImpl.detail` 直接信任 selectById 并 hydrate 正文、媒体和引用，未调用 canView。delete 使用 transitionCas 标记生命周期，并不立即清空正文。因此作者旧详情链接可继续得到已删正文/引用；comments 先调用 detail，作者也能通过该父帖检查。其他身份已被 PUBLISHED 条件拒绝。这是同作者删除语义不一致，未证实跨用户泄漏。

两身份 fixture（纯测试数据）：作者11、普通读者8、公开已发布帖1。两人先 GET 同一 `/api/v1/community/posts/1`，均应可读。删除后：现状源码推导作者仍可读、读者业务404；拟修后两人均业务404，comments 同一旧链接也应业务404。此处尚无真实HTTP或数据库执行结果，不称实际联调通过。

## 可审阅最小方案

`pending-authorization.patch` **未应用**：仅将共享 visibilityPolicy 作者分支加 `lifecycle <> 'DELETED'`；附一个 MyBatis 两身份旧链接预期修复测试，当前作者 assertNull 按源码应失败，但未执行。该测试只存在补丁内，不放自动测试目录、不 skip、不制造红门禁。补丁保留非 DELETED 的作者工作台状态，并验证其他身份仍不可见。

共享片段影响 selectById、feed、评论搜索/提及候选和普通通知的父帖检查；feed 本来仅取 PUBLISHED。revisions 先走 selectById，作者已删帖的编辑历史将同样不可读。SYSTEM_GOVERNANCE 通知豁免、锁行写路径、原始引用记录和30日媒体清除政策不变。批准前应确认以上读取范围符合既有删除合同。回退仅撤销补丁中一行XML并撤销新增测试；无DDL、无数据重写、无依赖/配置/UI/政策改动。

## 反证与未验

- 前任保留的自动测试 `b05LeavingCommunityRevokesTheSameDetailLinkWithoutMakingPostPublic` 描述会员8退出后同链接不可读、身份9不可读、解绑后 audience 仍为 COMMUNITY；本轮检查了代码，未执行，不能称通过。
- COMMUNITY→PUBLIC 的解绑赋值未发现；已有账户擦除套件也保留 COMMUNITY，不能报成已确认泄漏。
- 引用为 ACTIVITY/TOPIC/ROUTE/CLUB/POI，不是引用另一社区帖；服务端 selectReferenceSnapshot 白名单产 PUBLIC_SAFE，客户端 snapshotJson 未直接落存。持久快照本身不是越权证据；源对象后续撤回后的期望政策/业务联调尚未确认，不新增实时授权模型。
- 当前小程序广场实际消费 `/api/creativesquare`（square/list、square/detail），未发现消费 `/api/v1/community`；本差异不能声称修复当前小程序广场，也不切新链。
- 未验：Java编译/相关JUnit、VPS MySQL真实删除后独立读回、媒体URL/CDN和客户端缓存撤回、HTTP端到端、设备、部署。待授权后按现有VPS/CI资源运行相关测试，再由C99集成；无实际业务READY结论。

轻量校核只验证补丁可应用、XML可解析、diff空白规范，不能证明Java或授权行为通过。实际输出见 static-checks.txt。
