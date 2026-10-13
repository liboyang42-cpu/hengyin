const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/detail.vue'),
  'utf8'
);

test('后台供给条款把 0 作为不限的显式值，不再把空值提交给 NOT NULL 名额列', () => {
  assert.match(source,
    /<el-input-number v-model="termsModeDialog\.maxMerchant" :min="0" :max="127" :step="1"\s*\/>/);
  assert.match(source, /家 · 0 = 不限（三档通用）/);
  assert.match(source,
    /maxMerchant: Number\.isInteger\(Number\(chapter\.maxMerchant\)\)[\s\S]*?\? Number\(chapter\.maxMerchant\) : 0/);
  assert.match(source,
    /const maxMerchant = Number\(d\.maxMerchant\)[\s\S]*?Number\.isInteger\(maxMerchant\)[\s\S]*?maxMerchant < 0[\s\S]*?maxMerchant > 127/);
  assert.match(source,
    /configureChapterSupplyTerms\(d\.chapterId,\s*\{[\s\S]*?\n\s*maxMerchant\n\s*\}\)/);
  assert.doesNotMatch(source, /maxMerchant:\s*d\.maxMerchant == null \? null : d\.maxMerchant/);
});

test('后台权益门槛允许留空，但一旦填写必须大于零', () => {
  assert.match(source,
    /<el-input-number v-model="termsModeDialog\.perkMinValue" :min="0\.01" :precision="2" :step="1"\s*\/>/);
  assert.match(source, /元 · 留空 = 不设门槛/);
});
