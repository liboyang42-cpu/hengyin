'use strict';
// 角色信任接到站点选择类玩法(cast-game-trust-1119):玩法编辑器的「信任」行 + 玩家端结果里的「X会记住这件事」。
// 样例全是虚构文字。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8');
const strip = (s) => s.replace(/<!--[\s\S]*?-->/g, '');

let pageConfig;
global.getApp = global.getApp || (() => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest: () => {},
  getAuthorization: () => 'test', getUserID: () => 7, getUserInfo: () => null, getNickname: () => '', getAvatar: () => '',
  getToken: () => '', getRequestErrorMessage: (e, f) => (e && e.message) || f, tips: () => {} }));
global.wx = global.wx || { getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }), showToast: () => {}, showLoading: () => {}, hideLoading: () => {},
  showModal: () => {}, navigateTo: () => {}, redirectTo: () => {}, pageScrollTo: () => {}, nextTick: (fn) => fn(),
  getBackgroundAudioManager: () => ({ stop: () => {} }) };

const cast = require('../../pages/publish/utils/publish/story-cast.js');
const advancedGameConfig = require('../../pages/publish/utils/publish/advanced-game-config.js');
const castPlay = require('../../pages/play/utils/cast-play.js');

const CFG = { enabled: true, cast: [{ id: 'c_ab12cd', name: '苏禾' }, { id: 'c_zz98yx', name: '阿迟' }] };

function makeTempPage() {
  const prev = global.Page;
  global.Page = (config) => { pageConfig = config; };
  const file = require.resolve('../../pages/publish/temp/index.js');
  delete require.cache[file];
  require(file);
  global.Page = prev;
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch, done) => {
    Object.entries(patch).forEach(([p, value]) => {
      const parts = p.replace(/\[(\d+)\]/g, '.$1').split('.');
      let at = page.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    });
    if (done) done();
  };
  return page;
}

/* ── 主题编辑器 → 玩法编辑器:角色开着才带 ── */

test('castQuery:开着且有角色才带 &cast=,玩法编辑器读回 [{id,name}];坏参数一律空', () => {
  const q = cast.castQuery(CFG);
  assert.match(q, /^&cast=/);
  assert.deepEqual(cast.parseCastQuery(decodeURIComponent(q.slice('&cast='.length))), [{ id: 'c_ab12cd', name: '苏禾' }, { id: 'c_zz98yx', name: '阿迟' }]);
  assert.equal(cast.castQuery(Object.assign({}, CFG, { enabled: false })), '');
  assert.equal(cast.castQuery({ enabled: true, cast: [] }), '');
  assert.equal(cast.castQuery(null), '');
  assert.deepEqual(cast.parseCastQuery('{bad'), []);
  assert.deepEqual(cast.parseCastQuery(undefined), []);
  assert.deepEqual(cast.parseCastQuery(JSON.stringify([{ id: 'x', name: '坏' }, { id: 'c_ab12cd', name: '苏禾' }, { id: 'c_ab12cd', name: '重复' }])), [{ id: 'c_ab12cd', name: '苏禾' }]);
  const fabu = read('pages/publish/fabu/index.js');
  assert.equal((fabu.match(/storyCast\.castQuery\(this\.data\.formData\.castConfig\)/g) || []).length, 2, '新建玩法与编辑已绑定玩法两条入口都带');
});

/* ── 玩法编辑器:选择类选项的「信任」行 ── */

test('编辑器:角色开着时每个选项一行信任,0 收起成名字药丸,用现成 cy-stepper', () => {
  const wxml = strip(read('pages/publish/temp/index.wxml'));
  const row = wxml.slice(wxml.indexOf('class="cg-trust"'), wxml.indexOf('</view>\n      </view>', wxml.indexOf('class="cg-trust"')));
  assert.match(wxml, /<view class="cg-trust" wx:if="\{\{castMembers\.length\}\}">/);
  assert.match(row, /opt\.trust\(castMembers, item\.trust\)/);
  assert.match(row, /<cy-stepper value="\{\{t\.delta\}\}" min="-2" max="2"[^>]*bind:change="onOptionTrust"/);
  assert.match(row, /wx:else class="cg-chip"[^>]*data-v="1" catchtap="onOptionTrust"/);
  assert.ok(!/会记住|信任变化/.test(row.replace('>信任<', '')), '不加说明文字');
  const json = JSON.parse(read('pages/publish/temp/index.json'));
  assert.equal(json.usingComponents['cy-stepper'], '/pages/publish/components/stepper/index');
});

