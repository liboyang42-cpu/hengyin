const templateIdOf = require('./template-adoption.js').id;
// 主题云端草稿:保存草稿 → 经 edit-detail 回读确认 → (可选)发布这份已保存的草稿。
//
// 三种「保存」别混:
//  - 本机自动保存(proEditorDraft):每次编辑写本机 storage,只防杀进程丢稿;
//  - 保存草稿(本模块):v2/create 或 v2/update 带 saveDraft=1,写进服务端但不上架、不送审、不占在架额度;
//  - 发布:先按「保存草稿」存下最新内容并回读确认,再调 /api/topic/publish-draft 发布这份已持久化的草稿。
//
// 依赖注入(页面保留各自 UI):
//  save(payload, cb)      cb({ ok, data?, msg?, networkError? }),data 为 topicId 或 { topicId, recovered?, ... }
//  readback(topicId, cb)  cb({ ok, data?, msg?, networkError? }),data 为 edit-detail 的回包
//  publish(topicId, cb)   cb({ ok, data?, msg?, networkError? })
//  publishCheck(topicId, cb)(可选) 发布前试检查,cb({ ok, msg? });ok=false 时不发布,按「已存未发布」报 onFail
//
// handlers:onSaved(topicId) 服务端已接受(拿到主题号,后续保存走 update,避免重复建题);
//           onSuccess({ topicId, detail, published? }) / onFail({ stage, topicId?, detail?, msg?, networkError?, unknown? })。
//           hasChanges()(可选,仅保存草稿):回读确认后若返回 true,改报 onFail({ stage: 'readback', localChanges: true, detail })。
//
// 结果不确定(网络中断/回包丢失)与确定失败要分开:
//  - 首次建草稿丢回包:页面保留原意图键与输入;recovered 后先回读原保存,再由页面处理后续新编辑;
//  - 发布丢回包,或重试时服务端说「不是草稿」:先回读真实状态,已发布就按已发布展示,不再说「没发布出去」。

const templateBinding = require('./template-binding.js');
const initial = require('./story-initial-state.js');
const routeGraph = require('./topic-route-graph.js');
const vocabulary = require('./story-vocabulary.js');
const { PRESETS, LEGACY_ALIAS, normalizeAtmosphere } = require('../../../../utils/chapter-atmosphere.js');

function topicIdOf(data) {
  const id = data && typeof data === 'object' ? data.topicId : data;
  if (!['number', 'string'].includes(typeof id) || String(id).trim() === '') return 0;
  return Number.isSafeInteger(Number(id)) && Number(id) > 0 ? Number(id) : 0;
}

function matchingTopic(detail, topicId) {
  const topic = detail && detail.topic;
  return topic && Number(topic.id) === Number(topicId)
    && ['number', 'string'].includes(typeof topic.status) && String(topic.status).trim() !== ''
    && [0, 1, 2].includes(Number(topic.status));
}

function confirmedPublication(data, topicId) {
  return data && typeof data === 'object'
    && topicIdOf(data) > 0 && topicIdOf(data) === Number(topicId)
    && (data.published === true || data.published === false
      && (data.commercialAwaitingReview === true || ['PENDING', 'ESCALATED'].includes(data.reviewState)));
}

function parseConfig(raw) {
  try { return templateBinding.parse(raw) || {}; } catch (error) { return null; }
}

/* 站点身份 → 位置(第几章.第几站)。服务端重建站点会换编号、不回传 clientNodeKey,
   章节顺序与站点顺序是两边都持久的,所以按位置对齐,而不是比生编号。 */
function positionsOf(chapters, listKey, keyOf) {
  const map = new Map();
  (chapters || []).forEach((chapter, ci) => ((chapter && chapter[listKey]) || []).forEach((node, ni) => {
    const key = keyOf(node);
    if (key) map.set(key, ci + '.' + ni);
    if (Number(node && node.id) > 0) map.set(String(Number(node.id)), ci + '.' + ni);
  }));
  return map;
}

