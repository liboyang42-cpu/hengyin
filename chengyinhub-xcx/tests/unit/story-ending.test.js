const test = require('node:test');
const assert = require('node:assert/strict');
const ending = require('../../pages/publish/utils/publish/story-ending');

/* 结局住在 journeyStory.ending.endings(主题级一份)。作者界面只提供两种能在手机上说清楚的
   触发条件 —— 「想通了某个念头」与「到过某个站点」;服务端认的完整条件语法比这宽,
   所以本层的第一条硬约束是:看不懂的东西必须原样带回去,不能因为 UI 表达不了就抹掉。 */

test('空配置解析出一条兜底结局,作者一打开就有能填的东西', () => {
  const draft = ending.parse('');
  assert.equal(draft.list.length, 1);
  assert.equal(draft.list[0].fallback, true);
  assert.equal(draft.list[0].title, '');
});

test('念头结局往返:UI 的扁平形状 ↔ 服务端的 HAS_TAG 条件', () => {
  const json = ending.serialize({
    list: [
      { code: 'awake', title: '想通了', summary: '那个下午终于讲清楚了', kind: 'thought', thoughtKey: 'afternoon' },
      { code: 'default', title: '余波', fallback: true },
    ],
  });
  const parsed = JSON.parse(json);
  assert.deepEqual(parsed.ending.endings[0].when, [{ op: 'HAS_TAG', value: 'thought.afternoon.done' }]);
  assert.equal(parsed.ending.endings[1].fallback, true);
  assert.equal(parsed.ending.endings[1].when, undefined, '兜底不带条件');

  const back = ending.parse(json);
  assert.equal(back.list[0].kind, 'thought');
  assert.equal(back.list[0].thoughtKey, 'afternoon');
  assert.equal(back.list[0].summary, '那个下午终于讲清楚了');
});

test('站点结局往返:NODE_COMPLETED', () => {
  const json = ending.serialize({
    list: [
      { code: 'arrived', title: '到过展示馆', kind: 'node', nodeId: 501 },
      { code: 'default', title: '余波', fallback: true },
    ],
  });
  assert.deepEqual(JSON.parse(json).ending.endings[0].when, [{ op: 'NODE_COMPLETED', nodeId: 501 }]);
  const back = ending.parse(json);
  assert.equal(back.list[0].kind, 'node');
  assert.equal(back.list[0].nodeId, 501);
});

test('journeyStory 里的其它配置必须原样保留 —— 保存结局不是重写整份剧情', () => {
  const before = JSON.stringify({
    schemaVersion: 1,
    openingChapterId: 11,
    call: { npcId: 7, opener: '喂?' },
    stages: [{ chapterId: 11, end: { open: '本章完' } }],
    ending: {
      dispositionNodeId: 99,
      endings: [{ code: 'old', title: '旧的', fallback: true }],
      epilogues: [{ npcId: 7, relationKey: 'relation.arong', ally: '友', neutral: '中', guarded: '敌' }],
      exhibits: [{ text: '展品' }],
      badges: [{ title: '徽章' }],
      optionalClues: ['clue.voice'],
    },
  });
  const draft = ending.parse(before);
  draft.list[0].title = '改过的';
  const after = JSON.parse(ending.serialize(draft));

  assert.equal(after.openingChapterId, 11);
  assert.deepEqual(after.call, { npcId: 7, opener: '喂?' });
  assert.equal(after.stages.length, 1);
  assert.equal(after.ending.dispositionNodeId, 99);
  assert.equal(after.ending.epilogues.length, 1);
  assert.deepEqual(after.ending.optionalClues, ['clue.voice']);
  assert.equal(after.ending.endings[0].title, '改过的');
});

test('UI 表达不了的条件原样带回,不因为看不懂就抹掉', () => {
  const raw = [{ op: 'GTE', var: 'clue.x', value: 1 }, { op: 'HAS_TAG', value: 'tag.brave' }];
  const before = JSON.stringify({
    schemaVersion: 1,
    ending: { endings: [{ code: 'adv', title: '高级', when: raw }, { code: 'd', title: '兜底', fallback: true }] },
  });
  const draft = ending.parse(before);
  assert.equal(draft.list[0].kind, 'raw', '标成 raw,界面上只读');
  const after = JSON.parse(ending.serialize(draft));
  assert.deepEqual(after.ending.endings[0].when, raw);
});

test('恰好一个兜底 —— 服务端这条硬规则要在作者点「完成」之前就说清楚', () => {
  assert.throws(() => ending.serialize({ list: [{ code: 'a', title: 'A', kind: 'thought', thoughtKey: 'x' }] }),
    /兜底/, '一个都没有');
  assert.throws(() => ending.serialize({
    list: [{ code: 'a', title: 'A', fallback: true }, { code: 'b', title: 'B', fallback: true }],
  }), /兜底/, '两个');
});

test('标题必填、code 不重复、条件不能留空', () => {
  const ok = { code: 'd', title: '兜底', fallback: true };
  assert.throws(() => ending.serialize({ list: [{ code: 'a', title: '', kind: 'thought', thoughtKey: 'x' }, ok] }),
    /标题/);
  assert.throws(() => ending.serialize({
    list: [{ code: 'a', title: 'A', kind: 'thought', thoughtKey: 'x' },
           { code: 'a', title: 'B', kind: 'node', nodeId: 1 }, ok],
  }), /重复/);
  assert.throws(() => ending.serialize({ list: [{ code: 'a', title: 'A', kind: 'thought', thoughtKey: '' }, ok] }),
    /选一个伏笔/);   // 面上叫「伏笔」(2026-09-22 裁决),键名仍是 thought.*
  assert.throws(() => ending.serialize({ list: [{ code: 'a', title: 'A', kind: 'node', nodeId: null }, ok] }),
    /选一个站点/);
});

