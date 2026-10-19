// BL-103 合作方履约信用摘要(只读)。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const { partnerMemberId, shapeCredit } = require('../../pages/coop/invite-detail/credit-view.js');

test('partnerMemberId:收件箱取 fromId;发件箱仅受邀方是商家才取 toId(club 时 toId 是 clubId)', () => {
  assert.equal(partnerMemberId({ fromId: 12, toId: 34, toType: 'merchant' }, 'received'), 12);
  assert.equal(partnerMemberId({ fromId: 12, toId: 34, toType: 'merchant' }, 'sent'), 34);
  assert.equal(partnerMemberId({ fromId: 12, toId: 34, toType: 'club' }, 'sent'), 0);
  assert.equal(partnerMemberId({ fromId: '0' }, 'received'), 0);
  assert.equal(partnerMemberId({ fromId: 'x' }, 'received'), 0);
  assert.equal(partnerMemberId(null, 'received'), 0);
});

test('shapeCredit:只用 creditSummary 字段;样本为 0(后端兜底 100%)不展示', () => {
  assert.equal(shapeCredit(null), null);
  assert.equal(shapeCredit([]), null);
  assert.equal(shapeCredit({ days: 90, fulfil: 0, violationCount: 0, fulfillmentRate: 100 }), null);
  assert.equal(shapeCredit({ fulfil: 3, violationCount: 1 }), null);
  assert.equal(shapeCredit({ fulfil: 3, violationCount: 1, fulfillmentRate: 120 }), null);
  assert.deepEqual(shapeCredit({ days: 90, fulfil: 19, timeout: 1, cancelOutside: 0, violateInside: 0, violationCount: 1, fulfillmentRate: 95 }),
    { label: '对方履约(近90天)', text: '履约率 95% · 违约 1 次' });
});

test('详情页接线:POST /api/coop/credit 带对方 memberId,静默失败,只在条款卡加一行 kv', () => {
  const js = fs.readFileSync(path.join(root, 'pages/coop/invite-detail/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/coop\/credit', method: 'POST',\s*data: JSON\.stringify\(\{ memberId: memberId \}\)/);
  assert.match(js, /this\.loadPartnerCredit\(inv\);/);
  assert.doesNotMatch(js.slice(js.indexOf('loadPartnerCredit(inv) {')), /^\s*success\(res\) \{[^}]*this\./m);
  const wxml = fs.readFileSync(path.join(root, 'pages/coop/invite-detail/index.wxml'), 'utf8');
  assert.match(wxml, /<view wx:if="\{\{partnerCredit\}\}" class="kv"><text class="k">\{\{partnerCredit\.label\}\}<\/text><text class="v">\{\{partnerCredit\.text\}\}<\/text><\/view>/);
});