function sorted(obj) {
  return JSON.stringify(Object.keys(obj || {}).sort().map(key => [key, obj[key]]));
}

/* 只比作者写下的规则;编号、slotDigest、服务端编译出的字段不参与。 */
function authoredConfig(cfg, pos) {
  const at = id => pos.get(String(id)) || ('?' + id);
  const chapters = (cfg.chapters || []).map((ch) => {
    const completion = ch.completion && ch.completion.type === 'ANY_N'
      ? 'ANY_N:' + ch.completion.quantity + ':' + (ch.completion.overflowSlot || '') : 'ALL';
    const row = {
      index: ch.index,
      completion,
      groups: (ch.exclusiveGroups || []).map(group => group.map(at).sort().join(',')).sort(),
      extra: (ch.extraNodes || []).map(item => at(item.nodeId) + '=' + item.slot).sort(),
      costs: [Number(ch.hpCost) || 0, Number(ch.luckCost) || 0].concat((ch.statCosts || []).map(c => c.statKey + '=' + c.amount).sort()),
      grants: (ch.grants || []).map(g => g.itemId + 'x' + g.quantity + '@' + (g.timing || '')).sort(),
    };
    const empty = completion === 'ALL' && !row.groups.length && !row.extra.length && !row.grants.length
      && row.costs.length === 2 && !row.costs[0] && !row.costs[1];
    return empty ? null : row;
  }).filter(Boolean).sort((a, b) => a.index - b.index);
  const bindings = Object.keys(cfg.templateBindings || {}).map((key) => {
    const value = cfg.templateBindings[key] || {};
    return at(key) + ':' + templateIdOf(value.templateId) + ':' + sorted(value.values) + sorted(value.items);
  }).sort();
  const game = cfg.gameplayRuntime || {};
  return {
    chapters,
    bindings,
    quests: (game.quests || []).map(q => at(q.nodeId) + '>' + q.actKey).sort(),
    rations: ((game.survival || {}).rations || []).map(r => [r.actKey, r.itemId, r.quantity, r.fallbackHpCost || 0,
      r.fallback ? r.fallback.statKey + '=' + r.fallback.amount : ''].join('|')).sort(),
    items: (cfg.items || []).map(i => i.id + ':' + (i.name || '') + ':' + (i.slotCost || 1)).sort(),
    mode: (game.runtimeScope || '') + '/' + (game.worldMode || ''),
  };
}

const CONFIG_LABELS = { chapters: '章节规则', bindings: '节点玩法资源对应', quests: '节点与章节的对应', rations: '章末配给', items: '物品', mode: '进行方式' };

function initialOf(raw) {
  try {
    const rules = initial.parse(raw || '');
    const pick = s => (s && s.enabled ? [s.init, s.max] : []);
    return JSON.stringify([pick(rules.hp), pick(rules.luck), (rules.attributes || []).map(a => [a.key, a.initial, a.max])]);
  } catch (error) { return 'invalid'; }
}

// 只允许服务端补字段；作者明确写下的规则不能在回读时丢失。
// 初始状态的缺省兼容仍由 initialOf 处理，其他规则不能缩减成 hp/luck 投影。
function authoredRulesMatch(sent, stored) {
  const parse = raw => {
    const value = typeof raw === 'string' ? JSON.parse(raw.trim() || '{}') : (raw || {});
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid rules');
    return value;
  };
  const matches = (want, have) => {
    if (Array.isArray(want)) return Array.isArray(have) && want.length === have.length
      && want.every((row, index) => matches(row, have[index]));
    if (want && typeof want === 'object') return !!have && typeof have === 'object' && !Array.isArray(have)
      && Object.keys(want).every(key => matches(want[key], have[key]));
    return typeof want === 'string' && typeof have === 'string' ? want.trim() === have.trim() : want === have;
  };
  try {
    const want = parse(sent), have = parse(stored);
    // 新旧主题缺省均 true；显式 false 必须在云端仍为 false。
    if (have.natAuto == null) have.natAuto = true;
    return matches(want, have);
  } catch (error) { return false; }
}

