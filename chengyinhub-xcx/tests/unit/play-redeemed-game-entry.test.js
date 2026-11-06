'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const test = require('node:test');

const file = path.resolve(__dirname, '../../pages/play/index.js');
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
    console, setTimeout, clearTimeout, setInterval, clearInterval,
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
