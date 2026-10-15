# Claude 作者端合同（当前源码，不是发布证明）

基线 `d551c6babd018d41aec90dad2c0acaf1c534644d` 加 B02 候选。UI/保存反馈/预演不在 Codex 变更范围。

| 方法与路径 | 输入与输出 |
|---|---|
| POST `/api/topic/create` / `/api/topic/v2/create` | JSON TopicCreateDTO，`saveDraft:1` 存草稿；普通返回 `data:topicId`，v2 返回 `data:{topicId,auditTaskId,reviewState,bundledTemplateIds,published}`。v2 用于章节 blocks，不用旧入口丢掉 blocks。|
| POST `/api/topic/update` / `/api/topic/v2/update` | JSON TopicUpdateDTO（继承创建字段，多 `id`），沿用原主题。草稿事实来自服务器，v2 `saveDraft:0` 可提交既存草稿；普通 data=id，v2 data 为组合提交对象。|
| POST `/api/topic/edit-detail` | 表单参数 id、scope；data 包含 topic、chapters、tickets、collaboratorIds、journeyRules、journeyStory、themeRuntimeConfig、openingChapterId、editScope。无分页/本地缓存承诺，每次保存后重新请求。|
| POST `/api/topic/publish-draft` | 表单参数 id、scope；沿用草稿 id，检查完整度、引用及原发布政策。simple 成功无 data 业务对象，pro 返回组合提交对象；已不是草稿会报业务失败。|

归属：本人；显式 `scope:MERCHANT` 时通过既有商家 PROJECT_MANAGE 解算发布 owner。`editScope` 为 FULL/WHITELIST/SELLING_FROZEN 对应的现有枚举，WHITELIST 只能改文案图片等白名单；不可凭客户端 saveDraft 或 scope 扩权。沿用 AjaxResult 的 code/msg/data；不能只凭 HTTP 200 判成功。v2 blocks 校验错误 code=400，其他既有业务错误可为500和短中文文案；没有新增稳定错误枚举。

B02 修复的缺席语义：更新时 publishMode、teamMode/teamMaxMembers、xpBudget 和 selfPlay 缺席沿用旧值；selfPlay 缺席时未传的 price/quota 也保留；显式 selfPlay/teamMode=0 仍关闭。productType 和票 mode 都缺席时才用存量合法类型；旧票 mode 的兼容推断继续存在。创建默认不变。非这些字段不要泛化成 PATCH：chapters/tickets/collaboratorIds 全量替换；缺席但库中非空会被完整 payload 闸拒收，显式 [] 表示清空。journeyRules/journeyStory/themeRuntimeConfig 空白或缺席不抹旧值。

章节/节点数据库 ID 会重建，保存后重新 edit-detail，以返回的新 id、blocks.nodeId 及 topic.configVersion 作为下一次编辑基线。首次节点可传稳定 clientNodeKey；分支图支持 client key，已有节点使用读回 id。schemaVersion 是协议形状，configVersion 是路线 CAS，publishedConfigVersion 是发布快照版本；三者均不是全主题通用 revision，也没有 create requestId 幂等合同。

测试 fixture（身份/ID 仅测试值）：旧 topic 700 为 member42 草稿，teamMode=2/max=3/selfPlay=1/price=8.00/quota=9/xpBudget=6200/publishMode=simple；更新 `{id:700,name:"只改名称",chapters:[],tickets:[]}` 应生成保留上述配置的持久化补丁。显式 `{selfPlay:0,teamMode:0,xpBudget:1200,publishMode:"pro"}` 应按新值保存。异常 `{teamMode:7}` 被拒绝。完整 HTTP/SQL 读回尚未执行。

丢响应：若已持有 id，先读取原 id 内容和发布状态，不能改调用 create。首次 create 丢响应仍是 UNKNOWN，无 requestId 可精确定位；不得自动按名称找到一条就说恢复成功，也不得承诺重发不会重复。已发布的 publish-draft 重试可能报“不是草稿”，应读真实事实。接口未改为回执查询，Claude 需保留该限制。
