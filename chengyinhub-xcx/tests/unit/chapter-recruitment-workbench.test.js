const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: () => {},
  chooseImage: () => {},
  getUserID: () => 0,
  getUserInfo: () => null,
  getToken: () => '',
});
global.wx = {
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: () => {}, showLoading: () => {}, hideLoading: () => {},
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {}, nextTick: (fn) => fn(),
};

let pageConfig = null;
global.Page = (config) => { pageConfig = config; };

beforeEach(() => {
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach((key) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = page.data;
      for (let i = 0; i < parts.length - 1; i++) target = target[parts[i]];
      target[parts[parts.length - 1]] = patch[key];
    });
    if (callback) callback();
  };
  return page;
}

test('自由探索章节承接：开启后要求品类，并原样进入发布 payload', () => {
  const page = makePage();
  page.data.formData.productType = 2;
  page.data.merchantCategoryList = [{ id: 18, categoryName: '咖啡' }];
  page.data.formData.chapters = [{
    name: '夜航咖啡线', description: '夜间路线', nodes: [{ name: '站点' }],
    recruitEnabled: 0, categoryId: null, termsMode: 'PERK', maxMerchant: 0
  }];

  page.onChapterRecruitToggle({ currentTarget: { dataset: { index: 0 } }, detail: { value: true } });
  assert.equal(page.data.formData.chapters[0].recruitEnabled, 1);

  page.onChapterRecruitToggle({ currentTarget: { dataset: { index: 0 } }, detail: { value: false } });
  assert.equal(page.data.formData.chapters[0].recruitEnabled, 0, '开关必须能关回去，不能只验开启一次');
  page.onChapterRecruitToggle({ currentTarget: { dataset: { index: 0 } }, detail: { value: true } });

  let bag = page._buildValidationBag();
  assert.equal(bag.errors.chapterRecruitCategory0, '第1章请选择适合商家品类');
  // 2026-08-10 平铺:getFirstErrorTab 随步骤一起退役(没有 tab 可跳了)。它保护的语义
  // ——「商家承接类错误要定位到商家承接区」—— 由下面这条锚点断言原样接住。
  assert.equal(page._anchorForErrorKey('chapterRecruitCategory0'), 'chapterRecruitSection');

  page.onChapterMerchantCategory({ currentTarget: { dataset: { index: 0 } }, detail: { value: 0 } });
  page.onChapterTermsMode({ currentTarget: { dataset: { index: 0, mode: 'TRAFFIC' } } });
  page.onChapterMaxMerchant({ currentTarget: { dataset: { index: 0 } }, detail: { value: '3' } });

  bag = page._buildValidationBag();
  assert.equal(bag.errors.chapterRecruitCategory0, undefined);
  assert.equal(page.data.formData.chapters[0].categoryId, 18);
  assert.equal(page.data.formData.chapters[0].category, '咖啡');
  assert.equal(page.data.formData.chapters[0].termsMode, 'TRAFFIC');
  assert.equal(page.data.formData.chapters[0].maxMerchant, 3);

  let payload = null;
  page.sendData = (data) => { payload = data; };
  page._doSubmit();
  assert.equal(payload.chapters[0].recruitEnabled, 1);
  assert.equal(payload.chapters[0].categoryId, 18);
  assert.equal(payload.chapters[0].termsMode, 'TRAFFIC');
  assert.equal(payload.chapters[0].maxMerchant, 3);
});