test('新增结局自带唯一 code,连加几个不会互相覆盖', () => {
  const draft = ending.parse('');
  const a = ending.appendTo(draft.list);
  const b = ending.appendTo(a);
  const codes = b.map(item => item.code);
  assert.equal(new Set(codes).size, codes.length);
  assert.equal(b.filter(item => item.fallback).length, 1, '加新条目不碰兜底');
});

// ===== 结局 = 结局章(2026-09-24)=====

test('章节结局转成 ChapterDTO.ending:兜底 / 伏笔 / 选项 / 站点;没开是 null;缺选择说人话', () => {
  assert.equal(ending.chapterEndingPayload(null), null)
  assert.deepEqual(ending.chapterEndingPayload({ fallback: true, kind: 'fallback' }), { fallback: true })
  assert.deepEqual(ending.chapterEndingPayload({ kind: 'thought', thoughtKey: 'window' }),
    { when: [{ op: 'HAS_TAG', value: 'thought.window.done' }] })
  assert.deepEqual(ending.chapterEndingPayload({ kind: 'node', nodeId: 7 }), { when: [{ op: 'NODE_COMPLETED', nodeId: 7 }] })
  assert.throws(() => ending.chapterEndingPayload({ kind: 'tag', tag: '' }, '窗外'), /「窗外」要选一个选项/)
})

test('结局章的规矩:不放站点、不兼开局、不招商、要选条件;整本书兜底恰好一章', () => {
  assert.equal(ending.chapterEndingIssue({ name: '窗外', ending: { fallback: true }, nodes: [] }), '')
  assert.match(ending.chapterEndingIssue({ ending: { fallback: true }, nodes: [{}] }), /不能放站点/)
  assert.match(ending.chapterEndingIssue({ ending: { fallback: true }, opening: true }), /开局不能同时是结局/)
  assert.match(ending.chapterEndingIssue({ ending: { fallback: true }, recruitEnabled: 1 }), /商家承接/)
  assert.match(ending.chapterEndingIssue({ name: '窗外', ending: { kind: 'thought', thoughtKey: '' } }), /要选一个伏笔/)
  assert.equal(ending.endingChaptersIssue([{ name: 'a' }]), '', '没有结局章不管')
  assert.match(ending.endingChaptersIssue([{ ending: { kind: 'thought', thoughtKey: 'x' } }]), /要有一章兜底/)
  assert.match(ending.endingChaptersIssue([{ ending: { fallback: true } }, { ending: { fallback: true } }]), /只能有一章兜底/)
})

test('回显:带 chapterId 的结局挂回那一章;老结局(没有 chapterId)交给调用方转成结局章', () => {
  const story = JSON.stringify({ schemaVersion: 1, ending: { endings: [
    { code: 'ending1', title: '窗外', chapterId: 12, when: [{ op: 'HAS_TAG', value: 'thought.window.done' }] },
    { code: 'default', title: '余波', summary: '你回到了人群里', fallback: true },
  ] } })
  const out = ending.attachEndingsToChapters([{ id: 11, name: '出生' }, { id: 12, name: '窗外' }], story)
  assert.equal(out.chapters[0].ending, null)
  assert.deepEqual(JSON.parse(JSON.stringify(out.chapters[1].ending)), { fallback: false, kind: 'thought', thoughtKey: 'window', tag: '', nodeId: null })
  assert.equal(out.legacy.length, 1)
  assert.equal(out.legacy[0].title, '余波')
  assert.equal(out.legacy[0].summary, '你回到了人群里')
  assert.equal(out.legacy[0].ending.fallback, true)
})

test('提交前:结局清单交给服务端按章重建;ending 只剩清单就整段去掉,有尾声就留着过校验', () => {
  const only = JSON.stringify({ schemaVersion: 1, call: { opener: 'x' }, ending: { endings: [{ code: 'a', title: 'A', fallback: true }] } })
  assert.deepEqual(JSON.parse(ending.withoutEndingList(only)), { schemaVersion: 1, call: { opener: 'x' } })
  const withEpilogue = JSON.stringify({ schemaVersion: 1, ending: { endings: [{ code: 'a', title: 'A', fallback: true }], epilogues: [{ npcId: 1 }] } })
  assert.equal(ending.withoutEndingList(withEpilogue), withEpilogue)
  assert.equal(ending.withoutEndingList(''), '')
})

test('章节载荷带上结局设置(后端形状);没开结局就不带这个字段', () => {
  const story = require('../../pages/publish/utils/publish/pro-editor-story.js')
  const base = { _localId: 'e1', name: '窗外', nodes: [], blocks: [{ key: 'x', type: 'text', content: '你推开了窗。' }] }
  assert.deepEqual(story.toPayloadChapter(story.materializeChapter(Object.assign({}, base, { ending: { fallback: true, kind: 'fallback' } }))).ending, { fallback: true })
  assert.deepEqual(story.toPayloadChapter(story.materializeChapter(Object.assign({}, base, { ending: { kind: 'tag', tag: 'tag.m_x' } }))).ending,
    { when: [{ op: 'HAS_TAG', value: 'tag.m_x' }] })
  assert.equal('ending' in story.toPayloadChapter(story.materializeChapter(base)), false)
  assert.throws(() => story.toPayloadChapter(story.materializeChapter(Object.assign({}, base, { ending: { kind: 'thought', thoughtKey: '' } }))), /「窗外」要选一个伏笔/)
})
