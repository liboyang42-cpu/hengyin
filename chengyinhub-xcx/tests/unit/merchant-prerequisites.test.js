// BL-85 商家开通前置检查(只读)纯函数单测:字段对照 MerchantPrerequisiteServiceImpl.calculate。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pendingPrerequisites } = require('../../pages/merchant/utils/merchant-prerequisites.js');

const ROOT = path.resolve(__dirname, '../..');

function item(code, status, actionTarget) {
  return { code, title: code + '标题', description: code + '说明', status, severity: status === 'PASS' ? 'INFO' : 'BLOCKING', actionTarget };
}

test('全部完成或无数据时不展示', () => {
  assert.deepEqual(pendingPrerequisites(null), []);
  assert.deepEqual(pendingPrerequisites([]), []);
  assert.deepEqual(pendingPrerequisites({ canContinue: true, items: [item('A', 'BLOCKED')] }), []);
  assert.deepEqual(pendingPrerequisites({ canContinue: false }), []);
});

test('只列未通过项,去处只放行已知小程序页', () => {
  const out = pendingPrerequisites({
    canContinue: false,
    items: [
      item('MERCHANT_APPROVED', 'PASS', { type: 'MINIPROGRAM_PAGE', path: '/pages/merchant/apply/index', available: true }),
      item('ACCOUNT_ACTIVE', 'BLOCKED', { type: 'MINIPROGRAM_PAGE', path: null, available: false, unavailableReason: 'NO_EXISTING_RECOVERY_PAGE' }),
      item('PROFILE_LOGO', 'BLOCKED', { type: 'MINIPROGRAM_PAGE', path: '/pages/merchant/decor/index', available: true }),
      item('EVIL', 'BLOCKED', { type: 'MINIPROGRAM_PAGE', path: '/pages/other/index', available: true }),
    ],
  });
  assert.deepEqual(out.map(r => r.key), ['ACCOUNT_ACTIVE', 'PROFILE_LOGO', 'EVIL']);
  assert.deepEqual(out.map(r => r.path), ['', '/pages/merchant/decor/index', '']);
  assert.equal(out[1].description, 'PROFILE_LOGO说明');
});

test('工作台用 GET 读前置检查,且只在有资料写权限时请求', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/merchant\/ops\/prerequisites', method: 'GET'/);
  assert.match(js, /if \(currentAccess\.canWriteProfile\) this\.loadPrerequisites\(dataEpoch\);/);
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{!consoleStatus && prerequisites\.length\}\}"/);
});
