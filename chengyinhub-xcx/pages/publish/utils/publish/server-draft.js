'use strict';

const { normalizeAtmosphere } = require('../../../../utils/chapter-atmosphere.js');
const clone = value => JSON.parse(JSON.stringify(value));
const numberId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
const parse = raw => raw ? (typeof raw === 'string' ? JSON.parse(raw) : clone(raw)) : null;
const stable = value => JSON.stringify(value, function (key, row) {
  return row && typeof row === 'object' && !Array.isArray(row)
    ? Object.keys(row).sort().reduce((out, field) => { out[field] = row[field]; return out; }, {}) : row;
});
function contains(expected, actual) {
  if (Array.isArray(expected)) return Array.isArray(actual) && expected.length === actual.length && expected.every((row, i) => contains(row, actual[i]));
  if (expected && typeof expected === 'object') return actual && typeof actual === 'object' && Object.keys(expected).every(key => contains(expected[key], actual[key]));
  return expected === actual;
}
function ticketMatches(before, after, productType) {
  const numbers = ['price', 'totalStock', 'teamSize', 'gatherLng', 'gatherLat', 'needAudit'];
  const fields = ['name', 'description', 'startTime', 'endTime', 'saleStartTime', 'saleEndTime', 'meetingPoint', 'meetingPointAddress', 'refundRule', 'refundSupported', 'syncWithTheme'].concat(numbers);
  if (Number(after.mode) !== Number(productType)) return false;
  for (const field of fields) {
    if (before[field] === undefined) continue;
    const value = field === 'totalStock' ? after.totalInventory : after[field];
    if (numbers.includes(field)) {
      const number = row => row == null || row === '' ? null : Number(row);
      if (number(before[field]) !== number(value)) return false;
    } else if ((before[field] == null ? '' : before[field]) !== (value == null ? '' : value)) return false;
  }
  return true;
}
function blocksOf(chapter, nodeRows, submitted) {
  const blocks = submitted ? chapter.blocks : (chapter.blocks || parse(chapter.blocksJson));
  return (blocks || []).map(row => {
    const block = clone(row);
    if (submitted && block.nodeIndex != null) { block.nodeId = nodeRows[block.nodeIndex] && nodeRows[block.nodeIndex].id; delete block.nodeIndex; }
    if (['voice', 'reveal'].includes(block.type)) block.content = String(block.content || '').trim();
    if (block.who != null && ['text', 'reveal'].includes(block.type)) { block.who = block.who.trim(); if (!block.who) delete block.who; }
    if (block.type === 'mood') block.mood = normalizeAtmosphere(block.mood);
    if (block.type === 'thought') block.thoughtKey = block.thoughtKey.trim();
    if (block.type === 'dream') {
      if (block.title != null) { block.title = block.title.trim(); if (!block.title) delete block.title; }
      block.images.forEach(row => { if (!String(row.line || '').trim()) delete row.line; });
    }
    return block;
  });
}
function remap(value, nodes, chapters) {
  if (Array.isArray(value)) return value.map(row => remap(row, nodes, chapters));
  if (!value || typeof value !== 'object') return value;
  return Object.keys(value).reduce((out, key) => {
    const raw = value[key];
    if (key === 'configSource') out[key] = clone(raw);
    else if (['nodeId', 'dispositionNodeId'].includes(key)) out[key] = nodes[String(raw)] || raw;
    else if (key === 'chapterId') out[key] = chapters[String(raw)] || raw;
    else if (key === 'var' && /^sys\.(passed|outcome|mistakes|damage)\.\d+$/.test(raw)) out[key] = raw.replace(/\d+$/, id => nodes[id] || id);
    else if (key === 'nodeConfigs') out[key] = Object.keys(raw).reduce((result, id) => { result[nodes[id] || id] = remap(raw[id], nodes, chapters); return result; }, {});
    else out[key] = remap(raw, nodes, chapters);
    return out;
  }, {});
}
function readbackIssue(payload, detail, topicId) {
  if (!detail || !detail.topic || String(detail.topic.id) !== String(topicId) || detail.isDraft !== true
      || !numberId(detail.topic.configVersion) || !Array.isArray(detail.chapters) || !Array.isArray(detail.tickets)
      || !Array.isArray(detail.collaboratorIds)) return '草稿身份或版本回读不完整';
  const topic = detail.topic;
  for (const field of ['name', 'subtitle', 'description', 'imgUrl', 'imgArr', 'categoryIds', 'productType'])
    if (payload[field] != null && String(payload[field]) !== String(topic[field] == null ? '' : topic[field])) return '主题字段回读不一致：' + field;
  if (payload.configVersion && Number(topic.configVersion) !== Number(payload.configVersion) + 1) return '草稿版本回读不一致';
  if (stable((payload.collaboratorIds || []).map(String).sort()) !== stable(detail.collaboratorIds.map(String).sort())) return '协作者回读不一致';
  const tickets = detail.tickets.slice(), ticketIds = new Set();
  if ((payload.tickets || []).length !== tickets.length) return '票种数量回读不一致';
  for (const ticket of tickets) {
    if (!numberId(ticket.id) || ticketIds.has(String(ticket.id)) || String(ticket.ownerId) !== String(topicId)
        || Number(ticket.ownerType) !== 1) return '票种编号或归属回读不一致';
    ticketIds.add(String(ticket.id));
  }
  for (const ticket of payload.tickets || []) {
    const index = tickets.findIndex(row => ticketMatches(ticket, row, payload.productType));
    if (index < 0) return '票种内容回读不一致';
    tickets.splice(index, 1);
  }
  const expected = payload.chapters || [], actual = detail.chapters;
  if (expected.length !== actual.length) return '章节数量回读不一致';
  const nodes = {}, chapters = {}, allIds = new Set();
  const actualRuntime = parse(detail.themeRuntimeConfig || topic.themeRuntimeConfigJson);
  const copies = actualRuntime && actualRuntime.configSource && actualRuntime.configSource.templateCopies || {};
  for (let i = 0; i < expected.length; i++) {
    const before = expected[i], after = actual[i], beforeNodes = before.nodes || [], afterNodes = after.cmsTopicNodeList || [];
    if (!numberId(after.id) || allIds.has('c' + after.id) || beforeNodes.length !== afterNodes.length) return '章节或站点编号回读不完整';
    allIds.add('c' + after.id);
    if (before.id) chapters[String(before.id)] = after.id;
    for (const key of ['name', 'description', 'atmospherePreset', 'audioUrl', 'audioDuration', 'allowedValidationMethods', 'maxNodeXp', 'recruitEnabled', 'termsMode', 'maxMerchant'])
      if (before[key] != null && String(before[key]) !== String(after[key] == null ? '' : after[key])) return '章节顺序或配置回读不一致：' + key;
    for (let j = 0; j < beforeNodes.length; j++) {
      const left = beforeNodes[j], right = afterNodes[j];
      if (!numberId(right.id) || allIds.has('n' + right.id) || String(right.chapterId) !== String(after.id)) return '站点归属回读不完整';
      allIds.add('n' + right.id);
      if (left.id) nodes[String(left.id)] = right.id;
      if (left.clientNodeKey) nodes[left.clientNodeKey] = right.id;
      for (const key of ['name', 'address', 'longitude', 'latitude', 'nodeTime', 'imgUrl', 'description']) {
        if (left[key] != null && String(left[key]) !== String(right[key] == null ? '' : right[key])) return '站点顺序或配置回读不一致：' + key;
      }
      const template = left.sourceLibraryTemplateId ? copies[String(left.sourceLibraryTemplateId)] : left.templateId;
      if (left.sourceLibraryTemplateId && !numberId(template)) return '玩法副本来源未回读';
      if (String(template || 0) !== String(right.templateId || 0)) return '站点玩法绑定回读不一致';
    }
    if (Array.isArray(before.blocks) && !contains(blocksOf(before, afterNodes, true), blocksOf(after, afterNodes, false))) return '章节剧情块或节点引用回读不一致';
  }
  const runtime = remap(parse(payload.themeRuntimeConfig), nodes, chapters);
  if (runtime) {
    if (!actualRuntime) return '配置来源或运行规则未回读';
    if (runtime.capacity == null) runtime.capacity = 0;
    if (runtime.configSource && !runtime.configSource.templateCopies && Object.keys(copies).length) runtime.configSource.templateCopies = copies;
  }
  if (stable(runtime) !== stable(actualRuntime)) return '配置来源或运行规则回读不一致';
  let story = remap(parse(payload.journeyStory), nodes, chapters);
  const opening = expected.findIndex(chapter => chapter.opening === true);
  const endingChapters = expected.map((chapter, index) => ({ chapter, index })).filter(row => row.chapter.ending);
  if (opening >= 0 || endingChapters.length) story = story || { schemaVersion: 1 };
  if (story) {
    delete story.openingChapterId;
    if (opening >= 0) story.openingChapterId = actual[opening].id;
    if (endingChapters.length) {
      story.ending = story.ending || {};
      story.ending.endings = endingChapters.map((row, index) => {
        const chapter = row.chapter, summary = String(chapter.description || '').trim().slice(0, 80);
        const ending = { code: 'ending' + (index + 1), title: String(chapter.name || '').trim() || '结局 ' + (index + 1), chapterId: actual[row.index].id };
        if (summary) ending.summary = summary;
        if (chapter.ending.fallback) ending.fallback = true;
        else ending.when = remap(chapter.ending.when, nodes, chapters);
        return ending;
      });
    }
  }
  const readStory = parse(detail.journeyStory || topic.journeyStoryJson);
  const readOpening = detail.openingChapterId == null ? readStory && readStory.openingChapterId : detail.openingChapterId;
  if (String(readOpening || '') !== String(opening >= 0 ? actual[opening].id : '')) return '开局章节回读不一致';
  if ((readStory && readStory.ending && readStory.ending.endings || []).length !== endingChapters.length) return '结局章节回读不一致';
  if (story && !contains(story, readStory)) return '剧情配置或结局条件回读不一致';
  if (payload.journeyRules && !contains(parse(payload.journeyRules), parse(detail.journeyRules || topic.journeyRulesJson))) return '主题初始状态回读不一致';
  return '';
}

