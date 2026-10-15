# A03 Claude合同：现有后端，不是发布证明

作者写入仍走 B02 的 POST `/api/topic/v2/create`、`/api/topic/v2/update`；章节 schemaVersion=1、blocks、nodes 和 ending 按已有DTO整包提交。ending.fallback 若出现只接受 JSON true/false，字符串 `"true"`、1、null 均拒绝（候选补丁待CI）。非兜底必须有非空 when；有结局章则恰好一个 fallback。权限/归属、发布政策完全沿用 B02，无新增授权。

读接口为 GET `/api/play/journey/stage-end?topicId=700&chapterId=11&activityId=0`、GET `/api/play/journey/ending?topicId=700&activityId=0`。身份来自登录 memberId，不接受客户端任意指定会员；服务核对本人的session和完成状态。AjaxResult code/msg/data，HTTP 200 不等于业务成功。未开始、“无权访问这一局”、未完成等沿原可观察错误；不新增错误枚举、分页或缓存合同。这两个GET无requestId，也不借读接口推进/更新内容。写命令 requestId/会话状态合同由A01维护，不能用 schemaVersion 代替请求身份。

协议 schemaVersion=1、路线 configVersion CAS、publishedConfigVersion/会话开始版本三者不同。不能在每次作者保存后提高 schemaVersion，不能因当前内容版本不同拒绝旧局。开局快照有 journeyStory/themeConfig 时读本局；极旧快照无键才沿旧回退。新候选以 snapshot.endingChapters 冻结结局正文，仅选中章按本局状态投影后下发；有键缺章返回null，缺整个键的历史局才实时回退。历史已丢正文无法追溯，不能承诺所有历史局已固定。读写补丁见 README，待CI。

兼容fixture（均为测试值）：

- 正常：两个JourneyStory endings均命中，code=a在前/b在后 → a；两个ThemeRuntime endings均无priority且命中 → 最后一个；显式priority最大胜，同值仍最后一个。不得为两个运行时统一UI排序而后端回填priority。
- 正常：开始冻结旧title，作者改为新title → 已冻结旧局继续旧title，新局读取新发布内容。
- 失败：章节 ending:{fallback:"true"} → 候选v2编译校验400，文案含“fallback 必须是布尔值”；未知condition op → 现有校验拒绝。
- 未知：旧局缺冻结键无法知道真实开始内容，沿原回退，不能标为历史恢复成功；未知协议返回剧情不可用；结局章已不存在时chapter=null，保留原标题/摘要降级，不产生替代章节。
- 快照fixture：有endingChapters键的局即使实时章节修改/删除仍只读冻结正文；空对象不回退；无此键的旧局维持原回退，可能无法恢复删除正文。候选尚待CI与独立读回。

UI条件编辑器、状态键面板、预演、结果卡和新玩法由Claude负责，不新增第二套状态键或运行时。Java/HTTP/SQL/设备fixture均待真实执行；本文件是源码合同与候选预期。
