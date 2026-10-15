# B 重建候选（新的 v3，不是旧 B-v2 恢复）

只拥有 3 生产源、原 ImServiceImplTest 合法路线 fixture 增量、1 新专项测试。公开 A-v2 基础已逐字比对，当前 0f0c32f 可应用 candidate.patch。源码未写回共享仓库。

调用链：listConversations → projectedSummary → selectLatestForProjection（包含 BLOCKED 最新行，不回退更早行）；listMessages 保留既有成员资格/分页条件，转为副本后 projectRouteMessage；send 成功与同 clientMessageId 回执均使用同一投影。原 content/extraJson/requestHash 和会话 digest 不写回。

投影规则：仅路线卡或旧 [路线] 摘要；既有 selectCmsTopicDetailById(topicId, viewerId) 为读者资格基础，再要求 status=1、userStatus=1、未删、clubMemberOnly≠1、消息 NORMAL。私密标记 clubMemberOnly=1 统一隐藏，包括作者、成员、主理人、买家；保留原 topicId，显示“路线已不可用”。坏 JSON 不回显旧路线名称。普通卡与原摘要形态不改。未新增 Controller/API/协议/表。

primaryCirclePublicGate 的作者例外涉及圈层供给时效/模板/复审，不等于 clubMemberOnly 私密；本包不改该政策。前端已缓存名称和重新拉详情可能仍需单独核验，本包不动 UI，不承诺旧客户端缓存被清理。

9 个新专项用例是候选，未运行：不同 viewer；删除/下架/拒审；私密授权身份；历史扩展字段；坏 JSON；BLOCKED 摘要；普通卡/无原消息；发送成功副本；同请求回执。原合法发送 fixture 只增加已上架 status 与 viewer 读，不削断言。

红版本 retained 新 mapper 声明/XML 仅为编译同一测试，ImServiceImpl 保持 A-v2 原算法；预期新投影行为断言失败。绿版本是当前候选。根统一 VPS 执行：`-Dtest=ImRouteProjectionTest,ImServiceImplTest,ImLegacyReadLockTest -Dsurefire.failIfNoSpecifiedTests=false`（按既有多模块构建脚本）。无本地 Java、VPS、提交、推送。

已完成静态 diff 应用和 XML 解析；旧临时目录丢失，原 B-v2 hash/既往测试记录不能证明本次重建源。manifest 每源列新 hash/base hash，root 验收后再更新证据。