// 只核对当前写接口实际持久的票字段；totalStock 写入 totalInventory，票号与顺序由服务端决定。
function ticketMatches(before, after, productType) {
  if (!before || typeof before !== 'object' || Array.isArray(before)) return false;
  const numbers = ['price', 'totalStock', 'teamSize', 'gatherLng', 'gatherLat', 'needAudit'];
  const fields = ['name', 'startTime', 'endTime', 'saleStartTime', 'saleEndTime',
    'meetingPoint', 'meetingPointAddress', 'refundRule', 'refundSupported', 'syncWithTheme'].concat(numbers);
  if (productType != null && Number(after.mode) !== Number(productType)) return false;
  return fields.every(field => {
    if (before[field] === undefined) return true;
    const left = before[field], right = after[field === 'totalStock' ? 'totalInventory' : field];
    if (numbers.includes(field)) {
      // 地图坐标落入 DECIMAL(10,6)，其余数字只消除字符串/小数尾零差异。
      const numeric = value => {
        if (value == null || value === '') return null;
        if (!['number', 'string'].includes(typeof value) || !Number.isFinite(Number(value))) return NaN;
        return ['gatherLng', 'gatherLat'].includes(field) ? Number(Number(value).toFixed(6)) : Number(value);
      };
      return numeric(left) === numeric(right);
    }
    return (left == null ? '' : left) === (right == null ? '' : right);
  });
}

function stable(value) {
  return JSON.stringify(value, (key, row) => row && typeof row === 'object' && !Array.isArray(row)
    ? Object.keys(row).sort().reduce((out, field) => { out[field] = row[field]; return out; }, {}) : row);
}

function authoredRoute(raw, positions) {
  const graph = typeof raw === 'string' ? JSON.parse(raw) : JSON.parse(JSON.stringify(raw));
  if (!graph || typeof graph !== 'object' || Array.isArray(graph)) throw Error('invalid route');
  const at = id => {
    const key = String(id == null ? '' : id);
    const position = positions.get(key) || positions.get('client:' + key);
    if (position === undefined) throw Error('unknown route node');
    return position;
  };
  graph.startNodeId = at(graph.startNodeId || graph.startNodeKey);
  graph.terminalNodeIds = (graph.terminalNodeIds || graph.terminalNodeKeys || []).map(at);
  (graph.edges || []).forEach(edge => {
    edge.fromNodeId = at(edge.fromNodeId || edge.fromNodeKey);
    edge.toNodeId = at(edge.toNodeId || edge.toNodeKey);
    edge.conditions = (edge.conditions || []).map(c => vocabulary.mapNodes(c, row => {
      const result = Object.assign({}, row, { nodeId: at(row.nodeId || row.nodeKey) });
      delete result.nodeKey;
      return result;
    }));
  });
  (graph.fallbacks || []).forEach(row => {
    row.fromNodeId = at(row.fromNodeId || row.fromNodeKey);
    row.toNodeId = at(row.toNodeId || row.toNodeKey);
  });
  (graph.nodeRequirements || []).forEach(row => { row.nodeId = at(row.nodeId || row.nodeKey); delete row.nodeKey; });
  return stable(routeGraph.normalizeGraph(graph).graph);
}

