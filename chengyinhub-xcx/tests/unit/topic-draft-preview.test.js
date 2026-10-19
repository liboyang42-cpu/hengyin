const test = require('node:test');
const assert = require('node:assert/strict');
const { buildTopicPreview } = require('../../pages/publish/utils/publish/topic-draft-preview.js');

const form = () => ({ name: '正在编辑的标题', productType: 1, chapters: [{
  _localId: 'c1', name: '出生', atmospherePreset: 'gold', nodes: [{
    _localId: 'n1', name: '医院建档', _storyGame: true, templateId: 7,
    templateInfo: { validationMethod: 0, title: '现在的建档', advancedConfigJson: JSON.stringify({
      present: 'inline', profile: { enabled: true, title: '填写你的名字', questions: [{ key: 'name', label: '姓名', kind: 'text' }] }
    }) }
  }], blocks: [{ key: 'a', type: 'text', content: '现在的正文' },
    { key: 'b', type: 'node', nodeKey: 'n1', locationRequired: false },
    { key: 'c', type: 'text', content: '你好，{name|朋友}' }]
}] });

test('整主题预览使用当前草稿配置，保留故事块顺序，主题正式ID不进入预览', () => {
  const input = form();
  const saved = JSON.stringify(input);
  const result = buildTopicPreview(input);
  assert.equal(result.topicName, input.name);
  assert.equal(result.topicId, '');
  assert.equal(result.chapters[0].blocks[0].content, '现在的正文');
  assert.equal(result.chapters[0].blocks[1].nodeId, result.nodes[0].nodeId);
  assert.equal(result.chapters[0].blocks[1].locationRequired, false);
  assert.equal(result.nodes[0].previewKit.title, '填写你的名字');
  assert.equal(result.nodes[0].previewKit.present, 'inline');
  assert.equal(JSON.stringify(input), saved);
});

test('缺失模板配置和损坏JSON不允许静默进入空白试玩', () => {
  const input = form();
  input.chapters[0].nodes[0].templateInfo = { title: '只有摘要' };
  assert.throws(() => buildTopicPreview(input), /重新选择.*模板/);
  input.chapters[0].nodes[0].templateInfo.advancedConfigJson = '{bad';
  assert.throws(() => buildTopicPreview(input), /配置无法读取/);
});


test('纯文字第二章保留正确序号，不会随构造单章误标第一章', () => {
  const input = form();
  input.chapters.push({ _localId: 'c2', name: '入学', nodes: [], blocks: [{ key: 'c2-a', type: 'text', content: '第二章正文' }] });
  const result = buildTopicPreview(input);
  assert.equal(result.chapters[1].idxLabel, '第 2 章');
  assert.equal(result.chapters[1].blocks[0].content, '第二章正文');
});


test('开局建档排在第一章之前，编号与当前配置一起交给真实玩家页', () => {
  const input = form();
  input.chapters[0].opening = true;
  input.chapters.push({ _localId: 'c2', name: '出发', nodes: [], blocks: [{ key: 'first', type: 'text', content: '你好' }] });
  const result = buildTopicPreview(input);
  assert.equal(result.chapters[0].opening, true);
  assert.equal(result.chapters[0].idxLabel, '序章');
  assert.equal(result.chapters[1].idxLabel, '第 1 章');
  assert.equal(result.nodes[0].chapterId, result.chapters[0].id);
  input.chapters[0].nodes[0]._storyGame = false;
  delete input.chapters[0].blocks[1].locationRequired;
  assert.throws(() => buildTopicPreview(input), /开局请添加故事玩法/);
});

// 2026-09-22 走查:作者在第一章埋了伏笔,试玩时整块被跳过 —— 预览根本没带念头的名字与说明。
test('整主题预览带上已保存的伏笔(名字 / 说明 / 步数),坏 JSON 当没配', () => {
  const input = form()
  input.journeyRules = JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'tmud', name: '我很幸运', desc: '所有人都这么说', need: 3000 }] })
  assert.deepEqual(buildTopicPreview(input).thoughts, [{ key: 'tmud', name: '我很幸运', desc: '所有人都这么说', need: 3000 }])
  input.journeyRules = '{坏'
  assert.deepEqual(buildTopicPreview(input).thoughts, [])
})

