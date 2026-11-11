'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const test = require('node:test');

const file = path.resolve(__dirname, '../../pages/play/index.js');
const hud = require('../../utils/wxs/hud-slots.wxs');
const wxml = fs.readFileSync(path.join(path.dirname(file), 'index.wxml'), 'utf8');
const raw = {
  nodeId: 9170502, chapterId: 9170302, merchantId: 9002,
  done: true, arrived: true, gameDone: false, hasGame: true,
  validationMethod: 4, gameTitle: '店内游戏',
  advancedConfigJson: JSON.stringify({ schemaVersion: '5.1', scan: { enabled: true } }),
};

function page(mode = 2) {
  let definition;
  const app = { globalData: { user_id: 9004, features: {} }, sendRequest() {} };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    require: createRequire(file), getApp: () => app, Page(value) { definition = value; },
    wx: { getStorageSync: () => '', nextTick() {}, navigateTo() {} },
    console, setTimeout() {}, clearTimeout, setInterval, clearInterval,
  }, { filename: file });
  const instance = Object.assign({}, definition, {
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), { mode, packOpen: true }),
    setData(patch, callback) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.');
        let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part];
        target[parts[parts.length - 1]] = value;
      }
      if (callback) callback();
    },
    closeChapStory() {}, stopAudio() {}, _refreshNavRim() {}, buildJournal() {},
  });
  return instance;
}

function nodeFor(instance, override) {
  return instance.normNode(Object.assign({}, raw, override), 0, instance.data.mode);
}

function ctaDisabled(view) {
  const expression = wxml.match(/<cy-btn variant="\{\{([^}]+)\}\}" style="width:100%" bindtap="heroEnter"/)[1];
  return vm.runInNewContext(expression, { hero: { node: view } }) === 'disabled';
}

test('redeemed merchant unfinished game: real normalizer, hero CTA and heroEnter reach normal advanced host', () => {
  const instance = page();
  const node = nodeFor(instance);
  instance.data.nodes = [node];
  instance.data.hero = { show: true, node: instance._heroView(node) };
  assert.equal(ctaDisabled(instance.data.hero.node), false);
  instance.heroEnter();
  assert.equal(instance.data.hero.show, false);
  assert.equal(instance.data.advancedPlay.show, true);
  assert.equal(instance.data.advancedPlay.node, node);
  assert.equal(node.done, true, 'redemption remains consumed');
  assert.equal(node.gameDone, false, 'opening never fabricates completion');
});

test('card geometry fallback uses same redeemed-game entry with a dataset id', () => {
  const instance = page();
  const node = nodeFor(instance);
  instance.data.nodes = [node];
  instance.heroEnter({ currentTarget: { dataset: { id: String(node.nodeId) } } });
  assert.equal(instance.data.advancedPlay.show, true);
});

test('completed, nonmerchant, unconfigured and unarrived redeemed nodes remain disabled', () => {
  for (const override of [
    { gameDone: true }, { merchantId: 0 }, { arrived: false },
    { hasGame: false }, { validationMethod: 0, advancedConfigJson: '' },
  ]) {
    const instance = page();
    const node = nodeFor(instance, override);
    instance.data.nodes = [node];
    instance.data.hero = { show: true, node: instance._heroView(node) };
    assert.equal(ctaDisabled(instance.data.hero.node), true, JSON.stringify(override));
    let entries = 0;
    instance.startGame = () => { entries += 1; };
    instance.heroEnter();
    assert.equal(entries, 0, JSON.stringify(override));
  }
});

test('classic completed nodes remain disabled, unredeemed FREE entries keep the arrival gate', () => {
  const classic = page(1);
  assert.equal(ctaDisabled(classic._heroView(nodeFor(classic))), true);
  const instance = page();
  const node = nodeFor(instance, { done: false, arrived: false });
  instance.data.nodes = [node];
  instance.data.hero = { show: true, node: instance._heroView(node) };
  let arrivals = 0;
  instance.openMerchantFromSheet = () => { arrivals += 1; };
  assert.equal(ctaDisabled(instance.data.hero.node), false);
  instance.heroEnter();
  assert.equal(arrivals, 1);
  assert.equal(instance.data.advancedPlay.show, false);
});

test('redeemed-game entry retains normal activity playable gate', () => {
  const instance = page();
  const node = nodeFor(instance);
  instance.data.nodes = [node];
  instance._playable = false;
  instance.heroEnter({ currentTarget: { dataset: { id: node.nodeId } } });
  assert.equal(instance.data.advancedPlay.show, false);
});