// 对照 ChapterFlowCompiler.materializeBlocks；只忽略服务端补出的 key，不能忽略正文、条件或节点引用。
function storedBlock(block, nodes, submitted, keepKey) {
  const row = {};
  const fields = {
    text: ['content', 'who', 'level', 'when', 'beat', 'field', 'qid', 'npcId', 'claimLater'],
    node: [], image: ['url'], audio: ['url'], mood: ['mood'], thought: ['thoughtKey'],
    voice: ['who', 'content', 'when'], odd: ['level'], reveal: ['who', 'content'], dream: ['title', 'images'],
  };
  if (!block || !Object.prototype.hasOwnProperty.call(fields, block.type)) throw new Error('invalid block');
  row.type = block.type;
  if (keepKey && block.key != null) row.key = block.key;
  for (const field of fields[block.type]) if (block[field] != null) row[field] = block[field];
  if (block.type === 'node' || block.type === 'text' && String(block.beat || '').trim()) {
    const id = submitted ? (nodes[block.nodeIndex] || {}).id : block.nodeId;
    if (!topicIdOf(id)) throw new Error('invalid block node');
    row.nodeId = String(id);
  }
  if (block.type === 'node' && block.locationRequired === false) row.locationRequired = false;
  if (['text', 'reveal'].includes(block.type) && row.who != null) {
    row.who = row.who.trim(); if (!row.who) delete row.who;
  }
  if (block.type === 'text' && row.beat) {
    for (const field of ['beat', 'field', 'qid']) if (row[field] != null) row[field] = row[field].trim();
  }
  if (['voice', 'reveal'].includes(block.type)) row.content = String(row.content || '').trim();
  if (block.type === 'mood') {
    // 回读不能用展示层 DEFAULT 兜底掩盖丢字段；正常编译输出必有合法 mood。
    const mood = typeof row.mood === 'string' ? row.mood.trim().toUpperCase() : '';
    if (!PRESETS.some(preset => preset.value === mood)
        && !Object.prototype.hasOwnProperty.call(LEGACY_ALIAS, mood)) throw new Error('invalid mood');
    row.mood = normalizeAtmosphere(mood);
  }
  if (block.type === 'thought') row.thoughtKey = row.thoughtKey.trim();
  if (block.type === 'dream') {
    if (row.title != null) { row.title = row.title.trim(); if (!row.title) delete row.title; }
    row.images = row.images.map(image => {
      const out = { url: image.url };
      if (String(image.line || '').trim()) out.line = image.line;
      return out;
    });
  }
  return row;
}

