# 沉浸式主题：只读导出与离线检查

用途：由 owner 用既有受保护的只读流程导出 990068、990069 或其它指定主题，再在本机离线检查。这里没有执行生产查询，没有改库，也没有联网探测媒体。

SQL 依据当前 `CmsTopicMapper.xml`、`CmsTopicChapterMapper.xml`、`CmsTopicNodeMapper.xml` 和 `CmsMemberTemplateMapper.xml` 的字段。先确认目标库具备这些列；缺列要记录，不要改成写操作或迁移。下面只读事务每个主题返回一行 JSON。只改最后的主题 ID 列表，不要导出全部主题。

```sql
START TRANSACTION READ ONLY;
SELECT JSON_OBJECT(
  'schemaVersion', 1,
  'topic', JSON_OBJECT(
    'id', t.id, 'routeMode', t.route_mode,
    'configVersion', t.config_version,
    'publishedConfigVersion', t.published_config_version,
    'publishedRouteGraphJson', t.published_route_graph_json,
    'journeyStoryJson', t.journey_story_json,
    'themeRuntimeConfigJson', t.theme_runtime_config_json,
    'publishedThemeRuntimeConfigJson', t.published_theme_runtime_config_json,
    'lookJson', t.look_json, 'audioUrl', t.audio_url),
  'chapters', COALESCE((
    SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'id', c.id, 'sortId', c.sort_id,
      'audioUrl', c.audio_url, 'blocks', c.blocks))
    FROM cms_topic_chapter c WHERE c.topic_id = t.id
  ), JSON_ARRAY()),
  'nodes', COALESCE((
    SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'id', n.id, 'chapterId', n.chapter_id, 'sortId', n.sort_id,
      'unlockAfterNodeId', n.unlock_after_node_id,
      'checkinConfigured', n.checkin_code IS NOT NULL AND n.checkin_code <> '',
      'template', IF(m.id IS NULL, NULL, JSON_OBJECT(
        'id', m.id, 'validationMethod', m.validation_method,
        'audioUrl', m.audio_url, 'questionAudio', m.question_audio,
        'storyJson', m.story_json, 'advancedConfigJson', m.advanced_config_json))))
    FROM cms_topic_node n
    LEFT JOIN cms_member_template m ON m.id = n.template_id
    WHERE n.topic_id = t.id
  ), JSON_ARRAY())
) AS theme_export
FROM cms_topic t WHERE t.id IN (990068, 990069);
ROLLBACK;
```

把结果中每行 JSON 单独保存为本机文件，例如 `theme-990068.json`；使用原始列值，不要带 MySQL 表格边框、列标题或外层转义。导出可能包含未公开剧情、谜底和签名媒体地址，只保留在 owner 的私有本机目录，不提交 Git，不贴到聊天。

```sh
python3 docs/delivery/walk-fix-20261102/integ/k10-theme-check.py --selftest
python3 docs/delivery/walk-fix-20261102/integ/k10-theme-check.py /private/local/path/theme-990068.json
```

退出码：0 表示未发现已实现检查范围内的结构问题；1 表示有待处理项；2 表示文件或导出形状不能检查。输出只带主题/站点编号、字段路径和问题，不带媒体 URL、签名或原始配置。

| 分类 | 当前能检查的内容 |
| --- | --- |
| BLOCKER | 坏 JSON/类型、空媒体块、丢失模板或章节、丢失故事站点、重复编号、前置站点缺失/循环、分支起终点缺失、坏连接、不可达步骤、走不出终点的闭环、缺少已发布路线、结局章不存在、默认结局数量错误 |
| MISSING | 沉浸式要求的开局章、结局清单或主题背景音乐未配置；这不等于旧主题一定不能完成 |
| REVIEW | 非 HTTPS/相对媒体地址、可能过期的签名地址、缺少完成方式、路线条件尚未实跑 |

这不是发布许可。路线图只检查拓扑，不能判断所有条件组合是否可满足；模板存在不证明玩家有提交入口，默认结局存在不证明一定能触发。媒体块地址存在不证明文件可读或可以解码。当前主题行、章节、模板也不等于已购订单的冻结内容，草稿和已发布版本必须分开看。

仍要逐站从玩家界面验证：开局音乐/视频、背景音乐、旁白、拍照、扫码、每条分支和彩蛋的命中/错过、结局音乐/视频、完成卡及重开。视频入口、实际上传文件、节点完成合同、主题 runtime 条件、媒体大小/弱网/预加载、四种外观均需要走查；本工具不把这些写成 PASS。手机部分按 [k10-phone-script.md](k10-phone-script.md) 执行。

STATUS: PARTIAL - 离线工具已提供；未导出或检查 owner 的生产主题，结构检查不能替代完整游玩。
