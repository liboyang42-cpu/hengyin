'use strict';
// 角色(Cast)编辑器 · docs/design/cast-contract-1119.md(业主 2026-11-19)。
// 样例全是虚构文字。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const cast = require('../../pages/publish/utils/publish/story-cast.js');
const story = require('../../pages/publish/utils/publish/pro-editor-story.js');
const ending = require('../../pages/publish/utils/publish/story-ending');

const FABU = path.join(__dirname, '../../pages/publish/fabu');
const read = (f) => fs.readFileSync(path.join(FABU, f), 'utf8');
const seq = (vals) => { let i = 0; return () => vals[i++ % vals.length]; };

function member(over) {
  return Object.assign(cast.normalizeMember({ id: 'c_ab12cd', name: '苏禾', identity: '主唱', color: '#E8604C', voice: { mode: 'preset', preset: 'bright_female' } }, 0), over || {});
}
function cfgWith(list, extra) { return Object.assign(cast.emptyCastConfig(), { cast: list }, extra || {}); }
const apply = (chapter, command) => story.applyStoryCommand({ chapter, pendingMaterials: [] }, command);

/* ── 数据形状 ── */

test('空 / 坏的 cast_config 读成 null(= 没开过),老主题什么都不多', () => {
  assert.equal(cast.parseCastConfig(null), null);
  assert.equal(cast.parseCastConfig(''), null);
  assert.equal(cast.parseCastConfig('{bad'), null);
  assert.equal(cast.parseCastConfig([]), null);
  assert.equal(cast.serializeCastConfig(null), null);
  const v = cast.cardView(null);
  assert.deepEqual(v, { on: false, count: 0, faces: [] });
});

test('回填:字符串与对象都认;非法颜色 / 声音回到默认,线索与结局台词保留', () => {
  const raw = JSON.stringify({ enabled: true, playerName: true, identities: [{ key: 'i_ab12', title: '记者', sub: '来写稿' }],
    cast: [{ id: 'c_ab12cd', name: '苏禾', color: '#123456', voice: { mode: 'weird' }, clues: [{ text: '左肩背琴', chapterId: 7 }], endingLines: { high: '谢谢你来' } }] });
  const cfg = cast.parseCastConfig(raw);
  assert.equal(cfg.enabled, true);
  assert.equal(cfg.cast[0].color, cast.COLORS[0]);
  assert.deepEqual(cfg.cast[0].voice, { mode: 'none' });
  assert.deepEqual(cfg.cast[0].clues, [{ text: '左肩背琴', chapterId: 7 }]);
  assert.equal(cfg.cast[0].endingLines.high, '谢谢你来');
  assert.deepEqual(cast.parseCastConfig(JSON.parse(raw)), cfg);
});

test('新角色:c_ 编号不撞、颜色取第一个没人用的;满 12 位由页面挡', () => {
  const cfg = cfgWith([member({ color: '#E8604C' })]);
  const m = cast.newMember(cfg, seq([0.5, 0.1, 0.2, 0.3, 0.4, 0.6]));
  assert.match(m.id, cast.CAST_ID);
  assert.notEqual(m.id, 'c_ab12cd');
  assert.equal(m.color, '#3D8BFD');
  assert.equal(cast.MAX_CAST, 12);
  const page = read('theme-cast.js');
  assert.match(page, /cast\.length >= storyCast\.MAX_CAST/);
});

test('校验:名字 1–8 字且不重名,身份 ≤30,头像须 https 或 media-asset:N', () => {
  const a = member();
  assert.deepEqual(cast.memberIssues(a, [a]), []);
  assert.equal(cast.memberIssues(member({ name: ' ' }), [])[0].field, 'name');
  assert.equal(cast.memberIssues(member({ name: '一二三四五六七八九' }), [])[0].field, 'name');
  const twin = member({ id: 'c_zz99yy' });
  assert.match(cast.memberIssues(twin, [a, twin])[0].message, /已经有叫「苏禾」/);
  assert.equal(cast.memberIssues(member({ identity: 'x'.repeat(31) }), [])[0].field, 'identity');
  assert.equal(cast.memberIssues(member({ avatarUrl: 'wxfile://tmp.jpg' }), [])[0].field, 'avatar');
  assert.deepEqual(cast.memberIssues(member({ avatarUrl: 'media-asset:42' }), []), []);
  assert.equal(cast.memberIssues(member({ voice: { mode: 'record', url: '' } }), [])[0].field, 'voice');
  assert.deepEqual(cast.memberIssues(member({ voice: { mode: 'record', url: 'media-asset:9' } }), []), []);
});