function savedContentIssue(sent, detail) {
  const topicFields = { subtitle: '副标题', description: '说明', imgUrl: '主封面', imgArr: '多图封面',
    startDate: '开始时间', endDate: '结束时间', categoryIds: '分类' };
  for (const field of Object.keys(topicFields)) {
    if (sent[field] === undefined) continue;
    const value = raw => raw == null ? '' : String(raw);
    if (value(sent[field]) !== value(detail.topic[field])) return '云端保存的' + topicFields[field] + '与本机不一致';
  }
  if (sent.collaboratorIds !== undefined) {
    const ids = list => Array.isArray(list) && list.every(id => topicIdOf(id))
      ? list.map(id => String(Number(id))).sort() : null;
    const want = ids(sent.collaboratorIds), have = ids(detail.collaboratorIds);
    if (!want || !have || stable(want) !== stable(have)) return '云端保存的协作者与本机不一致';
  }
  if (sent.tickets !== undefined) {
    if (!Array.isArray(sent.tickets) || !Array.isArray(detail.tickets)
        || sent.tickets.length !== detail.tickets.length) return '云端保存的票种数量与本机不一致';
    const tickets = detail.tickets.slice(), ids = new Set();
    for (const ticket of tickets) {
      if (!ticket || !topicIdOf(ticket.id) || ids.has(String(Number(ticket.id)))
          || String(ticket.ownerId) !== String(detail.topic.id) || Number(ticket.ownerType) !== 1)
        return '云端保存的票种编号或归属不一致';
      ids.add(String(Number(ticket.id)));
    }
    // 半填草稿允许省略字段，不能让宽匹配先占用另一张票唯一能匹配的回读行。
    const paired = new Map();
    const match = (index, seen) => {
      for (let at = 0; at < tickets.length; at++) {
        if (seen.has(at) || !ticketMatches(sent.tickets[index], tickets[at], sent.productType)) continue;
        seen.add(at);
        if (!paired.has(at) || match(paired.get(at), seen)) { paired.set(at, index); return true; }
      }
      return false;
    };
    if (!sent.tickets.every((ticket, index) => match(index, new Set()))) return '云端保存的票种内容与本机不一致';
  }
  const chapterIds = new Set(), nodeIds = new Set();
  for (let ci = 0; ci < (sent.chapters || []).length; ci++) {
    const before = sent.chapters[ci], after = detail.chapters[ci], nodes = after.cmsTopicNodeList || [];
    if (!topicIdOf(after.id) || chapterIds.has(String(Number(after.id)))
        || String(after.topicId) !== String(detail.topic.id)) return '云端保存的章节或节点编号、归属不完整';
    chapterIds.add(String(Number(after.id)));
    for (const node of nodes) {
      if (!topicIdOf(node.id) || nodeIds.has(String(Number(node.id)))
          || String(node.chapterId) !== String(after.id) || String(node.topicId) !== String(detail.topic.id))
        return '云端保存的节点编号或章节归属不一致';
      nodeIds.add(String(Number(node.id)));
    }
    if (Array.isArray(before.blocks)) {
      try {
        const blocks = after.blocks == null ? JSON.parse(after.blocksJson || 'null') : after.blocks;
        if (!Array.isArray(blocks) || blocks.length !== before.blocks.length) return '云端保存的章节剧情块与本机不一致';
        for (let bi = 0; bi < blocks.length; bi++) {
          const keepKey = !!before.blocks[bi].key;
          if (stable(storedBlock(before.blocks[bi], nodes, true, keepKey))
              !== stable(storedBlock(blocks[bi], nodes, false, keepKey))) return '云端保存的章节剧情块或节点引用与本机不一致';
        }
      } catch (error) { return '云端保存的章节剧情块无法确认'; }
    }
  }
  return '';
}

