'use strict';

// PRIVATE-GROUP-F-current-favorites-AI / PC-233:店铺参谋的智能解读跟随当日缓存
// (MerchantInsightServiceImpl:喂 AI 的 facts.profile 随缓存,展示层 profile 实时读)。
// 解读卡要如实说明依据的是生成时的数据与档案,今天改档案次日生效——不能让商家以为刚改的档案已经进了解读。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

test('智能解读卡标注生成时间对应的数据/档案版本,仅在有生成时间且有解读时出现', () => {
  const wxml = read('pages/merchant/marketing/ai-insight/index.wxml');
  const aiCard = wxml.slice(wxml.indexOf('<cy-card wx:if="{{ai}}"'), wxml.indexOf('<cy-card wx:else class="ai-card ai-card--down"'));
  assert.match(aiCard, /<text class="ai-source" wx:if="\{\{generatedAt\}\}">解读依据 \{\{generatedAt\}\} 的经营数据与当时的店铺档案；今天修改的档案次日生效<\/text>/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.wxss'), /\.ai-source \{/);
  // 后端合同:档案改动对 AI 次日生效的前提仍在
  const service = fs.readFileSync(path.join(ROOT, '../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantInsightServiceImpl.java'), 'utf8');
  assert.match(service, /CACHE_PREFIX \+ merchantMemberId \+ ":" \+ now\.toLocalDate\(\)/);
  assert.match(service, /facts\.put\("profile", profileFactsOf\(profileView\)\)/);
});