test('你知道的:最多 6 条、每条 1–40 字、解锁章节须还在;结局台词每档 ≤60', () => {
  const six = Array.from({ length: 7 }, (_, i) => ({ text: '第' + i + '件事', chapterId: null }));
  assert.equal(cast.memberIssues(member({ clues: six }), [])[0].field, 'clues');
  assert.equal(cast.memberIssues(member({ clues: [{ text: '', chapterId: null }] }), [])[0].field, 'clue0');
  assert.equal(cast.memberIssues(member({ clues: [{ text: 'x'.repeat(41), chapterId: null }] }), [])[0].field, 'clue0');
  assert.match(cast.memberIssues(member({ clues: [{ text: '她怕黑', chapterId: 99 }] }), [], [1, 2])[0].message, /章节已经不在/);
  assert.deepEqual(cast.memberIssues(member({ clues: [{ text: '她怕黑', chapterId: 2 }] }), [], [1, 2]), []);
  assert.equal(cast.memberIssues(member({ endingLines: { low: 'x'.repeat(61), mid: '', high: '' } }), [])[0].field, 'ending_low');
});

test('玩家身份:开着时至少 1 个、最多 4 个、每个要有名字', () => {
  const on = cfgWith([], { playerIdentity: true });
  assert.match(cast.firstIssue(on).message, /至少加一个身份/);
  on.identities = [{ key: 'i_aaaa11', title: '', sub: '' }];
  assert.equal(cast.firstIssue(on).field, 'identity0');
  on.identities = [{ key: 'i_aaaa11', title: '九个字的身份名字呀', sub: '' }];
  assert.match(cast.firstIssue(on).message, /最多 8 字/);
  on.identities = [{ key: 'i_aaaa11', title: '记者', sub: 'x'.repeat(25) }];
  assert.match(cast.firstIssue(on).message, /最多 24 字/);
  on.identities = Array.from({ length: 5 }, (_, i) => ({ key: 'i_aaaa1' + i, title: '记者', sub: '' }));
  assert.match(cast.firstIssue(on).message, /最多 4 个/);
  on.playerIdentity = false;
  assert.equal(cast.firstIssue(on), null, '身份关掉时清单只是留着,不拦');
});

test('上送形状:按契约字段名;空线索 / 空台词不带;enabled=false 照样整份带上(关掉不丢数据)', () => {
  const cfg = cfgWith([member({ clues: [{ text: ' 她怕黑 ', chapterId: null }], endingLines: { low: '', mid: '', high: '再见' } }), member({ id: 'c_qq11ww', name: '阿迟', voice: { mode: 'none' } })], { enabled: false });
  const out = JSON.parse(cast.serializeCastConfig(cfg));
  assert.equal(out.enabled, false);
  assert.deepEqual(Object.keys(out).sort(), ['cast', 'enabled', 'identities', 'playerIdentity', 'playerName']);
  assert.deepEqual(out.cast[0], { id: 'c_ab12cd', name: '苏禾', identity: '主唱', avatarUrl: '', color: '#E8604C',
    voice: { mode: 'preset', preset: 'bright_female' }, clues: [{ text: '她怕黑', chapterId: null }], endingLines: { high: '再见' } });
  assert.deepEqual(out.cast[1].voice, { mode: 'none' });
  assert.equal(out.cast[1].clues, undefined);
  assert.throws(() => cast.serializeCastConfig(cfgWith([member({ name: '' })])), /「未命名角色」给角色起个名字/);
});

/* ── 视图模型 ── */