/** 回读与刚保存的内容是否对得上;对不上返回一句原因,对得上返回 ''。 */
function readbackIssue(sent, detail, expectDraft) {
  if (!detail || !detail.topic) return '没能取回云端保存的主题';
  if (expectDraft && detail.isDraft !== true) return '云端保存的主题不是草稿';
  if (expectDraft && sent && Object.prototype.hasOwnProperty.call(sent, 'configVersion')) {
    const version = topicIdOf(detail.topic.configVersion);
    const previous = sent.configVersion === '' || sent.configVersion == null ? 0 : topicIdOf(sent.configVersion);
    if (!version || version <= previous || (sent.configVersion && !previous)) return '云端保存的版本尚未确认';
  }
  if (String(detail.topic.name || '') !== String((sent && sent.name) || '')) return '云端保存的主题名与本机不一致';
  const sentChapters = (sent && sent.chapters) || [];
  const got = detail.chapters;
  if (!Array.isArray(sentChapters) || !Array.isArray(got)
      || got.some(chapter => !chapter || !Array.isArray(chapter.cmsTopicNodeList)))
    return '云端保存的章节或节点列表不完整';
  if (sentChapters.length !== got.length) return '云端保存的章节数与本机不一致';
  for (let ci = 0; ci < got.length; ci++) {
    const a = sentChapters[ci] || {}, b = got[ci] || {};
    if (String(a.name || '') !== String(b.name || '')) return '第' + (ci + 1) + '章名称或顺序与本机不一致';
    const an = a.nodes || [], bn = b.cmsTopicNodeList || [];
    if (an.length !== bn.length) return '第' + (ci + 1) + '章节点数与本机不一致';
    for (let ni = 0; ni < bn.length; ni++) {
      if (!(Number(bn[ni] && bn[ni].id) > 0)) return '云端保存的节点缺少编号';
      if (String(an[ni].name || '').trim() !== String(bn[ni].name || '').trim()) return '第' + (ci + 1) + '章节点顺序或名称与本机不一致';
      if ((templateIdOf(an[ni].templateId) || 0) !== (templateIdOf(bn[ni].templateId) || 0)) return '第' + (ci + 1) + '章「' + (bn[ni].name || '') + '」的玩法与本机不一致';
    }
  }
  const contentIssue = savedContentIssue(sent || {}, detail);
  if (contentIssue) return contentIssue;
  if (sent && Object.prototype.hasOwnProperty.call(sent, 'routeMode')) {
    if (sent.routeMode !== (detail.topic.routeMode || 'LINEAR')) return '云端保存的路线模式与本机不一致';
    if (sent.routeMode === 'BRANCH_GRAPH') {
      try {
        const want = authoredRoute(sent.routeGraphJson, positionsOf(sentChapters, 'nodes', templateBinding.nodeKey));
        const have = authoredRoute(detail.topic.routeGraphJson, positionsOf(got, 'cmsTopicNodeList', node => String(node.id)));
        if (want !== have) return '云端保存的路线条件、效果或节点配置与本机不一致';
      } catch (error) { return '云端保存的路线配置无法确认'; }
    }
  }
  const sentConfig = parseConfig(sent && sent.themeRuntimeConfig);
  const gotConfig = parseConfig(detail.themeRuntimeConfig || detail.topic.themeRuntimeConfigJson);
  if (!sentConfig || !gotConfig) return '云端保存的玩法配置无法解析';
  const want = authoredConfig(sentConfig, positionsOf(sentChapters, 'nodes', templateBinding.nodeKey));
  const have = authoredConfig(gotConfig, positionsOf(got, 'cmsTopicNodeList', node => String(Number(node && node.id) || '')));
  for (const key of Object.keys(CONFIG_LABELS)) {
    if (JSON.stringify(want[key]) !== JSON.stringify(have[key])) return '云端保存的' + CONFIG_LABELS[key] + '与本机不一致';
  }
  try {
    require('./attribute-binding.js').frozenReadback(detail.journeyRules || detail.topic.journeyRulesJson,
      detail.topic.configVersion, detail.topic.id);
  } catch (invalid) { return invalid.message; }
  if (initialOf(sent && sent.journeyRules) !== initialOf(detail.journeyRules || detail.topic.journeyRulesJson)) return '云端保存的初始状态与本机不一致';
  if (!authoredRulesMatch(sent && sent.journeyRules, detail.journeyRules || detail.topic.journeyRulesJson)) return '云端保存的旅程规则与本机不一致';
  return '';
}

