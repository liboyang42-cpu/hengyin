// 节点配置页读写 v5.1 玩法的端到端契约(2026-08-27)。
//
// advanced-game-config-v51.test.js 钉的是纯函数层;这一条钉的是**用户真正走的那条路**:
// 从货架进 /pages/publish/temp/index?id=xxx → 页面回填 → 改个无关字段 → 存。
// 修复前这一路会把 blindTaste 整段抹掉(parse 不认 → serialize 不写),
// 商家看到的现象是「盲品玩法自己没了,节点变成纯打卡」。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/temp/index.js';

let pageConfig = null;
let imageCallback;
let navigationColor;

const testApp = {
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: () => {},
  getAuthorization: () => 'Bearer test',
  getUserID: () => 101,
  getUserInfo: () => null,
  getToken: () => '',
  chooseImage: callback => { imageCallback = callback; },
  tips: () => {},
};
global.getApp = () => testApp;
global.wx = {
  setNavigationBarColor: value => { navigationColor = value; },
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  showToast: () => {},
  navigateBack: () => {},
  getBackgroundAudioManager: () => ({ stop: () => {} }),
};
global.Page = (config) => { pageConfig = config; };

function loadPage() {
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = function (patch, callback) {
    // 只需要支持本用例用到的形状:顶层字段 + 'a.b.c' 动态路径
    for (const [key, value] of Object.entries(patch)) {
      if (!key.includes('.')) { this.data[key] = value; continue; }
      const parts = key.split('.');
      let cursor = this.data;
      for (const part of parts.slice(0, -1)) cursor = cursor[part];
      cursor[parts[parts.length - 1]] = value;
    }
    if (callback) callback();
  };
  page._refreshOutcomeContract = () => ({ items: [], errors: [] });
  page.refreshPreviewState = () => {};
  page.hasModule = () => false;
  return page;
}
test('发布自己的模板带原 id 与清空意图，新建和公共复制不带 id', () => {
  const page = loadPage();
  page.validateForm = () => true;
  page.prepareFormData = () => ({ id: 999, title: '玩法', clearFields: ['merchantGuide'] });
  const sent = [];
  page.sendData = (body, url) => sent.push({ body, url });
  page._templateId = 45; page.data.isMyScope = true;
  page.publishGame();
  assert.equal(sent[0].url, '/api/template/publish');
  assert.equal(sent[0].body.id, 45);
  assert.deepEqual(sent[0].body.clearFields, ['merchantGuide']);
  page.data.isMyScope = false; page.publishGame();
  assert.equal(Object.hasOwn(sent[1].body, 'id'), false);
  page._templateId = 0; page.publishGame();
  assert.equal(Object.hasOwn(sent[2].body, 'id'), false);
  page.data.from = 'fabu'; page._boundDraftId = 46; page.publishGame();
  assert.equal(sent[3].url, '/api/template/draft');assert.equal(sent[3].body.id, 46);
});

beforeEach(() => { pageConfig = null; });

// 与 migration_seed_merchant_game_v51_20260827.sql 的「闭眼味觉师」同形
const BLIND_TASTE = {
  enabled: true, title: '闭上眼 先尝再猜', steps: '① 领小样 ② 闭眼尝 ③ 睁眼作答',
  hint: '别偷看 舌头比眼睛诚实', xp: 15,
  options: [{ key: 'A', label: '桂花乌龙' }, { key: 'B', label: '茉莉雪芽' },
    { key: 'C', label: '蜜桃红茶' }, { key: 'D', label: '陈皮白茶' }],
  answerKey: 'B',
};

function loadTemplate(page, advancedConfigJson) {
  page.fillFormWithTemplateData({
    id: 307, title: '闭眼味觉师', validationMethod: 0, status: 1,
    advancedConfigJson,
  });
}

