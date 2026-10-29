# H：119d 章节块泛型合同修补

结论：SOURCE 已修；编译、测试、CI 均 NOT_RUN。仅修改 PlayNodeQueryApplication 的两处静态泛型声明，不改运行分支、响应字段或授权逻辑。

- parent：119d3f87bbf89162d47db837c404b361915d1b81
- parent tree：1d96922c384e9fd1cbacb447ef0dc2e9eb8bf407
- 原 Java blob：13e42e4336c4fcd3486eec53a846300869b6509f
- 真实首因：run40215827511/job121236623557，生产 compile 在原568/579行报告 List<Object> 与 List<Map<String,Object>> 双向不兼容。原失败记录不改写为通过。

实际链：ApiPlayProgressController.nodes → PlayEntryApplication（229/237/239行逐节点投影，242行 finishNodes）→ PlayNodeQueryApplication.finishNodes → ChapterBlockReadSupport.resolveFromTopicNodes / projectForPlayer。

已读 parent 的 ChapterBlockReadSupport（blob 0ed467d695cb1123c41aad3c707ebca0cda4a405）：原 BLOCK_LIST_TYPE、resolve、resolveFromTopicNodes、projectForPlayer 输入/返回均为 List<Object>；projectForPlayer 明确保留非 Map 块，只按原 Map 条件/tag 与首个未完成 node 截断。不是把列表强制改成只有 Map 的新业务合同。

修复：
1. blocks 局部变量使用原 helper 的 List<Object>。
2. 冻结 thoughtChapters.blocks 的 Jackson TypeReference 同样使用 List<Object>。

没有 raw/unchecked cast，没有扩大块类型白名单或权限。旧 live 分支、原冻结 thoughtChapters 匹配、原章节准入、completedNodeIds/storyTags 授权投影、答案/提示权限、原局/当前配置选择、原公开字段逐字保留。没有生产 shared helper/interface 或其他 owner 文件改动。

现成相关测试源码：PlayFrozenContentProjectionTest（parent blob c127a8000949147ed9e284ddb3dfa37231d1fbf5）、ChapterBlockReadSupportTest（8923067696f331d1092ad6a57ae6437e75f1f456），以及原 ApiPlayPuzzleHintTest 的真实 nodes 装配场景；本片均不修改、删除或放宽断言。本问题为声明与现有 helper 不一致的编译错误，不新增镜像实现的运行测试；root 在收齐同轮源码后定点编译及保留用例验证。

review.patch.b64 解码得到完整两行 delta，PATCHES.sha256 为解码补丁摘要；POSTIMAGE.sha256 为唯一 Java 文件后像。提交只从该公共 parent 建立，未接私有历史；原 H 工作树与他人修改未回退。无 Java/DB/Node/Python/供应商/CI 执行，无验收通过声明。