test('首页卡:最多叠 4 张脸 +「N 位」;media-asset 头像先画首字', () => {
  const list = ['甲', '乙', '丙', '丁', '戊'].map((n, i) => member({ id: 'c_aaaa' + i + i, name: n, avatarUrl: i === 0 ? 'https://x.example/a.jpg' : 'media-asset:3' }));
  const v = cast.cardView(cfgWith(list));
  assert.equal(v.on, true);
  assert.equal(v.count, 5);
  assert.equal(v.faces.length, 4);
  assert.equal(v.faces[0].img, 'https://x.example/a.jpg');
  assert.equal(v.faces[1].img, '');
  assert.equal(v.faces[1].initial, '乙');
});

test('谁在说话:角色在前,然后旁白、玩家;删掉的角色名字为空(按普通段落显示,不崩)', () => {
  const cfg = cfgWith([member()]);
  assert.deepEqual(cast.speakerOptions(cfg).map((o) => o.value), ['c_ab12cd', 'narrator', 'player']);
  assert.equal(cast.speakerName(cfg, 'c_ab12cd'), '苏禾');
  assert.equal(cast.speakerName(cfg, 'player'), '玩家');
  assert.equal(cast.speakerName(cfg, 'c_gone00'), '');
  assert.equal(cast.speakerNameMap(cfg).c_gone00, undefined);
});

test('信任:只留 -2..2 的非零整数,0 = 去掉那一行;编辑器卡片小字', () => {
  const cfg = cfgWith([member(), member({ id: 'c_qq11ww', name: '阿迟' })]);
  assert.deepEqual(cast.normalizeTrust([{ castId: 'c_ab12cd', delta: 3 }, { castId: 'c_ab12cd', delta: 0 }, { castId: 'bad', delta: 1 }, { castId: 'c_qq11ww', delta: -2 }]),
    [{ castId: 'c_qq11ww', delta: -2 }]);
  let t = cast.setTrust([], 'c_qq11ww', 1, ['c_ab12cd', 'c_qq11ww']);
  t = cast.setTrust(t, 'c_ab12cd', -1, ['c_ab12cd', 'c_qq11ww']);
  assert.deepEqual(t, [{ castId: 'c_ab12cd', delta: -1 }, { castId: 'c_qq11ww', delta: 1 }]);
  assert.deepEqual(cast.setTrust(t, 'c_ab12cd', 0), [{ castId: 'c_qq11ww', delta: 1 }]);
  assert.deepEqual(cast.setTrust([], 'c_ab12cd', 9), [{ castId: 'c_ab12cd', delta: 2 }]);
  const rows = cast.trustRows(cfg, [{ castId: 'c_qq11ww', delta: 1 }]);
  assert.deepEqual(rows.map((r) => [r.castId, r.delta, r.on]), [['c_ab12cd', 0, false], ['c_qq11ww', 1, true]]);
  assert.equal(cast.trustSummary(cfg, [{ castId: 'c_ab12cd', delta: 1 }, { castId: 'c_qq11ww', delta: -2 }]), '苏禾 +1 · 阿迟 −2');
});

test('线索章节选项:开局就有 + 已保存的普通章(序章 / 结局章 / 未保存章不进)', () => {
  const opts = cast.clueChapterOptions([{ id: 1, opening: true, name: '序' }, { id: 5, name: '排练室' }, { name: '未保存' }, { id: 8, ending: { fallback: true }, name: '尾声' }]);
  assert.deepEqual(opts, [{ value: null, label: '开局就有' }, { value: 5, label: '第 1 章 · 排练室' }]);
  assert.equal(cast.clueChapterLabel(opts, null), '开局就有');
  assert.equal(cast.clueChapterLabel(opts, 5), '第 1 章 · 排练室');
});

/* ── 故事流:对白块 ── */