// W-15(2026-09-22 走查):试玩走到最后只剩「返回编辑」,作者配的结局在试玩里看不到。
test('整主题预览带上结局清单:标题 / 一句话 / 触发条件说成人话,兜底那条也在', () => {
  const input = form()
  input.chapters[0].nodes[0].id = 88
  input.journeyRules = JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'w', name: '那扇窗', need: 10 }] })
  input.journeyStory = JSON.stringify({ schemaVersion: 1, ending: { endings: [
    { code: 'awake', title: '醒来', summary: '你推开了窗', when: [{ op: 'HAS_TAG', value: 'thought.w.done' }] },
    { code: 'hall', title: '留在展示馆', when: [{ op: 'NODE_COMPLETED', nodeId: 88 }] },
    { code: 'default', title: '预制的一生', fallback: true },
  ] } })
  assert.deepEqual(buildTopicPreview(input).endings.map(e => [e.title, e.cond]), [
    ['醒来', '想通「那扇窗」'], ['留在展示馆', '到过「医院建档」'], ['预制的一生', '其它结局都不中时'],
  ])
  input.journeyStory = ''
  assert.deepEqual(buildTopicPreview(input).endings, [])
})

test('结局可按「选过某个选项」触发:存成 HAS_TAG tag.*,读回来还原', () => {
  const storyEnding = require('../../pages/publish/utils/publish/story-ending.js')
  const raw = storyEnding.serialize({ root: {}, list: [
    { code: 'accountant', title: '标准版本', kind: 'tag', tag: 'tag.job_accountant' },
    { code: 'default', title: '余波', fallback: true, kind: 'fallback' },
  ] })
  assert.deepEqual(JSON.parse(raw).ending.endings[0].when, [{ op: 'HAS_TAG', value: 'tag.job_accountant' }])
  assert.equal(storyEnding.parse(raw).list[0].kind, 'tag')
  assert.equal(storyEnding.parse(raw).list[0].tag, 'tag.job_accountant')
  assert.throws(() => storyEnding.serialize({ root: {}, list: [
    { code: 'x', title: '没选', kind: 'tag', tag: '' }, { code: 'default', title: '余波', fallback: true, kind: 'fallback' }] }), /要选一个选项/)
})

// 2026-09-24 用户:「结局应该是添加章节,可以设定这个章节为结局」—— 结局章
test('结局章不进试玩的章节顺序;试玩结局按结局章生成,每条带着那一章', () => {
  const input = form()
  input.journeyRules = JSON.stringify({ schemaVersion: 1, thoughts: [{ key: 'w', name: '那扇窗', need: 10 }] })
  input.chapters.push({ _localId: 'e1', name: '窗外', nodes: [], blocks: [{ key: 'x', type: 'text', content: '你推开了窗。' }],
    ending: { fallback: false, kind: 'thought', thoughtKey: 'w' } })
  input.chapters.push({ _localId: 'e2', name: '余波', nodes: [], blocks: [{ key: 'y', type: 'text', content: '你回到了人群里。' }],
    ending: { fallback: true, kind: 'fallback' } })
  input.journeyStory = JSON.stringify({ schemaVersion: 1, ending: { endings: [{ code: 'old', title: '老结局', fallback: true }] } })
  const result = buildTopicPreview(input)
  assert.deepEqual(result.chapters.map(c => c.name), ['出生'], '结局章不当普通章')
  assert.deepEqual(result.endings.map(e => [e.title, e.cond, e.fallback]), [['窗外', '想通「那扇窗」', false], ['余波', '其它结局都不中时', true]],
    '有结局章就按结局章,journeyStory 里的老清单不再用')
  assert.equal(result.endings[0].chapter.blocks[0].content, '你推开了窗。')
  assert.equal(result.endings[1].chapter.idxLabel, '结局')
})


const { buildThemeOverview } = require('../../pages/publish/utils/publish/topic-draft-preview.js');
const overviewForm = config => Object.assign(form(), { themeRuntimeConfig: JSON.stringify(Object.assign({ schemaVersion: 1 }, config)) });
const overviewText = result => JSON.stringify(result);

