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
