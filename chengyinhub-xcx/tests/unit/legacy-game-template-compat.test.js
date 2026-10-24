const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
const outcomes = require('../../pages/publish/utils/publish/node-outcome-contract.js');
const { gameTemplateCover } = require('../../utils/template-display.js');
const detail = require('../../pages/templatedetail/utils/templateDetail.js');
const root = path.resolve(__dirname, '../../..');
const editorWxml = fs.readFileSync(path.join(root, 'chengyinhub-xcx/pages/publish/temp/index.wxml'), 'utf8');

test('42款真实种子：加载、结果合同、计时、保存再打开均不丢配置', () => {
  let count = 0;
  for (const file of ['migration_seed_merchant_game_a8_20260823.sql',
    'migration_seed_merchant_game_b27_20260823.sql', 'migration_seed_merchant_game_v51_20260827.sql']) {
    const sql = fs.readFileSync(path.join(root, 'chengyinhub-admin/sql', file), 'utf8');
    const pattern = /VALUES \('([a-z0-9_]+)', '(\{[\s\S]*?\})'(?:, (?:NULL|'[\s\S]*?'))?\);/g;
    for (const match of sql.matchAll(pattern)) {
      const source = JSON.parse(match[2].replace(/''/g, "'"));
      const loaded = cfg.parse(JSON.stringify(source));
      assert.equal(loaded.error, '', match[1]);
      assert.equal(cfg.validate(loaded.value), '', match[1]);
      assert.deepEqual(outcomes.extract({ advancedConfigJson: loaded.value }).errors, [], match[1]);
      catalog.dropStaleTimer(loaded.value, catalog.detectGame(loaded.value));
      const game = catalog.findGame(catalog.detectGame(loaded.value));
      for (const [section, label] of [['random', '这副卡叫什么'], ['branch', '起点步骤 ID']]) {
        if (!source[section] || !source[section].enabled) continue;
        const gate = section === 'branch'
          ? editorWxml.match(/<view class="cg-gcfg" wx:if="{{([^}]+)}}">\s*<view class="cg-adv-body">\s*<view class="cg-r5-sec">[^<]*分支剧情（旧设置）[^<]*<\/view>/)
          : editorWxml.match(new RegExp('<view class="cg-gcfg" wx:if="{{([^}]+)}}">\\s*<view class="cg-adv-body">\\s*<view class="cg-cfg-label">' + label));
        assert.ok(gate, section + ' 配置面板必须存在');
        assert.equal(new Function('advanced', 'gameSection', 'r5Editor', 'return (' + gate[1] + ')')(loaded.value, game ? game.section : '', { nextDisabledReason: '暂未开放' }), true,
          match[1] + ':' + section + ' 已启用的旧配置不得被另一主玩法隐藏');
      }
      if (source.timer && source.timer.enabled) assert.equal(loaded.value.timer.enabled, true, match[1]);
      const saved = cfg.parse(cfg.serialize(loaded.value));
      for (const section of cfg.enabledSections(source)) {
        assert.equal(saved.value[section].enabled, true, match[1] + ':' + section);
        for (const list of ['items', 'steps', 'roles', 'turnOrder', 'poems']) {
          if (source[section][list]) assert.deepEqual(saved.value[section][list], loaded.value[section][list]);
        }
      }
      count++;
    }
  }
  assert.equal(count, 42, '必须完整提取，而非用空列表冒充通过');
});

test('旧完成方式与分支/抽卡并存时仍可编辑，现代问答不重复显示旧表单', () => {
  for (const method of [1, 2, 3, 6]) {
    const gate = editorWxml.match(new RegExp('<cy-card[^>]+wx:if="{{([^}]*formData.validationMethod === ' + method + '[^}]*)}}"'));
    assert.ok(gate);
    const visible = new Function('advanced', 'formData', 'gameKey', 'return (' + gate[1] + ')');
    assert.equal(visible({ qa: { enabled: false } }, { validationMethod: method }, 'branch'), true);
    assert.equal(visible({ qa: { enabled: true } }, { validationMethod: method }, 'qaPick'), false);
  }
});

test('早期多结局缺结果码：按步骤 ID 补齐、显式重复和空码仍拒绝', () => {
  const raw = { schemaVersion: 1, branch: { enabled: true, startStepId: 'start', steps: [
    { id: 'start', terminal: false, options: [{ id: 'a', label: '左', nextStepId: 'left' },
      { id: 'b', label: '右', nextStepId: 'right' }] },
    { id: 'left', terminal: true, options: [] }, { id: 'right', terminal: true, options: [] }
  ] } };
  const model = cfg.parse(JSON.stringify(raw)).value;
  assert.equal(cfg.validate(model), '');
  assert.deepEqual(model.branch.steps.slice(1).map(s => s.outcomeCode), ['left', 'right']);
  assert.deepEqual(outcomes.extract({ advancedConfigJson: model }).errors, []);
  model.branch.steps[2].outcomeCode = 'left';
  assert.match(cfg.validate(model), /不能重复/);
  model.branch.steps[2].outcomeCode = '';
  assert.notEqual(cfg.validate(cfg.parse(JSON.stringify(model)).value), '');
});

test('七款封面在包内且详情共用；作者封面不替换，源记录不改写', () => {
  for (const title of ['闭眼味觉师', '沉默点单', '便利店调酒师', '治愈音乐角', '午夜电台', '低碳行动', '今日城市签']) {
    const source = { title, imgUrl: '' };
    const cover = gameTemplateCover(source);
    assert.ok(fs.existsSync(path.join(root, 'chengyinhub-xcx', cover)));
    assert.equal(detail.normalizeTemplateDetail(source).coverUrl, cover);
    assert.equal(source.imgUrl, '', '显示补图不得将客户端路径写进业务数据');
    assert.equal(gameTemplateCover({ title, imgUrl: 'https://www.chengyinhub.com/author.jpg' }), 'https://www.chengyinhub.com/author.jpg');
  }
  assert.equal(gameTemplateCover({ title: '非官方同名以外的玩法' }), '');
  assert.match(gameTemplateCover({ title: '我改名的签文', advancedConfigJson: '{"dailySign":{"enabled":true}}' }), /daily-city-sign.svg$/);
  assert.equal(gameTemplateCover({ advancedConfigJson: '{broken' }), '');
});