test('CONFIG overview projects only names, declared rules/counts and text without source identifiers or media', () => {
  const input = overviewForm({ capacity: 6, allowDiscard: true, hpName: '精力', luckName: '好运', actionLimit: 25,
    actionSources: ['CHOICE'], rescueLimit: 2, replayEnabled: true,
    items: [{ id: 'private_item', name: '路标', image: 'https://secret.invalid/image', kind: 'ITEM', stat: 'HP', delta: 2, wear: true, mode: 'HELD' }],
    chapters: [{ index: 0, hpCost: 2, luckCost: 1, grants: [{ itemId: 'private_item', quantity: 3, timing: 'START' }], eggs: [{ id: 'private_egg', content: 'secret egg body' }] }],
    events: [{ id: 'private_event', content: '这是作者配的广播', when: [{ nodeId: 987654321, op: 'NODE_COMPLETED' }], grants: [], effects: [{ var: 'clue.private', value: 1 }] }],
    unknown: { answer: 'secret answer', actor: 'private_actor' }, card: { cover: 'https://secret.invalid/card' } });
  const saved = JSON.stringify(input), value = buildThemeOverview(input), text = overviewText(value);
  assert.equal(value.kind, 'CONFIG'); assert.equal(value.version, 1);
  assert.match(text, /路标/); assert.match(text, /出生/); assert.match(text, /开始物资 1 种/); assert.match(text, /这是作者配的广播/);
  for (const forbidden of ['private_item', 'private_egg', 'private_event', '987654321', 'clue.private', 'secret', 'private_actor', 'https:']) assert.equal(text.includes(forbidden), false, forbidden);
  value.rows.forEach(row => assert.deepEqual(Object.keys(row).sort(), ['detail', 'label', 'section']));
  assert.equal(JSON.stringify(input), saved);
});
test('CONFIG overview preserves missing, explicit null and empty action source meanings without enabling budgets', () => {
  for (const [config, expected] of [[{}, '未显式设置'], [{ actionSources: null }, '显式空值'], [{ actionSources: [] }, '显式空列表']]) {
    const result = buildThemeOverview(overviewForm(config));
    assert.match(result.rows.find(r => r.label === '行动计数来源配置').detail, new RegExp(expected));
    assert.equal(result.rows.find(r => r.label === '行动上限配置').detail, '未设置上限');
    assert.equal(result.rows.find(r => r.label === '救援次数上限配置').detail, '未设置（现有默认为 0）');
  }
  const result = buildThemeOverview(overviewForm({ actionSources: ['NODE', 'NODE', 'DRAW'] }));
  assert.equal(result.rows.find(r => r.label === '行动计数来源配置').detail, '完成节点、完成节点、抽取');
});
test('CONFIG overview distinguishes scene inheritance, explicit null/empty overrides and configured start without selecting a run', () => {
  const value = buildThemeOverview(overviewForm({ rotation: 'WEEKLY', rotationAnchor: 123, currentScene: 'private_scene_2',
    scenes: [{ id: 'private_scene_1', name: '春' }, { id: 'private_scene_2', name: '秋', grants: [], events: null, endings: [], journeyStory: {}, nodeConfigs: {} }] }));
  const rows = value.rows.filter(r => r.section === '场景');
  assert.match(rows[0].detail, /UTC 周一 \/ 北京时间周一 08:00/);
  assert.match(rows[1].detail, /物资继承根配置.*广播继承根配置/);
  assert.equal(rows[2].label, '秋（起始配置）'); assert.match(rows[2].detail, /物资覆盖 0 条.*广播显式空值覆盖/);
  assert.equal(overviewText(value).includes('private_scene'), false); assert.equal(overviewText(value).includes('123'), false);
});
test('CONFIG overview bounds all declared catalog/chapter/event/scene lists and clips text without splitting emoji', () => {
  const input = overviewForm({
    items: Array.from({ length: 200 }, (_, i) => ({ id: 'i' + i, name: '😀'.repeat(30), kind: 'MEDAL' })),
    chapters: Array.from({ length: 200 }, (_, index) => ({ index, hpCost: 0, luckCost: 0 })),
    events: Array.from({ length: 100 }, () => ({ content: '😀'.repeat(250) })),
    scenes: Array.from({ length: 50 }, () => ({ name: '景'.repeat(60) }))
  });
  const value = buildThemeOverview(input);
  assert.ok(value.rows.length > 550 && value.rows.length <= 600); assert.equal(value.clipped, true);
  for (const row of value.rows) { assert.ok(row.label.length <= 80); assert.ok(row.detail.length <= 240); assert.equal(/[\uD800-\uDBFF]…/.test(row.detail + row.label), false); }
  assert.ok(JSON.stringify(value).length < 200000);
});
test('CONFIG overview fails closed for oversized lists, malformed roots/depth and excessive source, without blocking story preview', () => {
  const cases = ['{broken', '[]', '{"schemaVersion":2}', 'x'.repeat(200001), JSON.stringify({ schemaVersion: 1, events: Array(101).fill({}) }),
    JSON.stringify({ schemaVersion: 1, unknown: JSON.parse('['.repeat(34) + '0' + ']'.repeat(34)) })];
  for (const raw of cases) {
    const input = form(); input.themeRuntimeConfig = raw;
    assert.equal(buildThemeOverview(input).rows[0].label, '配置清单暂不可读');
    const result = buildTopicPreview(input); assert.equal(result.chapters[0].blocks[0].content, '现在的正文');
  }
});
test('CONFIG overview handles missing/cleared input and prototype enum names without coercing unknown values', () => {
  for (const raw of [undefined, null, '']) assert.equal(buildThemeOverview({ themeRuntimeConfig: raw }), null);
  const value = buildThemeOverview(overviewForm({ actionSources: ['__proto__', 'toString'], rotation: 'constructor', items: [{ name: '物品', kind: 'ITEM', stat: 'HP', delta: 1, wear: true, mode: 'toString' }] }));
  assert.equal(overviewText(value).includes('function'), false); assert.equal(overviewText(value).includes('[object'), false);
});