test('编辑器:点名字 +1 展开,步进器改值,回到 0 整键摘掉;顺序跟角色表', () => {
  const page = makeTempPage();
  page.data.castMembers = cast.parseCastQuery(JSON.stringify(CFG.cast));
  page.data.advanced = page.data.advanced || {};
  page.data.advanced.qa = { enabled: true, mode: 'PICK', judge: false, title: '跟谁走', options: [{ id: 'a', label: '跟她' }, { id: 'b', label: '跟他' }] };
  const ev = (index, castId, value, viaStepper) => ({ currentTarget: { dataset: viaStepper
    ? { path: 'qa.options', index, cast: castId } : { path: 'qa.options', index, cast: castId, v: '1' } },
    detail: viaStepper ? { value } : {} });
  page.onOptionTrust(ev(0, 'c_zz98yx'));
  page.onOptionTrust(ev(0, 'c_ab12cd'));
  assert.deepEqual(page.data.advanced.qa.options[0].trust, [{ castId: 'c_ab12cd', delta: 1 }, { castId: 'c_zz98yx', delta: 1 }]);
  page.onOptionTrust(ev(0, 'c_ab12cd', 5, true));
  assert.deepEqual(page.data.advanced.qa.options[0].trust[0], { castId: 'c_ab12cd', delta: 2 }, '夹在 -2..2');
  page.onOptionTrust(ev(0, 'c_ab12cd', 0, true));
  page.onOptionTrust(ev(0, 'c_zz98yx', 0, true));
  assert.equal('trust' in page.data.advanced.qa.options[0], false);
  assert.equal('trust' in page.data.advanced.qa.options[1], false, '别的选项不动');
  const fresh = makeTempPage();
  assert.deepEqual(fresh.data.castMembers || [], [], '没带角色就不出信任行');
});

test('存取往返:问答 / 建档选项的 trust 原样回来,0 与坏行在存盘时丢掉', () => {
  const raw = JSON.stringify({ schemaVersion: 1,
    qa: { enabled: true, mode: 'PICK', judge: false, title: '跟谁走', options: [
      { id: 'a', label: '跟她', trust: [{ castId: 'c_ab12cd', delta: 2 }, { castId: 'c_zz98yx', delta: 0 }, { castId: 'bad', delta: 1 }] },
      { id: 'b', label: '跟他', trust: [] }] } });
  const model = advancedGameConfig.parse(raw).value;
  assert.deepEqual(model.qa.options[0].trust.slice(0, 1), [{ castId: 'c_ab12cd', delta: 2 }]);
  const saved = JSON.parse(advancedGameConfig.serialize(model));
  assert.deepEqual(saved.qa.options[0].trust, [{ castId: 'c_ab12cd', delta: 2 }]);
  assert.equal('trust' in saved.qa.options[1], false);
  const again = JSON.parse(advancedGameConfig.serialize(advancedGameConfig.parse(JSON.stringify(saved)).value));
  assert.deepEqual(again.qa.options[0].trust, [{ castId: 'c_ab12cd', delta: 2 }]);
});

/* ── 玩家端:站点结果里的「X会记住这件事」 ── */

test('rememberText:只认回包 trustChanged 里本主题的角色,去重,不带数字', () => {
  const cfg = { cast: [{ id: 'c_ab12cd', name: '苏禾' }, { id: 'c_zz98yx', name: '阿迟' }] };
  assert.equal(castPlay.rememberText(cfg, ['c_ab12cd']), '苏禾会记住这件事');
  assert.equal(castPlay.rememberText(cfg, ['c_ab12cd', 'c_zz98yx', 'c_ab12cd', 'c_ghost1']), '苏禾、阿迟会记住这件事');
  assert.equal(castPlay.rememberText(cfg, ['c_ghost1']), '');
  assert.equal(castPlay.rememberText(cfg, null), '');
  assert.equal(castPlay.rememberText(null, ['c_ab12cd']), '');
  const api = castPlay.methods(() => Promise.resolve({}));
  assert.equal(api._castRemember.call({ _cast: cfg }, ['c_zz98yx']), '阿迟会记住这件事');
  assert.equal(api._castRemember.call({ _cast: null }, ['c_zz98yx']), '', '没开角色不出');
});

test('结果反馈:回包 trustChanged → 会话事件 → 问答 kit.remember → 结果下方居中浅灰一行', () => {
  const game = read('pages/play/components/advanced-game/index.js');
  assert.match(game, /trustChanged: \(state && Array\.isArray\(state\.trustChanged\)\) \? state\.trustChanged\.slice\(\) : null/);
  const page = read('pages/play/index.js');
  assert.match(page, /kit\.type === 'qa'\) kit\.remember = \(this\._castRemember \? this\._castRemember\(view\.trustChanged\) : ''\) \|\| \(previous && previous\.sessionId != null && String\(previous\.sessionId\) === String\(kit\.sessionId\) && previous\.remember\) \|\| ''/);
  assert.match(strip(read('pages/playkit/components/playkit/index.wxml')), /<cy-playkit-qa [\s\S]*?remember="\{\{kit\.remember\}\}"/);
  const qaJs = read('pages/playkit/components/playkit-qa/index.js');
  assert.match(qaJs, /remember: \{ type: String, value: '' \}/);
  const qaWxml = strip(read('pages/playkit/components/playkit-qa/index.wxml'));
  assert.match(qaWxml, /<view wx:if="\{\{remember && \(finished \|\| passed\)\}\}" class="qa__remember" aria-live="polite">\{\{remember\}\}<\/view>/);
  const css = read('pages/playkit/components/playkit-qa/index.wxss');
  const rule = css.slice(css.indexOf('.qa__remember {'));
  assert.match(rule, /font-size: var\(--cy-type-caption\)/);
  assert.match(rule, /text-align: center/);
  assert.match(rule, /opacity: \.46/);
});
