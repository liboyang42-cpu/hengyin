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
  // 2026-10-22 batch2 集成:#511 店铺参谋改版把解读卡改为 view.ai-card,且页内不再出现「店铺档案」字样,按新结构取卡片区间。
  const start = wxml.indexOf('<view wx:if="{{ai}}" class="ai-card">');
  // 2026-10-23 业主:降级卡已删(没有解读就整段不显示),区间终点改到其后的说明注释
  const end = wxml.indexOf('<!-- 2026-10-23 业主:没有解读就整段不显示');
  assert.ok(start >= 0 && end > start, '解读卡结构必须可定位');
  const aiCard = wxml.slice(start, end);
  assert.match(aiCard, /<text class="ai-source" wx:if="\{\{generatedAt\}\}">解读依据 \{\{generatedAt\}\} 的经营数据与当时的档案；今天修改的档案次日生效<\/text>/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.wxss'), /\.ai-source \{/);
  // 后端合同:档案改动对 AI 次日生效的前提仍在
  const service = fs.readFileSync(path.join(ROOT, '../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantInsightServiceImpl.java'), 'utf8');
  assert.match(service, /CACHE_PREFIX \+ merchantMemberId \+ ":" \+ now\.toLocalDate\(\)/);
  assert.match(service, /facts\.put\("profile", profileFactsOf\(profileView\)\)/);
});
