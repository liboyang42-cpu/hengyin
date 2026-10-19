const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('F0431 授权读失败清除旧授权，通知结果独立展示未知', () => {
  let page, setting;
  vm.runInNewContext(read('pages/merchant/ledger/index.js'), {
    Page: value => { page = value; }, getApp: () => ({}),
    require: name => name.includes('subscribe') ? { TEMPLATES: { coopSettle: 'template' } } : {},
    wx: { getSetting: value => { setting = value; } },
  });
  page.setData = patch => Object.assign(page.data, patch);
  page.data.settleAlertsEnabled = true;
  page.syncCoopSettleAlertSetting();
  assert.equal(page.data.settleAlertsEnabled, false);
  assert.equal(page.data.settleAlertsState, 'unknown');
  setting.success({ subscriptionsSetting: { mainSwitch: true, itemSettings: { template: 'accept' } } });
  assert.equal(page.data.settleAlertsEnabled, true);
  setting.success({ subscriptionsSetting: { mainSwitch: false, itemSettings: { template: 'accept' } } });
  assert.equal(page.data.settleAlertsEnabled, false);
  const wxml = read('pages/merchant/ledger/index.wxml');
  assert.match(wxml, /授权不代表已发送/);
  assert.match(wxml, /通知结果未确认/);
});


test('F0219 已接受不等于履约，重复和网络错误回读原关系', () => {
  assert.match(read('pages/coop/invite-detail/index.wxml'), /合作已接受 · 履约未核验 · 评价身份/);
  const js = read('pages/coop/invite-detail/index.js');
  assert.match(js, /Number\(inv.status\) !== 1/);
  assert.match(js, /fail\(\) \{\s*that.loadMyReview\(that.data.invite\)/);
});


test('F0469 普通意见不推定已体验，奖励不作无条件承诺', () => {
  const wxml = read('pages/topic/index/index.wxml');
  assert.match(wxml, /条普通意见/);
  assert.match(wxml, /普通意见 · 体验未核验/);
  assert.match(wxml, /奖励以实际积分记录为准/);
  assert.doesNotMatch(wxml, /玩过之后回来写一条，可以获得平台积分/);
});


test('F0148 筛选范围、公开评分与管理未审计数不混称', () => {
  const wxml = read('pages/merchant/reviews/index.wxml');
  assert.match(wxml, /筛选仅针对已加载的/);
  assert.match(wxml, /含待复核/);
  assert.match(wxml, /已加载内容中没有匹配评价，请继续加载/);
  assert.doesNotMatch(wxml, /近 30 天均分/);
});


test('F0206 新邀约拟议条款不冒充已冻结合同', () => {
  const wxml = read('pages/coop/invite/index.wxml');
  assert.match(wxml, /当前身份的可选方式尚未确认/);
  assert.match(wxml, /不变更已成立合作、已开售或已结算部分/);
  assert.match(wxml, /资源来源：所选主题与合作方申报供给/);
  assert.doesNotMatch(wxml, /合作条款 · 已锁定/);
});

test('F0258 编辑署名使用 memberId 回填，空名单不残留创建者', () => {
  const js = read('pages/publish/activity/index.js');
  let page;
  vm.runInNewContext(js, {
    Page: value => { page = value; }, getApp: () => ({}),
    require: name => name.includes('step-form') ? require('../../pages/publish/utils/publish/step-form.js')
      : (name.includes('time-picker-options') ? require('../../utils/time-picker-options.js') : {}), wx: {},
  });
  page.setData = patch => { page.patch = patch; };
  page.applyExistingActivity({ collaboratorsList: [
    { id: 901, memberId: 21, memberNickname: 'A' },
    { id: 902, memberId: 22, memberNickname: 'B' },
  ] });
  assert.equal(JSON.stringify(page.patch['formData.collaborators']), '[21,22]');
  page.applyExistingActivity({ collaboratorsList: [] });
  assert.equal(JSON.stringify(page.patch['formData.collaborators']), '[]');
});
