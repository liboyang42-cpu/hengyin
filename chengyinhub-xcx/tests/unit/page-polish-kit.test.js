'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..', '..');

function wxs(file) {
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), {
    module, parseFloat, getRegExp: (source, flags) => new RegExp(source, flags),
  });
  return module.exports;
}

test('count.wxs money scales only the number and keeps the text around it', () => {
  const { money } = wxs('utils/wxs/count.wxs');
  assert.equal(money('¥1,234.00', 0.5), '¥617.00');
  assert.equal(money('−¥5.00', 0.5), '−¥2.50');
  assert.equal(money('¥1,234.00 元', 1), '¥1,234.00 元');
  assert.equal(money('待定', 0.5), '待定');
  assert.equal(money('—', 0.5), '—');
  assert.equal(money('¥1,234.00', null), '¥1,234.00');
});

test('num.wxs shows a dash only when there is no value', () => {
  const { n } = wxs('utils/wxs/num.wxs');
  assert.equal(n(undefined), '—');
  assert.equal(n(null), '—');
  assert.equal(n(''), '—');
  assert.equal(n(0), 0);
  assert.equal(n(12), 12);
});

test('enter with once runs one entrance per key on a page instance', () => {
  const pageMotion = require(path.join(root, 'utils/page-motion.js'));
  const writes = [];
  const page = { data: {}, setData(patch) { writes.push(patch); } };
  pageMotion.enter(page, 'pmList', { once: true });
  pageMotion.enter(page, 'pmList', { once: true });
  pageMotion.enter(page, 'pmOther', { once: true });
  clearTimeout(page._pmListTimer);
  clearTimeout(page._pmOtherTimer);
  assert.deepEqual(writes, [{ pmList: 1 }, { pmOther: 1 }]);
});

test('fly-in leaves transform free so press feedback still scales', () => {
  const css = fs.readFileSync(path.join(root, 'style/motion-entry.wxss'), 'utf8');
  const keyframes = css.slice(css.indexOf('@keyframes cy-fly-in'));
  assert.doesNotMatch(keyframes.replace(/\/\*[\s\S]*?\*\//g, ''), /transform\s*:/);
});