test('authoritative rebuild refreshes an open hero without losing its animation geometry', () => {
  const instance = page();
  const old = nodeFor(instance, { done: false, arrived: false });
  instance.data.hero = { show: true, open: true, style: 'left:20px', node: instance._heroView(old) };
  instance.data.nodes = [nodeFor(instance)];
  instance.computeNext = () => null;
  instance.rebuild();
  assert.equal(instance.data.hero.open, true);
  assert.equal(instance.data.hero.style, 'left:20px');
  assert.equal(instance.data.hero.node.stepArrive, true);
  assert.equal(instance.data.hero.node.stepRedeem, true);
  assert.equal(instance.data.hero.node.stepProof, false);
  assert.equal(ctaDisabled(instance.data.hero.node), false);
  instance.data.nodes = [nodeFor(instance, { gameDone: true })];
  instance.rebuild();
  assert.equal(instance.data.hero.node.stepProof, true);
  assert.equal(ctaDisabled(instance.data.hero.node), true);
});

test('duplicate-redemption warning and QR visibility still use redemption done', () => {
  assert.match(wxml, /hero\.node\.done[^\n]*class="fx-shop__off">已核销/);
  assert.match(wxml, /hero\.node\.isMerchant && !hero\.node\.done && regId/);
});


function rebuildFree(overrides = {}, mode = 2) {
  const instance = page(mode);
  instance._playable = true;
  instance.data.loading = false;
  instance.computeNext = () => null;
  instance.data.nodes = [nodeFor(instance, overrides), nodeFor(instance, { nodeId: 9170501, gameDone: true })];
  instance.rebuild();
  return instance;
}

function bannerVisible(instance) {
  const expression = wxml.match(/class="banner" wx:if="{{([^}]+)}}"/)[1];
  return vm.runInNewContext(expression, Object.assign({ hud }, instance.data));
}

function packVisible(instance) {
  // 2026-11-11 f-play V3-08:选章弹窗挪到这块外面(走完后也要能弹),按块自己的守卫条件定位
  const expression = wxml.match(/<block wx:if="{{(!screen && \(!allDone \|\| canContinueGames\)[^}]+)}}">/)[1];
  return vm.runInNewContext(expression, instance.data);
}

test('all redeemed FREE keeps unfinished configured merchant game count and normal card entry', () => {
  const instance = rebuildFree();
  assert.equal(instance.data.allDone, true);
  assert.equal(instance.data.doneCount, 2);
  assert.equal(instance.data.remainingGameCount, 1);
  assert.equal(packVisible(instance), true);
  instance.heroEnter({ currentTarget: { dataset: { id: 9170502 } } });
  assert.equal(instance.data.advancedPlay.show, true);
  assert.equal(instance.data.nodes[0].gameDone, false);
  assert.equal(instance.data.nodes[0].done, true);
});

test('partial redemption count follows gameDone for merchants and done for ordinary nodes/CITY', () => {
  const partial = rebuildFree({ done: false });
  assert.equal(partial.data.remainingGameCount, 1);
  assert.equal(partial.data.allDone, false);
  assert.equal(packVisible(partial), true);
  const played = rebuildFree({ done: false, gameDone: true });
  assert.equal(played.data.remainingGameCount, 0);
  for (const mode of [1, 2]) {
    const ordinary = rebuildFree({ merchantId: 0 }, mode);
    assert.equal(ordinary.data.remainingGameCount, 0);
  }
});

test('summary Continue stays in the normal pack only while current FREE merchant games remain playable', () => {
  for (const scenario of [
    { patch: {}, mode: 2, playable: true, leaves: 0 },
    { patch: { gameDone: true }, mode: 2, playable: true, leaves: 1 },
    { patch: {}, mode: 2, playable: false, leaves: 1 },
    { patch: { merchantId: 0 }, mode: 2, playable: true, leaves: 1 },
    { patch: {}, mode: 1, playable: true, leaves: 1 },
  ]) {
    const instance = rebuildFree(scenario.patch, scenario.mode);
    instance._playable = scenario.playable;
    instance.rebuild();
    let leaves = 0;
    instance.goBackDetail = () => { leaves += 1; };
    instance._hideFinish = callback => callback();
    instance.closeFinish();
    assert.equal(leaves, scenario.leaves, JSON.stringify(scenario));
    assert.equal(packVisible(instance), scenario.leaves === 0, JSON.stringify(scenario));
    assert.equal(bannerVisible(instance), scenario.leaves === 1, JSON.stringify(scenario));
    assert.equal(instance.data.allDone, true);
  }
});

test('FREE finale waits for the final game receipt; expired FREE keeps the original read-only finale', () => {
  for (const scenario of [{ gameDone: false, playable: true, expected: false },
    { gameDone: true, playable: true, expected: true }, { gameDone: false, playable: false, expected: true }]) {
    const instance = rebuildFree({ gameDone: scenario.gameDone });
    instance._playable = scenario.playable;
    instance._sawIncomplete = true;
    instance.rebuild();
    assert.equal(!!instance._finCelebrated, scenario.expected, JSON.stringify(scenario));
  }
});

test('FREE summary reports visits and keeps CITY presentation unchanged', () => {
  const label = wxml.match(/{{doneCount}}\/{{total}}<\/text><text class="fst__l">([^<]+)<\/text>/)[1];
  assert.equal(label, '到访完成');
  assert.match(wxml, /mode!=2[\s\S]*?点亮坐标/);
});
