const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('../helpers/ui-sandbox-vm.js'); // node:vm + 沙箱里装真 utils/toast.js·loading.js

const ROOT = path.resolve(__dirname, '../..');

function read(relative) {
  return fs.readFileSync(path.join(ROOT, relative), 'utf8');
}

function mountMarketingConsent() {
  const requests = [];
  let definition;
  const app = {
    sendRequest(options) { requests.push(options); },
    getRequestErrorMessage(res, fallback) { return (res && res.msg) || fallback; },
  };
  const sandbox = {
    getApp: () => app,
    Component(value) { definition = value; },
    wx: { showToast() {} },
    Date,
    Math,
    Number,
    String,
    JSON,
    Object,
    Array,
  };
  vm.runInNewContext(read('pages/shezhi/components/marketing-consent/index.js'), sandbox);
  const component = Object.assign({
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); },
  }, definition.methods);
  return { component, requests };
}


test('营销同意保存失败后的回读不会清掉失败提示', () => {
  const { component, requests } = mountMarketingConsent();
  component.setData({
    state: 'ready',
    rows: [{ merchantRowId: 1, merchantOwnerMemberId: 7, merchantName: '山风咖啡', couponOptedIn: true }],
  });

  component.onConsentChange({
    currentTarget: { dataset: { row: '1', owner: '7', channel: 'COUPON' } },
    detail: { value: false },
  });
  assert.equal(requests[0].method, 'POST');
  assert.equal(JSON.parse(requests[0].data).merchantRowId, 1,
    '营销同意必须绑定服务端返回的精确 merchant row');
  requests[0].success({ code: 409, msg: '退订状态保存失败' });

  assert.equal(requests[1].method, 'GET', '保存失败后应刷新服务端真值');
  assert.equal(component.data.error, '退订状态保存失败', '刷新开始时失败提示仍应可见');
  requests[1].success({ code: 200, data: [{
    merchantRowId: 1,
    merchantOwnerMemberId: 7,
    merchantName: '山风咖啡',
    couponOptedIn: true,
  }] });
  assert.equal(component.data.error, '退订状态保存失败', '真值刷新完成后仍应保留本次保存失败提示');
});

test('客户导出与合规触达使用权限门控的等层工具且避开原生按钮底色', () => {
  const wxml = read('pages/merchant/customer/index.wxml');
  const wxss = read('pages/merchant/customer/index.wxss');

  assert.match(wxml,
    /<view[^>]+wx:if="\{\{canExport\}\}"[^>]+class="tm-row"[^>]+bindtap="createExport"[^>]+aria-role="button"/,
    '导出必须只在授权态渲染并保留按钮语义');
  assert.doesNotMatch(wxml, /<button[^>]+bindtap="createExport"/,
    '授权工具不得再受原生 button 白底覆盖');
  // 2026-10-27 业主 M-04 推翻 CU-M-101/121 的「两列等宽工具网格」:客户管理半屏改成标准操作列表,
  // 每行 图标 + 标题 + 说明。原判据里仍成立的部分保留:同层动作不分黑底主操作档、
  // 行走卡面正文色、说明单行不折行。负控 = 换回网格工具块,下面必红。
  assert.doesNotMatch(wxml, /class="cu-tool\b/, '客户管理不再是浮在空白里的文字工具块');
  // 2026-10-28 m679/m694 业主:Threads 式分组菜单 —— 文字在左、图标在右,不再写说明行
  for (const handler of ['toggleSelectMode', 'createExport', 'goCampaigns']) {
    assert.match(wxml, new RegExp(`class="tm-row"[^>]+bindtap="${handler}"[\\s\\S]*?<text class="tm-label">[\\s\\S]*?<cy-icon class="tm-ico"`),
      `${handler}:操作行必须是 文字 + 右侧图标`);
  }
  assert.doesNotMatch(wxml, /class="cu-op-desc"/, '不写说明行');
  assert.match(read('style/threads-menu.wxss'), /\.tm-row \{[^}]*display: flex;[^}]*justify-content: space-between/);
});

test('客户页与横向分段把导航内距计入自身宽高，不能撑出整页滚动条', () => {
  const wxss = read('pages/merchant/customer/index.wxss');
  const pageRule = wxss.match(/\.cu-page\s*\{[^}]*\}/);
  const segmentsRule = wxss.match(/\.cu-segs\s*\{[^}]*\}/);
  assert.ok(pageRule && segmentsRule, '客户页布局规则缺失');
  assert.match(pageRule[0], /box-sizing:\s*border-box/,
    '100vh 页面顶部导航 padding 必须计入高度');
  assert.match(pageRule[0], /width:\s*100%/);
  assert.match(segmentsRule[0], /box-sizing:\s*border-box/,
    '满宽横向 scroll-view 的左右 padding 必须计入自身宽度');
  assert.match(segmentsRule[0], /width:\s*100%/);
});

test('客户实付金额被服务端脱敏时明确显示无查看权限', () => {
  const viewModel = require('../../pages/merchant/customer/detail/view-model');
  const shaped = viewModel.shapeCustomerDetail({
    summary: {
      customerMemberId: 41,
      displayName: '林青',
      arrivedCount: 2,
      pendingCount: 1,
      refundedCount: 0,
      paidAmount: null,
    },
    systemTags: [],
    merchantTags: [],
    timeline: [],
  }, 41);

  assert.equal(shaped.summary.paidAmount, null);
  assert.equal(shaped.summary.paidAmountText, '无查看权限');
  assert.notEqual(shaped.summary.paidAmountText, '金额待确认');
});

// 2026-09-09 用户裁决:会费整条暂时不做,pages/club/membership/setting 整页删除,
// 这条深链权限合同随页一并撤。其余四条与会费无关,原样留下。