function createServerDraftSaver(deps) {
  deps = deps || {};
  ['save', 'readback', 'publish'].forEach(key => {
    if (typeof deps[key] !== 'function') throw new Error('server-draft: 缺少 ' + key + ' 依赖');
  });
  let busy = false;
  let published = false;
  let destroyed = false;
  const current = () => !destroyed && (!deps.isCurrent || deps.isCurrent());
  let unconfirmedVersionInfo = null;
  let unconfirmedPublish = 0; // 上次发布结果不确定的主题号:下次发布前先查真实状态,别对已发布主题再存草稿

  function run(payload, publish, handlers) {
    handlers = handlers || {};
    const runCurrent = () => current() && (!handlers.isCurrent || handlers.isCurrent());
    function request(kind, value, callback) {
      let settled = false;
      const done = (result) => {
        if (settled || !runCurrent()) return;
        settled = true;
        callback(result);
      };
      try { deps[kind](value, done); }
      catch (error) { done({ ok: false, networkError: true }); }
    }
    // 防重:在途或已发布都不再发起(连点只会有一次请求)
    if (busy || published || !runCurrent()) return false;
    busy = true;
    if (deps.pendingPublish) unconfirmedPublish = topicIdOf(deps.pendingPublish());
    const finish = (fn, arg) => {
      busy = false;
      if (runCurrent() && typeof fn === 'function') fn(arg);
    };
    const draftPayload = Object.assign({}, payload, { saveDraft: 1 });

    const publicationMatches = (detail, expected) => {
      if (!expected) return true; // Legacy callers without a versioned author command.
      const version = expected.configVersion;
      if (version == null || !/^[1-9][0-9]*$/.test(String(version))
          || String(detail.topic.configVersion) !== String(version)) return false;
      if (expected.registryVersion != null) {
        try {
          const rules = JSON.parse(detail.journeyRules || detail.topic.journeyRulesJson || '{}');
          if (!rules.attributeBinding || rules.attributeBinding.registryVersion !== expected.registryVersion) return false;
          require('./attribute-binding.js').frozenReadback(rules, version, detail.topic.id);
        } catch (invalid) { return false; }
      }
      return true;
    };
    const expectedPublication = (savedDetail) => {
      if (savedDetail && savedDetail.topic && savedDetail.topic.configVersion != null) {
        const expected = { configVersion: savedDetail.topic.configVersion };
        try {
          const rules = JSON.parse(savedDetail.journeyRules || savedDetail.topic.journeyRulesJson || '{}');
          if (rules.attributeBinding) expected.registryVersion = rules.attributeBinding.registryVersion;
        } catch (invalid) { return {}; }
        return expected;
      }
      return typeof deps.pendingPublication === 'function' ? (deps.pendingPublication() || {}) : unconfirmedVersionInfo;
    };

    const reconcilePublish = (topicId, savedDetail, pub) => {
      // 发布结果不确定(丢回包),或重试时服务端说「不是草稿」:回读真实状态再说话
      request('readback', topicId, (back) => {
        if (!current()) return;
        if (back && back.ok && matchingTopic(back.data, topicId) && back.data.isDraft === false
            && publicationMatches(back.data, expectedPublication(savedDetail))) {
          published = true;
          finish(handlers.onSuccess, { topicId, detail: back.data, published: { recovered: true, status: (back.data.topic || {}).status }, localChanges: !!readbackIssue(payload, back.data, false) });
          return;
        }
        // 仍是草稿只说明读取时尚未发布，不能排除原请求仍在执行。
        unconfirmedPublish = topicId;
        finish(handlers.onFail, Object.assign({ stage: 'publish', topicId, detail: savedDetail }, pub || {}, { unknown: true }));
      });
    };

    const afterSaved = (topicId) => {
      request('readback', topicId, (back) => {
        if (!current()) return;
        const issue = back && back.ok ? (!matchingTopic(back.data, topicId)
          ? '云端保存的主题编号与本次不一致' : readbackIssue(payload, back.data, true)) : '';
        if (!back || !back.ok || issue) {
          finish(handlers.onFail, Object.assign({ stage: 'readback', topicId }, back || {}, issue ? { ok: false, msg: issue } : {}));
          return;
        }
        if (!publish) {
          // 保存在途时本机又改过:云端存的是上一版,不能拿回读版本覆盖编辑器,也不能报「已保存」
          if (typeof handlers.hasChanges === 'function' && handlers.hasChanges()) {
            finish(handlers.onFail, { stage: 'readback', topicId, detail: back.data, localChanges: true, unknown: false });
            return;
          }
          finish(handlers.onSuccess, { topicId, detail: back.data });
          return;
        }
        const publishNow = () => {
          // 写请求发出前落盘；杀进程或丢回包后仍先核对同一主题。
          if (deps.beforePublish && deps.beforePublish(topicId) === false) {
            finish(handlers.onFail, { stage: 'persist', topicId, msg: '本地草稿保存失败，尚未发起发布，请检查存储空间' });
            return;
          }
          unconfirmedVersionInfo = expectedPublication(back.data);
          request('publish', topicId, (pub) => {
            if (!current()) return;
            if (pub && pub.ok && confirmedPublication(pub.data, topicId)) {
              if (back.data.topic.configVersion != null) {
                const expected = expectedPublication(back.data);
                request('readback', topicId, (confirmed) => {
                  if (!current()) return;
                  if (!confirmed || !confirmed.ok || !matchingTopic(confirmed.data, topicId)
                      || confirmed.data.isDraft !== false || !publicationMatches(confirmed.data, expected)
                      || readbackIssue(payload, confirmed.data, false)) {
                    unconfirmedPublish = topicId;
                    finish(handlers.onFail, { stage: 'publish', topicId, detail: back.data, unknown: true,
                      msg: '发布已返回，原版本的发布状态尚未回读确认，请重试核对' });
                    return;
                  }
                  published = true;
                  finish(handlers.onSuccess, { topicId, detail: confirmed.data, published: Number(confirmed.data.topic.status) === 2
                    ? { recovered: true, status: 2 } : pub.data });
                });
              } else {
                published = true;
                finish(handlers.onSuccess, { topicId, detail: back.data, published: pub.data || {} });
              }
              return;
            }
            if (!pub || pub.ok || pub.networkError || /不是草稿/.test(String(pub.msg || ''))) { reconcilePublish(topicId, back.data, pub); return; }
            finish(handlers.onFail, Object.assign({ stage: 'publish', topicId, detail: back.data }, pub, { unknown: false }));
          });
        };
        // BL-185:发布前试检查(已存的这一版)。error 不发,只有 warn 由页面提示后决定;未注入就直接发布
        if (typeof deps.publishCheck !== 'function') { publishNow(); return; }
        request('publishCheck', topicId, (check) => {
          if (!current()) return;
          if (!check || check.ok !== true) {
            finish(handlers.onFail, { stage: 'publish', topicId, detail: back.data,
              msg: check && check.msg || '发布检查未完成，请重试', unknown: false });
            return;
          }
          publishNow();
        });
      });
    };

    const start = () => request('save', draftPayload, (res) => {
      if (!current()) return;
      const topicId = res && res.ok ? topicIdOf(res.data) : 0;
      if (!topicId) {
        finish(handlers.onFail, Object.assign({ stage: 'save' }, res || {},
          res && res.ok ? { msg: '保存结果缺少主题编号' } : {}, { unknown: !res || !!(res.ok || res.networkError) }));
        return;
      }
      if (typeof handlers.onSaved === 'function') handlers.onSaved(topicId);
      // recovered 也先核对冻结输入；后续新编辑由页面在正向回读之后另行保存。
      afterSaved(topicId);
    });
    if (handlers.recoverSave && topicIdOf(handlers.recoveryTopicId)) {
      afterSaved(topicIdOf(handlers.recoveryTopicId));
    } else if (unconfirmedPublish) {
      const topicId = unconfirmedPublish;
      request('readback', topicId, (back) => {
        if (!current()) return;
        if (back && back.ok && matchingTopic(back.data, topicId) && back.data.isDraft === false
            && publicationMatches(back.data, expectedPublication(null))) {
          published = true; unconfirmedPublish = 0;
          finish(handlers.onSuccess, { topicId, detail: back.data, published: { recovered: true, status: (back.data.topic || {}).status }, localChanges: !!readbackIssue(payload, back.data, false) });
          return;
        }
        // 没有原请求的确定失败回执时保留 UNKNOWN；重试只读，不能重放写入。
        finish(handlers.onFail, Object.assign({ stage: 'publish', topicId }, back || {}, { unknown: true }));
      });
    } else start();
    return true;
  }

  return {
    save: (payload, handlers) => run(payload, false, handlers),
    saveAndPublish: (payload, handlers) => run(payload, true, handlers),
    isBusy: () => busy,
    destroy: () => { destroyed = true; },
  };
}

module.exports = { createServerDraftSaver, readbackIssue, topicIdOf, confirmedPublication };