test('对白块:选人后落块,改内容 / 换人 / 录音;换成旁白 = 变回普通段落', () => {
  let chapter = { nodes: [], blocks: [{ key: 't1', type: 'text', content: '雨停了。' }] };
  let r = apply(chapter, { type: 'insertMediaAt', mediaType: 'line', index: 1, blockKey: 'l1', speaker: 'c_ab12cd' });
  chapter = r.draft.chapter;
  assert.deepEqual(chapter.blocks[1], { key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '', audioUrl: '' });
  assert.throws(() => apply(chapter, { type: 'insertMediaAt', mediaType: 'line', index: 0, blockKey: 'l2' }), /先选谁在说话/);
  chapter = apply(chapter, { type: 'updateLine', blockKey: 'l1', content: '你来了。' }).draft.chapter;
  chapter = apply(chapter, { type: 'updateLine', blockKey: 'l1', speaker: 'player', audioUrl: 'media-asset:12' }).draft.chapter;
  assert.equal(chapter.blocks[1].speaker, 'player');
  assert.equal(chapter.blocks[1].audioUrl, 'media-asset:12');
  assert.throws(() => apply(chapter, { type: 'updateLine', blockKey: 'l1', content: 'x'.repeat(301) }), /300/);
  const asText = apply(chapter, { type: 'updateLine', blockKey: 'l1', speaker: 'narrator' }).draft.chapter;
  assert.deepEqual(asText.blocks[1], { key: 'l1', type: 'text', content: '你来了。' });
  // 带条件的台词变回段落:cond 挪成正文的 when(#873:正文块不能带 cond)
  const condLine = { nodes: [], blocks: [{ key: 'l9', type: 'line', speaker: 'player', content: '嗯', cond: { op: 'HAS_TAG', value: 'tag.met' } }] };
  assert.deepEqual(apply(condLine, { type: 'updateLine', blockKey: 'l9', speaker: 'narrator' }).draft.chapter.blocks[0],
    { key: 'l9', type: 'text', content: '嗯', when: { op: 'HAS_TAG', value: 'tag.met' } });
});

test('对白块上送:1–300 字、说话的人必填、cond 原样带;空录音不带键;可以删', () => {
  const chapter = (blocks) => ({ nodes: [], blocks });
  const ok = story.toPayloadChapter(chapter([{ key: 'l1', type: 'line', speaker: 'c_ab12cd', content: ' 你来了。 ', audioUrl: '', cond: { op: 'HAS_TAG', value: 'tag.met' } }]));
  assert.deepEqual(ok.blocks[0], { key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '你来了。', cond: { op: 'HAS_TAG', value: 'tag.met' } });
  const withAudio = story.toPayloadChapter(chapter([{ key: 'l1', type: 'line', speaker: 'player', content: '嗯', audioUrl: 'media-asset:3' }]));
  assert.equal(withAudio.blocks[0].audioUrl, 'media-asset:3');
  assert.throws(() => story.toPayloadChapter(chapter([{ key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '  ' }])), /还没写内容/);
  assert.throws(() => story.toPayloadChapter(chapter([{ key: 'l1', type: 'line', speaker: '', content: '嗯' }])), /谁在说话/);
  assert.throws(() => story.toPayloadChapter(chapter([{ key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '嗯', audioUrl: 'wxfile://a' }])), /录音/);
  const removed = apply(chapter([{ key: 'l1', type: 'line', speaker: 'player', content: '嗯' }]), { type: 'removeMedia', blockKey: 'l1' });
  assert.equal(removed.draft.chapter.blocks.length, 0);
});

test('对白块回填:重开编辑器原样还原,可以像其它块一样拖动', () => {
  let n = 0;
  const m = story.materializeChapter({ nodes: [], blocks: [{ key: 'a', type: 'text', content: '甲' }, { key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '乙', audioUrl: 'media-asset:1' }] }, () => 'k' + (++n));
  assert.deepEqual(m.blocks[1], { key: 'l1', type: 'line', speaker: 'c_ab12cd', content: '乙', audioUrl: 'media-asset:1' });
  const moved = apply(m, { type: 'moveBlock', blockKey: 'l1', index: 0 });
  assert.equal(moved.draft.chapter.blocks[0].key, 'l1');
});

/* ── D2:分支信任 + 结局归属 ── */