test('权益招商可选配置最低价值，切到引流时清空且零值不通过校验', () => {
  const page = makePage();
  page.data.formData.productType = 2;
  page.data.merchantCategoryList = [{ id: 18, categoryName: '咖啡' }];
  page.data.formData.chapters = [{
    name: '夜航咖啡线', description: '夜间路线', nodes: [{ name: '站点' }],
    recruitEnabled: 1, categoryId: 18, termsMode: 'PERK', perkMinValue: null, maxMerchant: 0
  }];

  page.onChapterPerkMinValue({ currentTarget: { dataset: { index: 0 } }, detail: { value: '50.5' } });
  assert.equal(page.data.formData.chapters[0].perkMinValue, '50.5');

  let payload = null;
  page.sendData = (data) => { payload = data; };
  page._doSubmit();
  assert.equal(payload.chapters[0].perkMinValue, '50.5');

  page.onChapterTermsMode({ currentTarget: { dataset: { index: 0, mode: 'TRAFFIC' } } });
  assert.equal(page.data.formData.chapters[0].perkMinValue, null);

  page.data.formData.chapters[0].termsMode = 'PERK';
  page.data.formData.chapters[0].perkMinValue = '0';
  const bag = page._buildValidationBag();
  assert.equal(bag.errors.chapterRecruitPerkMin0, '第1章权益最低价值须为大于0的金额');
});

test('俱乐部计酬章节重新编辑与招商开关保留档位和约定上限', () => {
  const page = makePage();
  page.data.formData.productType = 2;
  page.data.formData.clubId = 990005;
  page.data.formData.chapters = [{ name: '计酬章', recruitEnabled: 1, categoryId: 18,
    termsMode: 'REVSHARE', maxPerHeadFee: '0.01', maxMerchant: 1, nodes: [] }];
  page.showEditChapter({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(page.data.chapterForm.termsMode, 'REVSHARE');
  assert.equal(Number(page.data.chapterForm.maxPerHeadFee), 0.01);
  page.onChapterRecruitToggle({ currentTarget: { dataset: { index: 0 } }, detail: { value: false } });
  page.onChapterRecruitToggle({ currentTarget: { dataset: { index: 0 } }, detail: { value: true } });
  assert.equal(page.data.formData.chapters[0].termsMode, 'REVSHARE');
  assert.equal(Number(page.data.formData.chapters[0].maxPerHeadFee), 0.01);
});

test('计酬上限缺失和超两位小数阻止发布，合法上限进入保存数据', () => {
  const page = makePage();
  page.data.formData.productType = 2;
  page.data.formData.clubId = 990005;
  page.data.formData.chapters = [{ name: '计酬章', recruitEnabled: 1, categoryId: 18,
    termsMode: 'REVSHARE', maxPerHeadFee: null, maxMerchant: 1, nodes: [] }];
  assert.ok(page._buildValidationBag().errors.chapterRecruitFee0);
  page.data.formData.chapters[0].maxPerHeadFee = '0.001';
  assert.ok(page._buildValidationBag().errors.chapterRecruitFee0);
  page.data.formData.chapters[0].maxPerHeadFee = '0.01';
  assert.equal(page._buildValidationBag().errors.chapterRecruitFee0, undefined);
  assert.equal(page._buildValidationBag().errors.chapterRecruitTerms0, undefined);
  let payload;
  page.sendData = (data) => { payload = data; };
  page._doSubmit();
  assert.equal(payload.chapters[0].termsMode, 'REVSHARE');
  assert.equal(Number(payload.chapters[0].maxPerHeadFee), 0.01);
});

test('个人内容不能选择俱乐部计酬，切回权益不遗留计酬上限', () => {
  const page = makePage();
  page.data.formData.productType = 2;
  page.data.formData.clubId = '';
  page.data.chapterForm = { recruitEnabled: 1, categoryId: 18, termsMode: 'PERK', maxPerHeadFee: null };
  page.onChapterTermsModeChange({ currentTarget: { dataset: { mode: 'REVSHARE' } } });
  assert.equal(page.data.chapterForm.termsMode, 'PERK');
  page.data.formData.clubId = 990005;
  page.onChapterTermsModeChange({ currentTarget: { dataset: { mode: 'REVSHARE' } } });
  assert.equal(page.data.chapterForm.termsMode, 'REVSHARE');
  page.onChapterMaxPerHeadFeeInput({ detail: { value: '0.01' } });
  assert.equal(page.data.chapterForm.maxPerHeadFee, '0.01');
  page.onChapterTermsModeChange({ currentTarget: { dataset: { mode: 'PERK' } } });
  assert.equal(page.data.chapterForm.maxPerHeadFee, null);
});
