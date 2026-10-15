# B05 给 Claude 的合同（当前未改接口）

当前小程序广场仍为 `/api/creativesquare`；本单不改其UI、方法、DTO或切换内容链。以下是另一个既有 `/api/v1/community` 合同，修复尚待授权。

- GET `/api/v1/community/posts/{id}`：返回 AjaxResult，data 包括 post/media/references/mentions/viewerIsAuthor 等。viewer 由服务端身份取得，匿名以0参与检查。普通读者须 PUBLISHED 且符合 PUBLIC/FOLLOWERS/COMMUNITY 以及屏蔽/静音等既有过滤；引用不是独立授权入口。
- GET `/api/v1/community/posts/{id}/comments?cursor=&limit=`：先重查父帖 detail，再返回 items/nextCursor。游标和有界 limit 维持现状；本单无新增缓存策略。
- 不存在或不可见：AjaxResult `code:404,msg:"帖文不存在或不可见"`。Controller 捕获 ServiceException 后返回包装对象，未设置 HTTP 404，因此不可把业务404写成传输层404；实际HTTP需VPS确认。
- 已删作者旧链接当前仍可读，这是待批准修复差异。拟修后作者和其他身份均业务404，父帖评论亦不可读；非删除作者草稿/审核/受限/隐藏/移除状态保持现有规则。revisions 对已删作者同受影响。
- 本补丁无新字段、requestId、version或分页语义；删除原命令requestId/expectedVersion和既有幂等/CAS不改。读取不得靠历史成功响应视作当前仍获授权。

验收 fixture：测试作者11、普通读者8、帖1；公开PUBLISHED时两者详情成功，DELETED后同旧URL两者业务404。失败fixture为身份9读COMMUNITY帖；成员8退出或 community_id 清空后同URL不可读，audience保持COMMUNITY。未知fixture：超时不能推断删除/撤回已完成，重新读取既有服务端事实。上述均为待执行验收要求，不代表HTTP测试已经通过。

UI、新可见性选择、举报申诉/审核工作台、新引用策略、内容链切换均不在本单；不按本文件启动新页面。
