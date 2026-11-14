'use strict';
// E-B13:评价提交带 request_id,网络失败后重试复用同一个,成功后下一次换新。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const api = require('../../subpackageJourney/utils/journey-api.js');

test('api.rate 把 request_id 随表单参数发出去', async () => {
  const sent = [];
  const app = { sendRequest(o) { sent.push(o.data); o.success({ code: 200 }); } };
  await api.rate(app, 1, 9, 5, '好玩', 'req_1700000000000_abcdefgh');
  assert.equal(sent[0].request_id, 'req_1700000000000_abcdefgh');
  assert.equal(sent[0].owner_id, 9);
});

test('评价页:一次意图复用同一个 request_id,成功后清掉', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../subpackageJourney/pages/settle/index.js'), 'utf8');
  assert.match(src, /if \(!this\._rateReqId\) this\._rateReqId = 'req_'/);
  assert.match(src, /api\.rate\(app, ownerType, ownerId, this\.data\.stars, text, this\._rateReqId\)/);
  assert.match(src, /if \(api\.ok\(res\)\) \{ this\._rateReqId = '';/);
});
