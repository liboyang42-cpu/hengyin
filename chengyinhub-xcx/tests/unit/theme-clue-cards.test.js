const test = require('node:test');
const assert = require('node:assert/strict');
global.wx = { getStorageSync: () => '', setStorageSync: () => {}, showToast: () => {} };
global.getApp = () => ({ globalData: {} });
const backpack = require('../../pages/play/utils/theme-backpack.js');
const plans = require('../../pages/play/utils/theme-plans.js');

test('服务端回读的线索：范围单列，正文与图片按来源原样保留；没有 clues 时与旧行为一致', () => {
  const base = { contents: [], events: [], scope: 'TEAM', teamId: 1, availableActions: [] };
  const procedure = '先隔离抽热支路 ISO，再接回热旁路 BYP，最后释放启动供能 START。';
  const withClues = backpack.present(Object.assign({}, base, { clues: [
    { id: 'clue.h_a', name: 'EV07 许岚留言', scope: 'TEAM', sources: [{ nodeId: 11, title: 'L03 求救点', blocks: [{ text: '妈妈留下的录音……', images: ['https://cdn.example/ev07.jpg'] }] }] },
    { id: 'clue.h_b', name: 'EV04 维护规程', scope: 'PUBLIC', sources: [{ nodeId: 14, title: 'L00 证据墙', blocks: [{ text: procedure, images: ['https://cdn.example/ev04-1.jpg', 'https://cdn.example/ev04-2.jpg'] }, { text: '第二页', images: [] }] }] },
    { id: 'clue.h_c', name: 'EV05 授权链', scope: 'PUBLIC' },
  ] }), []);
  const [ev07, ev04, ev05] = withClues.clues;
  assert.equal(ev07.scopeText, '本队线索'); assert.equal(ev04.scopeText, '全场公开');
  assert.deepEqual(ev07.blocks, [{ source: 'L03 求救点', text: '妈妈留下的录音……', images: ['https://cdn.example/ev07.jpg'] }]);
  assert.equal(ev04.blocks[0].text, procedure, '完整规程正文不被替换或截断');
  assert.deepEqual(ev04.blocks[0].images, ['https://cdn.example/ev04-1.jpg', 'https://cdn.example/ev04-2.jpg']);
  assert.equal(ev04.blocks.length, 2);
  assert.deepEqual(ev05.blocks, [], '未配内容时只显示名称与范围，不臆造正文');
  assert.ok(withClues.clues.every(c => !('content' in c) || c.content === undefined), '范围不再冒充正文');
  // 同名线索本队私有与全场公开各自成行，不互相覆盖
  const both = backpack.present(Object.assign({}, base, { clues: [{ id: 'clue.h_d', name: 'EV03 人口原表', scope: 'TEAM', sources: [] }, { id: 'clue.h_d', name: 'EV03 人口原表', scope: 'PUBLIC', sources: [] }] }), []);
  assert.deepEqual(both.clues.map(c => c.id), ['clue.h_d:TEAM', 'clue.h_d:PUBLIC']);
  assert.deepEqual(backpack.present(base, []).clues, []);
});

test('线索页签渲染正文、范围与可预览图片', () => {
  const fs = require('node:fs'), path = require('node:path');
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /item\.scopeText/);
  assert.match(wxml, /wx:for="\{\{item\.blocks\}\}"/);
  assert.match(wxml, /block\.text/);
  assert.match(wxml, /bindtap="previewThemeClueImage"/);
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(js, /previewThemeClueImage\(e\)/);
});

test('方案缺少公开线索时显示线索名称，不再只说前置未满足', () => {
  const view = { teamId: 1, availableActions: [], interactionTools: [{ kind: 'PLAN', endingKey: 'tpl_vote_1_1', title: '第二次日出', missingClues: ['EV06 负载校验'] }], world: {} };
  const rows = plans.present(view, []).planRows;
  assert.equal(rows[0].conditionText, '还缺线索：EV06 负载校验');
  const met = plans.present({ ...view, interactionTools: [{ kind: 'PLAN', endingKey: 'x', conditionsMet: true }] }, []).planRows[0];
  assert.equal(met.conditionText, '前置条件已满足');
});
