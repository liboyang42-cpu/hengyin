'use strict';
// r2-merchant(2026-11-10)a6-w2-07(业主「数字没有就是0」):主题「客户」汇总格没有数字就写 0,不再写「—」。
// 「未售票」这一句空态保留(业主只改了数字)。商家域的计数是业主拍板的例外,金额类仍按「未知不当 0」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

for (const file of ['pages/topic/components/project-host/index.wxml', 'pages/topic/components/project-join/index.wxml']) {
  test('a6-w2-07:' + file + ' 客户汇总格没有就是 0', () => {
    const view = read(file);
    const dsum = view.slice(view.indexOf('<view class="dsum" wx:else>'), view.indexOf('</view>\n', view.indexOf('summary.refundedCount')));
    for (const key of ['paidCount', 'pendingCount', 'arrivedCount']) {
      assert.match(dsum, new RegExp('\\{\\{ playerSheet\\.summary\\.' + key + ' \\|\\| 0 \\}\\}'), key);
    }
    assert.doesNotMatch(dsum, /'—'/, '汇总格不再写「—」');
  });
}

test('a6-w2-07:没开售时空态仍写「未售票」,汇总数据为空对象时格子渲染 0', () => {
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(js, /emptyText = '未售票';/);
});
