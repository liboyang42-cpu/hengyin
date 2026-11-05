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


---

## 第二份(同一轮 k10 的云端 rig 版,另一个工作者;上面一份保留不动)

### k10 theme check: what in a stored theme would not play

Read-only. Nothing here writes to a database. Run it yourself on production; I could not.

#### What it lists

For each theme, from the **published** config (what players actually get):

- **Missing media:** a station voice (语音讲解), chapter narration, question audio, station music, story audio/image block or ending audio whose media is not approved, missing, an empty block, an `http://` address or not on our own storage.
- **Station with no way on:** a scan station without a check-in code or not scan-ready, a GPS station without coordinates, a station with no game and no scan/GPS arrival, a text question without an answer, a choice question without a correct option, a rule cut down to one character (the old editor typing bug fixed in this round).
- **Unreachable branch:** for branch themes (`BRANCH_GRAPH`), stations not reachable from the start, non-final stations with no way out, no final station. For any theme, a chapter whose 互斥组 (only one of the group can be done) still requires 全部完成.
- **Stations nobody reaches:** a route station that is not in any chapter's story flow; a node that is not in the published route.
- **Missing ending:** no ending, no fallback ending, an ending chapter missing from the snapshot or without text.
- Also: theme not on sale (status), deleted, no ticket, dates over.

#### Step 1: export (read only)

Put the theme ids in the `IN (...)` list. `-r` keeps the JSON as stored.

```sql
-- k10 theme check, part 1 (read only). One row per theme, tab separated.
-- Set the ids in the IN (...) list; 990068, 990069 and the other public themes.
SET SESSION group_concat_max_len = 16777216;
SELECT t.id,
       REPLACE(REPLACE(t.name, '\t', ' '), '\n', ' '),
       t.status,
       t.del_flag,
       t.start_date,
       t.end_date,
       t.route_mode,
       REPLACE(REPLACE(CAST(t.published_route_graph_json AS CHAR), '\t', ' '), '\n', ' '),
       REPLACE(REPLACE(IFNULL(CAST(t.published_theme_runtime_config_json AS CHAR), ''), '\t', ' '), '\n', ' '),
       (SELECT IFNULL(JSON_ARRAYAGG(JSON_OBJECT('id', n.id, 'name', n.name, 'chapter_id', n.chapter_id,
                                                'template_id', n.template_id, 'latitude', n.latitude,
                                                'longitude', n.longitude, 'checkin_code', n.checkin_code)), JSON_ARRAY())
          FROM cms_topic_node n WHERE n.topic_id = t.id),
       (SELECT IFNULL(JSON_ARRAYAGG(JSON_OBJECT('id', k.id, 'name', k.name, 'price', k.price)), JSON_ARRAY())
          FROM oms_ticket k WHERE k.owner_type = 1 AND k.owner_id = t.id AND IFNULL(k.del_flag, 0) = 0)
  FROM cms_topic t
 WHERE t.id IN (990068, 990069);
```

```bash
mysql -h <prod-db-host> -u <read-only-user> -p -N -B -r chengyinhubdb < k10_theme_check.sql > themes.tsv
```

#### Step 2 (optional): media states

The checker prints the `media-asset` ids it found. To check their review state:

```sql
SELECT id, review_state FROM app_media_asset WHERE id IN (<ids printed by the checker>);
```

```bash
mysql ... -N -B -r chengyinhubdb < assets.sql > assets.tsv
```

#### Step 3: check

Save the script below as `k10_theme_check.py` (Python 3, no packages):

```bash
python3 k10_theme_check.py themes.tsv assets.tsv
```

It prints one block per theme, either 「没有发现放不出来的地方」 or one line per problem. Exit code 1 if anything was found.