test('从货架采用的 v5.1 模板：回填→存盘，玩法配置一字不掉', () => {
  const page = loadPage();
  loadTemplate(page, JSON.stringify({ schemaVersion: 1, blindTaste: BLIND_TASTE }));

  assert.equal(page.data.advancedConfigError, '', '既有模板不该读出错误');
  assert.equal(page.data.advanced.blindTaste.answerKey, 'B', '答案必须回填出来');
  assert.equal(page.data.advanced.blindTaste.options.length, 4);

  const saved = page.prepareFormData().advancedConfigJson;
  assert.notEqual(saved, '', '★ 只配了盲品也是配了 —— 存回去不能是空串(修复前正是这里把玩法删掉的)');
  assert.deepEqual(JSON.parse(saved).blindTaste, BLIND_TASTE);
});

test('在页面上改盲品选项，答案跟着选项走，不留悬空 answerKey', () => {
  const page = loadPage();
  loadTemplate(page, JSON.stringify({ schemaVersion: 1, blindTaste: BLIND_TASTE }));

  // 把答案项(B)的 key 改成 B2 —— answerKey 必须跟着改
  page.updateBlindOption({ currentTarget: { dataset: { index: 1, field: 'key' } }, detail: { value: 'B2' } });
  assert.equal(page.data.advanced.blindTaste.answerKey, 'B2');

  // 删掉答案项 —— 答案落到剩下的第一项,而不是指向一个不存在的选项
  page.removeBlindOption({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(page.data.advanced.blindTaste.answerKey, 'A');
  assert.equal(JSON.parse(page.prepareFormData().advancedConfigJson).blindTaste.answerKey, 'A');
});

test('签文池的换行草稿与落库的二维数组保持一致', () => {
  const page = loadPage();
  loadTemplate(page, JSON.stringify({
    schemaVersion: 1,
    dailySign: { enabled: true, signer: '猫向导', sealText: '城瘾', poems: [['第一条', '第二行']] },
  }));
  assert.deepEqual(page.data.dailySignDrafts, ['第一条\n第二行'], '回填时草稿必须由签文重建');

  page.addDailyPoem();
  page.updateDailyPoem({ currentTarget: { dataset: { index: 1 } }, detail: { value: '新签第一行\n新签第二行\n' } });

  const saved = JSON.parse(page.prepareFormData().advancedConfigJson).dailySign;
  assert.deepEqual(saved.poems, [['第一条', '第二行'], ['新签第一行', '新签第二行']],
    '末尾空行要清掉，其余原样落库');
});

test('本模块不认识的将来玩法段，过一遍配置页也还在', () => {
  const page = loadPage();
  loadTemplate(page, '{"schemaVersion":1,"woodFish":{"enabled":true,"taps":108}}');
  const saved = page.prepareFormData().advancedConfigJson;
  assert.deepEqual(JSON.parse(saved).woodFish, { enabled: true, taps: 108 });
});

test('配置有错时保留原文待修，不静默丢弃用户填的内容', () => {
  const page = loadPage();
  loadTemplate(page, JSON.stringify({
    schemaVersion: 1,
    blindTaste: Object.assign({}, BLIND_TASTE, { answerKey: 'Z' }),  // 答案不在选项里
  }));
  const saved = page.prepareFormData().advancedConfigJson;
  assert.match(JSON.parse(saved).blindTaste.answerKey, /Z/, '错误配置原样带回，交给校验拦下并让用户改');
});


test('故事玩法预览按编辑值运行，头像、留言和照片不上传或写正式数据', () => {
  const page = loadPage();
  const calls = [];
  const app = getApp();
  app.sendRequest = request => calls.push(request);
  const event = key => ({ currentTarget: { dataset: { key } } });
  const action = (action, detail = {}) => page.onPvKitAction({ detail: { action, detail } });
  for (const key of ['profile', 'typeIn', 'photoCheck', 'note']) {
    const before = JSON.stringify(page.data.advanced);
    page.previewGameFromSheet(event(key));
    assert.equal(page.data.previewVisible, true);
    assert.equal(navigationColor.frontColor, '#ffffff');
    assert.equal(JSON.stringify(page.data.advanced), before);
    page.closePreview();
    assert.equal(navigationColor.frontColor, '#000000');
    if (key === 'typeIn') {
      // R5 新选输入挑战禁用；存量模板仍须保留并可本地预览。
      page.fillFormWithTemplateData({ title: '存量输入挑战', validationMethod: 0, advancedConfigJson: JSON.stringify({
        schemaVersion: 1, typeIn: { enabled: true, title: '旧输入挑战', target: '正确答案', seconds: 30 },
      }) });
    } else page.pickGame(event(key));
    page.data.advanced[key].title = '现在编辑的内容';
    page.tryCurrentGame();
    assert.equal(page.data.pvKit.title, '现在编辑的内容');
    if (key === 'profile') {
      action('avatar', { tempFilePath: 'wxfile://avatar' });
      action('submit', { answers: { name: '旅人' } });
      assert.equal(page.data.pvKit.avatarUrl, 'wxfile://avatar');
      assert.equal(page.data.pvKit.done, true);
    } else if (key === 'note') {
      action('submit', { text: '你好' });
      assert.equal(page.data.pvKit.mine, '你好');
      assert.deepEqual(page.data.pvKit.previous, []);
    } else if (key === 'photoCheck') {
      action('shoot', { tempFilePath: 'wxfile://shot' });
      assert.equal(page.data.pvKit.lastUrl, 'wxfile://shot');
      assert.equal(page.data.pvKit.passed, false);
    } else {
      action('submit', { text: '不能跳过开始' });
      assert.equal(page.data.pvKit.attempts, 0);
      action('start'); action('submit', { text: '错误' });
      assert.equal(page.data.pvKit.passed, false);
      assert.equal(page.data.pvKit.attempts, 1);
      action('start'); action('submit', { text: page.data.pvKit.target });
      assert.equal(page.data.pvKit.passed, true);
    }
    page.closePreview(); page.tryCurrentGame();
    assert.equal(page.data.pvKit.done || page.data.pvKit.passed || false, false);
  }
  assert.deepEqual(calls, []);
});

test('相册模板页面：增改删、保存回填、复制互不串数据', () => {
  const page = loadPage();
  page.fillFormWithTemplateData({ title: '梦', advancedConfigJson: JSON.stringify({ album: { enabled: true, images: [{ url: 'https://example.com/old.jpg', line: '旧配文' }] } }) });
  const event = index => ({ currentTarget: { dataset: index == null ? {} : { index } } });
  page.chooseAlbumImage(event()); imageCallback(['https://example.com/new.jpg']);
  page.onAlbumLine({ ...event(1), detail: { value: '第二张' } });
  page.chooseAlbumImage(event(0)); imageCallback(['https://example.com/replaced.jpg']);
  page.data.formData.title = '童年回忆';
  const saved = page.prepareFormData();
  const copy = loadPage(); copy.fillFormWithTemplateData(saved);
  assert.equal(copy.data.formData.title, '童年回忆');
  assert.deepEqual(copy.data.advanced.album.images, [
    { url: 'https://example.com/replaced.jpg', line: '旧配文' },
    { url: 'https://example.com/new.jpg', line: '第二张' },
  ]);
  copy.removeAlbumImage(event(0));
  assert.equal(copy.data.advanced.album.images[0].line, '第二张');
  assert.equal(page.data.advanced.album.images.length, 2);
  assert.equal(JSON.parse(saved.advancedConfigJson).album.images.length, 2);
});

test('相册上传：取消、失败及切换模板后的迟到回调都不覆盖旧图', () => {
  const page = loadPage();
  const original = { album: { enabled: true, images: [{ url: 'https://example.com/old.jpg', line: '保留' }] } };
  page.fillFormWithTemplateData({ title: '梦', advancedConfigJson: JSON.stringify(original) });
  const event = { currentTarget: { dataset: { index: 0 } } };
  for (const result of [undefined, []]) {
    page.chooseAlbumImage(event); imageCallback(result);
    assert.deepEqual(page.data.advanced.album.images, original.album.images);
  }
  page.chooseAlbumImage(event);
  const stale = imageCallback;
  page.fillFormWithTemplateData({ title: '另一本', advancedConfigJson: JSON.stringify(original) });
  stale(['https://example.com/wrong.jpg']);
  assert.deepEqual(page.data.advanced.album.images, original.album.images);
});