test('分支选项的 trust:编辑、往返、上送都保留;全为 0 就不带键', () => {
  let chapter = { nodes: [], blocks: [] };
  chapter = apply(chapter, { type: 'insertMediaAt', mediaType: 'branch', index: 0, blockKey: 'b1' }).draft.chapter;
  chapter = apply(chapter, { type: 'updateBranch', blockKey: 'b1', content: '要不要回去？', option: { index: 0, text: '回去', trust: [{ castId: 'c_ab12cd', delta: 2 }] } }).draft.chapter;
  chapter = apply(chapter, { type: 'updateBranch', blockKey: 'b1', option: { index: 1, text: '不回' } }).draft.chapter;
  assert.deepEqual(chapter.blocks[0].options[0].trust, [{ castId: 'c_ab12cd', delta: 2 }]);
  const out = story.toPayloadChapter(chapter);
  assert.deepEqual(out.blocks[0].options, [{ text: '回去', to: 'continue', trust: [{ castId: 'c_ab12cd', delta: 2 }] }, { text: '不回', to: 'continue' }]);
  const cleared = apply(chapter, { type: 'updateBranch', blockKey: 'b1', option: { index: 0, trust: [] } }).draft.chapter;
  assert.equal(cleared.blocks[0].options[0].trust, undefined);
  assert.deepEqual(story.normalizeBranchOptions([{ text: 'a', trust: [{ castId: 'c_ab12cd', delta: 5 }] }])[0], { text: 'a', to: 'continue' });
});

test('结局章归属角色:{endingFor} 上送,读回挂回那一章;没选角色就拦;默认结局不带', () => {
  assert.deepEqual(ending.chapterEndingPayload({ fallback: false, kind: 'cast', endingFor: 'c_ab12cd' }, '她的结局'), { endingFor: 'c_ab12cd' });
  assert.equal(ending.chapterEndingIssue({ name: '她的结局', ending: { fallback: false, kind: 'cast', endingFor: '' } }), '「她的结局」要选归属角色');
  assert.deepEqual(ending.chapterEndingPayload({ fallback: true, kind: 'fallback' }), { fallback: true });
  const story1 = JSON.stringify({ ending: { endings: [{ code: 'e1', title: '她', chapterId: 3, endingFor: 'c_ab12cd' }, { code: 'd', title: '默认', fallback: true, chapterId: 4 }] } });
  const back = ending.attachEndingsToChapters([{ id: 3 }, { id: 4 }], story1);
  assert.deepEqual(back.chapters[0].ending, { fallback: false, kind: 'cast', thoughtKey: '', tag: '', nodeId: null, _when: undefined, endingFor: 'c_ab12cd' });
  assert.deepEqual(back.chapters[1].ending, { fallback: true, kind: 'fallback' });
  assert.deepEqual(cast.endingForOptions(cfgWith([member()])), [{ value: '', label: '不归属任何角色' }, { value: 'c_ab12cd', label: '苏禾' }]);
});

/* ── 页面接线(静态):开关关掉只是不露面;老主题请求不变 ── */

test('页面:「对白」只在开关开着时进添加栏,紧跟「段落」;信任 / 归属 / 对白名字都看 castOn', () => {
  const js = read('theme-cast.js');
  assert.match(js, /kind: 'line', label: '对白'/);
  assert.match(js, /if \(on && !hasLine\)/);
  assert.match(js, /findIndex\(\(i\) => i\.kind === 'text'\)\) \+ 1/);
  const wxml = read('index.wxml');
  assert.match(wxml, /wx:if="\{\{castOn && storyBranchTrust\[optIndex\]\.length\}\}"/);
  assert.match(wxml, /sx-line__who" wx:if="\{\{castOn && storySpeakerNameOf\[block\.speaker\]\}\}"/);
  assert.match(wxml, /\(castOn && !chapterForm\.ending\.fallback\) \|\| chapterForm\.ending\.kind === 'cast'/);
  assert.match(wxml, /storySheet === 'speaker'/);
  assert.match(wxml, /<include src="cast-sheet\.wxml" \/>/);
});

test('页面:关开关只置 enabled,不清数据;没开过就不往请求里加 castConfig', () => {
  const js = read('theme-cast.js');
  assert.match(js, /cfg\.enabled = on;\s*this\._writeCast\(cfg\);/);
  const index = read('index.js');
  assert.match(index, /\.\.\.\(castConfig \? \{ castConfig \} : \{\}\)/);
  assert.match(index, /'formData\.castConfig': storyCast\.parseCastConfig\(detail\.castConfig \|\| topic\.castConfig\)/);
  assert.match(index, /castConfig: null,/);
});