```python
#!/usr/bin/env python3
"""Read-only theme checker: lists what in a theme's stored config would not play.

Input: the TSV written by the SQL in integ/k10-theme-check.md (one theme per row),
optionally a second TSV of app_media_asset states. Never connects to a database.
Usage: python3 k10_theme_check.py themes.tsv [assets.tsv]
"""
import json, re, sys
from collections import defaultdict, deque

MEDIA_REF = re.compile(r'^media-asset:(\d+)$')
OWN_HOST = re.compile(r'^https://chengyin-oss\.oss-cn-shanghai\.aliyuncs\.com/upload/[0-9a-f]{32}\.(mp3|m4a|aac|wav|jpg|jpeg|png|gif)')


def parse_json(text):
    if text is None or text in ('', 'NULL', '\\N'):
        return None
    try:
        value = json.loads(text)
    except ValueError:
        return 'BAD'
    if isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


def media_issue(ref, assets, where):
    """Return a line if a media reference cannot play, else None."""
    if not ref:
        return None
    ref = str(ref).strip()
    m = MEDIA_REF.match(ref)
    if m:
        state = assets.get(m.group(1))
        if assets and state is None:
            return '%s: 媒体 %s 在媒体表里不存在' % (where, ref)
        if state and state != 'APPROVED':
            return '%s: 媒体 %s 审核状态 %s(玩家拿不到)' % (where, ref, state)
        return None
    if ref.startswith('/audio/merchant-games/') or ref.startswith('/profile/upload/'):
        return None
    if ref.startswith('http://'):
        return '%s: 媒体是 http 地址,真机不放:%s' % (where, ref[:80])
    if ref.startswith('https://') and not OWN_HOST.match(ref):
        return '%s: 媒体不在本站存储:%s' % (where, ref[:80])
    return None


def check_theme(row, assets):
    out = []
    tid, name, status, del_flag, start, end, route_mode = row[:7]
    snap = parse_json(row[7])
    runtime = parse_json(row[8])
    nodes_rows = parse_json(row[9]) or []
    tickets = parse_json(row[10]) or []
    head = '主题 %s「%s」' % (tid, name)
    if str(del_flag) not in ('0', ''):
        out.append('已删除')
    if str(status) != '1':
        out.append('未上架(status=%s):玩家打不开' % status)
    if snap in (None, 'BAD') or not isinstance(snap, dict):
        out.append('没有可读的发布快照(published_route_graph_json):玩家开局读不到路线')
        return head, out
    answers = snap.get('nodeAnswers') or {}
    configs = snap.get('nodeConfigs') or {}
    stations = snap.get('routeStations') or []
    chapters = snap.get('thoughtChapters') or []
    station_ids = [str(s.get('nodeId')) for s in stations]
    node_by_id = {str(n.get('id')): n for n in nodes_rows if isinstance(n, dict)}
    if not stations:
        out.append('路线里没有任何站点')
    # --- stations: a way to arrive and a way to finish
    for s in stations:
        nid = str(s.get('nodeId'))
        label = '站 %s「%s」' % (nid, s.get('nodeName') or '')
        ans = answers.get(nid) or {}
        vm = ans.get('validationMethod')
        node = node_by_id.get(nid, {})
        lat, lng = s.get('latitude'), s.get('longitude')
        if vm == 4 and not node.get('checkin_code'):
            out.append('%s: 扫码到达但没有打卡码,扫不过去' % label)
        if vm == 5 and not (lat and lng):
            out.append('%s: GPS 到达但没有坐标,到不了' % label)
        if s.get('scanReady') is False and vm == 4:
            out.append('%s: 扫码未就绪(scanReady=false)' % label)
        if ans and not ans.get('templateId') and vm not in (4, 5):
            out.append('%s: 没有绑定玩法,也不是扫码/GPS 到达,没有完成方式' % label)
        cfg = configs.get(nid) or {}
        qa = cfg.get('qa') or {}
        if qa.get('enabled'):
            mode = qa.get('mode')
            if mode == 'TYPE' and not str(qa.get('answerText') or '').strip():
                out.append('%s: 文字问答没有正确答案,答不对' % label)
            if mode == 'PICK':
                opts = [o for o in (qa.get('options') or []) if str(o.get('label') or '').strip()]
                if not any(o.get('correct') for o in opts):
                    out.append('%s: 选项问答没有可选的正确项' % label)
        pres = ans.get('templatePresentation') or {}
        rule = str(pres.get('ruleInstructions') or '')
        if 0 < len(rule.strip()) <= 1:
            out.append('%s: 玩法规则只剩一个字「%s」(编辑器逐字重建输入框的旧问题),玩家看到的是残句' % (label, rule.strip()))
        for where, ref in (('语音讲解', pres.get('audioUrl')), ('章节旁白', ans.get('chapterAudioUrl')),
                           ('题干音频', pres.get('questionAudio')), ('站点配乐', (cfg.get('backgroundMusic') or {}).get('audioUrl'))):
            issue = media_issue(ref, assets, '%s %s' % (label, where))
            if issue:
                out.append(issue)
    # --- story blocks: media and nodes that never appear in the route
    in_story = set()
    for ch in chapters:
        for b in ch.get('blocks') or []:
            if b.get('type') == 'node':
                in_story.add(str(b.get('nodeId')))
            if b.get('type') in ('audio', 'image'):
                if not b.get('url'):
                    out.append('章节 %s: 有一个空的%s块(没传文件)' % (ch.get('chapterId'), '音频' if b.get('type') == 'audio' else '图片'))
                issue = media_issue(b.get('url'), assets, '章节 %s 的%s块' % (ch.get('chapterId'), b.get('type')))
                if issue:
                    out.append(issue)
    for nid in station_ids:
        if chapters and nid not in in_story:
            out.append('站 %s 不在任何章节的故事流里:玩家读不到它' % nid)
    for nid, n in node_by_id.items():
        if nid not in station_ids:
            out.append('节点 %s「%s」不在发布路线里(改过但没重发?)' % (nid, n.get('name') or ''))
    # --- branches
    edges = snap.get('edges') or []
    if str(route_mode) == 'BRANCH_GRAPH' or edges:
        start = str(snap.get('startNodeId') or '')
        graph = defaultdict(set)
        for e in list(edges) + list(snap.get('fallbacks') or []):
            graph[str(e.get('fromNodeId'))].add(str(e.get('toNodeId')))
        seen, todo = {start}, deque([start])
        while todo:
            for nxt in graph[todo.popleft()]:
                if nxt not in seen:
                    seen.add(nxt); todo.append(nxt)
        terminals = {str(x) for x in snap.get('terminalNodeIds') or []}
        for nid in station_ids:
            if nid not in seen:
                out.append('分支:站 %s 从起点走不到' % nid)
            elif nid not in terminals and not graph[nid]:
                out.append('分支:站 %s 不是终点却没有出路(玩家卡在这里)' % nid)
        if not terminals:
            out.append('分支:没有终点站')
    # mutually exclusive groups + "all must be done"
    theme_cfg = snap.get('themeConfig') or runtime or {}
    if isinstance(theme_cfg, dict):
        for c in theme_cfg.get('chapters') or []:
            groups = c.get('exclusiveGroups') or []
            done_rule = (c.get('completion') or {}).get('type')
            if groups and done_rule in (None, 'ALL'):
                out.append('章节序号 %s: 有互斥组 %s 却要求「全部完成」,互斥的另一站永远完成不了' % (c.get('index'), groups))
    # --- ending
    story = parse_json(snap.get('journeyStory')) if isinstance(snap.get('journeyStory'), str) else snap.get('journeyStory')
    endings = ((story or {}).get('ending') or {}).get('endings') or [] if isinstance(story, dict) else []
    ending_chapters = snap.get('endingChapters') or {}
    if not endings:
        out.append('没有结局:通关后只有通用完成卡,没有作者写的尾声')
    elif not any(e.get('fallback') for e in endings):
        out.append('结局都带条件、没有兜底结局:条件都不满足时没有尾声')
    for e in endings:
        cid = e.get('chapterId')
        if cid is None:
            continue
        ch = ending_chapters.get(str(cid))
        if not ch:
            out.append('结局「%s」指向的结局章 %s 不在快照里' % (e.get('title'), cid))
            continue
        if not (ch.get('blocks') or []):
            out.append('结局章「%s」没有正文' % ch.get('name'))
        issue = media_issue(ch.get('audioUrl'), assets, '结局章「%s」音频' % ch.get('name'))
        if issue:
            out.append(issue)
    # --- tickets / dates
    if not tickets:
        out.append('没有票种:玩家报不了名')
    if end and str(end) < __import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M:%S'):
        out.append('档期已结束(%s)' % end)
    return head, out


def main():
    rows = [line.rstrip('\n').split('\t') for line in open(sys.argv[1], encoding='utf-8') if line.strip()]
    assets = {}
    if len(sys.argv) > 2:
        for line in open(sys.argv[2], encoding='utf-8'):
            parts = line.rstrip('\n').split('\t')
            if len(parts) >= 2 and parts[0].isdigit():
                assets[parts[0]] = parts[1]
    refs = set()
    for row in rows:
        refs.update(re.findall(r'media-asset:(\d+)', '\t'.join(row)))
    bad = 0
    for row in rows:
        if len(row) < 11 or not row[0].isdigit():
            continue
        head, issues = check_theme(row, assets)
        print(head + (':没有发现放不出来的地方' if not issues else ':'))
        for line in issues:
            print('  - ' + line)
        bad += len(issues)
    if refs and not assets:
        print('\n(没有给媒体状态文件:media-asset 引用只按格式检查。要查审核状态,运行第 2 段 SQL,id 为:%s)' % ','.join(sorted(refs, key=int)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
```

