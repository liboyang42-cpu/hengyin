const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { buildValidationBag } = require('../../pages/publish/utils/publish/publish-validation-bag.js');

// 第十轮 f2 第 4 项(j2 F-02 / F-01c):城市定向开了商家承接,编辑器必须让作者选招商截止并上送。
// 没有它,后端立项后直接进定价(锁价),合作邀约被拒,开售审核也拒「开放承接…必须有招商截止日期」。

const ROOT = path.join(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

function state(over) {
  return Object.assign({
    formData: Object.assign({
      name: '路线', description: '描述', startDate: '2026-09-01', endDate: '2026-09-02',
      imgUrl: '/a.png', productType: 1,
      tickets: [{ name: '领队场', price: 39, startTime: '2026-09-01', endTime: '2026-09-02' }],
      chapters: [{ description: '本章剧情', nodes: [{ latitude: 31.2, longitude: 121.4 }] }],
    }, over),
    selectedCategoryIds: [1],
    startDateTime: '2026-09-01 10:00',
    endDateTime: '2026-09-02 10:00',
  });
}

test('城市定向开了商家承接:没选招商截止就拦,红字落在招商截止那一格', () => {
  const bag = buildValidationBag(state({ openMerchantPool: true, recruitDeadline: '' }));
  assert.equal(bag.errors.recruitDeadline, '请选择招商截止日期');
  const ok = buildValidationBag(state({ openMerchantPool: true, recruitDeadline: '2026-08-25' }));
  assert.equal(ok.errors.recruitDeadline, undefined);
});

test('城市定向没开商家承接:不要求招商截止(自办主题不走招募)', () => {
  const bag = buildValidationBag(state({ openMerchantPool: false, recruitDeadline: '' }));
  assert.equal(bag.errors.recruitDeadline, undefined);
});

test('招商截止那一格在开了商家承接时露面,上送也带上它', () => {
  const wxml = read('pages/publish/fabu/step3.wxml');
  assert.match(wxml, /wx:if="\{\{formData\.productType === 2 \|\| formData\.openMerchantPool\}\}"/);
  const js = read('pages/publish/fabu/index.js');
  assert.match(js, /recruitDeadline: Number\(this\.data\.formData\.productType\) === 2 \|\| this\.data\.formData\.openMerchantPool\s*\n\s*\? this\._normalizeDateTime\(this\.data\.formData\.recruitDeadline, '23:59:59'\) : null/);
});