test('页面:「完成」有缺项时滚到那一栏并红字(#871),不是静默不动', () => {
  const js = read('theme-cast.js');
  assert.match(js, /'castEdit\.errField': issues\[0\]\.field/);
  assert.match(js, /'castEdit\.scrollTo': anchorOf\(issues\[0\]\.field\)/);
  const sheet = read('cast-sheet.wxml');
  assert.match(sheet, /scroll-into-view="\{\{castEdit\.scrollTo\}\}"/);
  for (const id of ['cm-name', 'cm-identity', 'cm-avatar', 'cm-voice', 'cm-color', 'cm-clues', 'cm-ending']) {
    assert.ok(sheet.includes('id="' + id + '"'), id + ' 锚点要在');
  }
  assert.match(sheet, /<cy-inline-error wx:if="\{\{castEdit\.errField === 'name'\}\}" kind="field"/);
  // 满额的「添加」行不藏:点了在下面说为什么
  assert.match(sheet, /aria-disabled="\{\{castView\.atMax\}\}"/);
  assert.match(sheet, /castError\.field === 'add'/);
});

test('页面:TTS 没配置只收起「试听」,不弹错', () => {
  const js = read('theme-cast.js');
  assert.match(js, /if \(ttsNotConfigured\(res\)\) this\.setData\(\{ castTtsOff: true, castPreviewing: '' \}\);/);
  assert.match(js, /data\.errorCode === 'CAST_TTS_NOT_CONFIGURED'/);
  assert.match(read('cast-sheet.wxml'), /castEdit\.m\.voice\.mode === 'preset' && !castTtsOff/);
  assert.match(js, /url: '\/api\/topic\/cast\/voice', method: 'GET'/);
});

test('试玩预览:归属角色的结局卡写「X」最信任你时', () => {
  const { buildTopicPreview } = require('../../pages/publish/utils/publish/topic-draft-preview.js');
  const input = { name: '乐队重聚', productType: 1, castConfig: cfgWith([member()]), chapters: [
    { _localId: 'c1', name: '排练室', nodes: [], blocks: [{ key: 'a', type: 'text', content: '雨停了。' }] },
    { _localId: 'c2', name: '她的结局', nodes: [], blocks: [{ key: 'b', type: 'text', content: '她笑了。' }], ending: { fallback: false, kind: 'cast', endingFor: 'c_ab12cd' } },
    { _localId: 'c3', name: '散场', nodes: [], blocks: [{ key: 'c', type: 'text', content: '各自回家。' }], ending: { fallback: true, kind: 'fallback' } },
  ] };
  assert.deepEqual(buildTopicPreview(input).endings.map((e) => [e.title, e.cond]), [['她的结局', '「苏禾」最信任你时'], ['散场', '其它结局都不中时']]);
});

test('草稿保存:半截身份 / 空线索先不带,带不了就整份不带;发布仍严格拦', () => {
  const cfg = cfgWith([member({ clues: [{ text: '', chapterId: null }, { text: '她怕黑', chapterId: 99 }] })], {
    playerIdentity: true, identities: [{ key: 'i_aaaa11', title: '', sub: '' }] });
  assert.throws(() => cast.serializeCastConfig(cfg, [1]));
  const out = JSON.parse(cast.serializeCastConfig(cfg, [1], { draft: true }));
  assert.equal(out.playerIdentity, false);
  assert.deepEqual(out.identities, []);
  assert.equal(out.cast[0].clues, undefined, '空线索与解锁章已删的线索都不带(不能变成开局就有)');
  assert.equal(cfg.identities.length, 1, '本机那份不动');
  assert.equal(cast.serializeCastConfig(cfgWith([member({ name: '' })]), [], { draft: true }), null);
  assert.match(read('../fabu/index.js'), /themeCast\.payloadOf\(this, draft \|\| !!\(options && options\.quiet\)\)/);
});
