'use strict';
// r2-store 发现页通票入口卡:只在服务端有在售通票时出现,价格只读 cashPrice。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { storeCard } = require('../../utils/discover-store-card.js');

test('没有在售通票/读失败:不出卡', () => {
  assert.equal(storeCard(null), null);
  assert.equal(storeCard([]), null);
  assert.equal(storeCard([{ productId: 1, productKind: 7, cashPrice: 9, items: [{ topicId: 1 }] }]), null);
  assert.equal(storeCard([{ productId: 1, productKind: 2, cashPrice: null, items: [{ topicId: 1 }] }]), null);
  assert.equal(storeCard([{ productId: 1, productKind: 2, cashPrice: 9, items: [] }]), null);
});

test('单个城市包:城市名 + 主题数 + 原价', () => {
  const c = storeCard([{ productId: 9201, productKind: 2, saleCity: '上海', cashPrice: 88, items: [{ topicTitle: 'A' }, { topicTitle: 'B' }] }]);
  assert.deepEqual(c, { title: '上海通票', meta: '2 个主题', price: '¥88', covers: ['A', 'B'] });
});

test('多种通票:取最低价并标「起」,跨城不写城市', () => {
  const c = storeCard([
    { productId: 1, productKind: 3, saleCity: '上海', cashPrice: 120, items: [{ topicTitle: 'A' }] },
    { productId: 2, productKind: 2, saleCity: '杭州', cashPrice: 59.5, items: [{ topicTitle: 'B' }] }
  ]);
  assert.equal(c.title, '城市通票');
  assert.equal(c.price, '¥59.50 起');
  assert.equal(c.meta, '2 种通票');
});

test('发现页:卡在帖文流顶部、商家视角与未登录不读、只读一次', () => {
  const dir = path.join(__dirname, '../../pages/talent/list');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.match(wxml, /<view wx:if="\{\{storeCard\}\}" class="store-card"/);
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  assert.match(js, /if \(this\.data\.isMerchantViewer \|\| !app\.getUserID\(\)\)/);
  assert.match(js, /if \(this\._storeCardRead\) return;/);
  assert.match(js, /\/subpackageMember\/market\/passes\/index/);
});