#### Proof in the rig (not production)

Real MySQL 8 in the cloud rig, themes made through the editor in this round:

```
主题 990015「磁带雨夜」:
  - 未上架(status=0):玩家打不开
  - 没有可读的发布快照(published_route_graph_json):玩家开局读不到路线
主题 990016「磁带雨夜」:
  - 未上架(status=0):玩家打不开
  - 没有可读的发布快照(published_route_graph_json):玩家开局读不到路线
主题 990017「磁带雨夜」:
  - 站 104「报亭」: 玩法规则只剩一个字「拍」(编辑器逐字重建输入框的旧问题),玩家看到的是残句
```

990015 and 990016 are the two drafts that the editor could not save (see the result file). 990017 is the published one; its first station rule really is 「拍」 in the database because of the typing bug.

The same 990017 row with problems planted on purpose (no answer, no check-in code, a media item still on HOLD, a 互斥组 under 全部完成, no ending, no ticket):

```
主题 990999「夹具·坏主题」:
  - 站 104「报亭」: 玩法规则只剩一个字「拍」(编辑器逐字重建输入框的旧问题),玩家看到的是残句
  - 站 104「报亭」 语音讲解: 媒体 media-asset:999 审核状态 HOLD(玩家拿不到)
  - 站 105「天桥」: 扫码到达但没有打卡码,扫不过去
  - 站 105「天桥」: 扫码未就绪(scanReady=false)
  - 站 105「天桥」: 文字问答没有正确答案,答不对
  - 章节序号 1: 有互斥组 [[105, 106]] 却要求「全部完成」,互斥的另一站永远完成不了
  - 没有结局:通关后只有通用完成卡,没有作者写的尾声
  - 没有票种:玩家报不了名
```

Not covered: whether an approved media file still exists on OSS (it checks the database, not the bucket), and themes that were never published (the checker reads the published snapshot only and says so).