function authorPreviewFixture() {
  const fs = require('node:fs'), path = require('node:path');
  const source = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
  const method = source.slice(source.indexOf('  previewDraft() {'), source.indexOf('  // 2026-09-05 用户裁决:删掉 previewTopic()'));
  let owner = 7; const navigation = [], tips = [], emitted = [];
  const app = { getUserID: () => owner, tips: value => tips.push(value) };
  const wx = { navigateTo: options => navigation.push(options) };
  const page = Object.assign({ data: { formData: overviewForm({ capacity: 3 }), draftMemberId: 7, draftUuid: 'private_draft', baseRevision: 'revision_a' },
    allowed: true, _runtimeMayEdit() { return this.allowed; } }, new Function('app', 'wx', 'buildTopicPreview', 'return ({' + method + '});')(app, wx, buildTopicPreview));
  const success = index => navigation[index].success({ eventChannel: { emit: (event, value) => emitted.push({ event, value }) } });
  return { page, navigation, tips, emitted, success, owner: value => { owner = value; } };
}
test('actual author handler emits only a private owner/guard plus projected CONFIG and preserves ordinary story data', () => {
  const f = authorPreviewFixture(); f.page.previewDraft(); f.success(0);
  const data = f.emitted[0].value, envelope = data.themeOverview;
  assert.equal(f.navigation[0].url, '/pages/play/index?preview=1'); assert.equal(f.emitted[0].event, 'previewData');
  assert.deepEqual(Object.keys(envelope).sort(), ['isCurrent', 'owner', 'value']); assert.equal(envelope.owner, '7'); assert.equal(envelope.isCurrent(), true);
  assert.equal(JSON.stringify(envelope).includes('private_draft'), false); assert.equal(data.topicId, ''); assert.equal(data.chapters[0].blocks[0].content, '现在的正文');
});
test('actual author handler suppresses stale navigation after identity, source, topic, draft revision or unload changes', () => {
  const changes = [f => f.owner(8), f => { f.page.data.formData.name = 'changed'; }, f => { f.page.data.editingTopicId = 55; },
    f => { f.page.data.draftUuid = 'next'; }, f => { f.page.data.baseRevision = 'new'; }, f => { f.page.data.operationScope = 'MERCHANT'; }, f => { f.page._runtimeDisposed = true; }];
  for (const change of changes) { const f = authorPreviewFixture(); f.page.previewDraft(); change(f); f.success(0); f.navigation[0].fail(); assert.equal(f.emitted.length, 0); assert.equal(f.tips.length, 0); }
});
test('actual author handler revalidates its emitted source guard after draft edits and permission revocation', () => {
  for (const change of [f => { f.page.data.formData.themeRuntimeConfig = '{}'; }, f => { f.page.allowed = false; }, f => f.owner(8)]) {
    const f = authorPreviewFixture(); f.page.previewDraft(); f.success(0); const guard = f.emitted[0].value.themeOverview.isCurrent;
    change(f); assert.equal(guard(), false);
  }
});
test('actual author handler allows normal preview but omits CONFIG for unverified/wrong author or disabled capability', () => {
  for (const change of [f => f.owner(0), f => { f.page.data.draftMemberId = 8; }, f => { f.page.allowed = false; }, f => { f.page.data.formData.description = 'x'.repeat(1000001); }]) {
    const f = authorPreviewFixture(); change(f); f.page.previewDraft(); f.success(0);
    assert.equal(f.emitted.length, 1); assert.equal(f.emitted[0].value.themeOverview, undefined); assert.equal(f.emitted[0].value.chapters.length, 1);
  }
});
test('actual author handler invalidates older navigation and older delivered guards on repeated preview', () => {
  const f = authorPreviewFixture(); f.page.previewDraft(); f.success(0); const old = f.emitted[0].value.themeOverview;
  f.page.previewDraft(); assert.equal(old.isCurrent(), false); f.success(0); assert.equal(f.emitted.length, 1); f.success(1); assert.equal(f.emitted.length, 2);
});