function createServerDraftWorkflow(request, initialId) {
  let sequence = 0;
  let state = 'idle', destroyed = false, topicId = initialId || '', savedPayload;
  function read(handlers) {
    state = 'reading';
    const epoch = ++sequence;
    request('/api/topic/edit-detail', { id: topicId, scope: savedPayload.scope }, false, res => {
      if (destroyed || epoch !== sequence || state !== 'reading') return;
      let issue;
      try { issue = res && res.code == 200 ? readbackIssue(savedPayload, res.data, topicId) : res && res.msg || '草稿回读失败'; }
      catch (error) { issue = '草稿回读配置无法读取'; }
      state = issue ? 'readbackFailed' : 'idle';
      if (issue) handlers.onFail(issue);
      else handlers.onSuccess(res.data);
    });
  }
  return {
    save(payload, handlers) {
      if (state !== 'idle') return false;
      savedPayload = clone(Object.assign({}, payload, { saveDraft: 1 }));
      state = 'saving';
      const epoch = ++sequence;
      if (handlers.beforeSave && handlers.beforeSave() === false) { state = 'idle'; return false; }
      const selected = parse(savedPayload.themeRuntimeConfig);
      const professionalStory = savedPayload.publishMode === 'pro' && ((selected && selected.configSource && selected.configSource.route) || Number(savedPayload.productType) === 1
        || (savedPayload.chapters || []).some(ch => ch.opening || ch.ending || (ch.blocks || []).length));
      const url = professionalStory
        ? (topicId ? '/api/topic/v2/update' : '/api/topic/v2/create')
        : (topicId ? '/api/topic/update' : '/api/topic/create');
      request(url, Object.assign({}, savedPayload, topicId ? { id: topicId } : {}), true, res => {
        if (destroyed || epoch !== sequence || state !== 'saving') return;
        if (!res || res.code != 200) {
          // 创建入口没有持久请求回执，通用错误也不能证明没有提交。
          state = topicId ? 'readbackFailed' : 'unknownCreate';
          handlers.onFail(state === 'unknownCreate' ? '保存结果未知，请从我的项目核对原草稿，勿重复新建' : state === 'readbackFailed' ? '保存结果未知，请重新核对原草稿；当前内容已保留' : res && res.msg || '草稿保存失败，内容已保留');
          return;
        }
        const returnedId = res.data && typeof res.data === 'object' ? res.data.topicId : res.data;
        if (!numberId(returnedId) || (topicId && String(returnedId) !== String(topicId))) {
          state = topicId ? 'readbackFailed' : 'unknownCreate';
          handlers.onFail('保存结果与当前草稿不一致，内容已保留');
          return;
        }
        topicId = returnedId;
        handlers.receipt(topicId);
        read(handlers);
      });
      return true;
    },
    retryReadback(handlers) { if (state !== 'readbackFailed' || !savedPayload) return false; read(handlers); return true; },
    getState() { return state; },
    destroy() { destroyed = true; },
  };
}
module.exports = { createServerDraftWorkflow, readbackIssue };
