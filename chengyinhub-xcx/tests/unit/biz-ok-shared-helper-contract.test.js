'use strict';

// B:P1-076(2026-10-24):业务成功判断统一走 utils/response-shape.js isBizOk。
// 已迁移的共享 utils / components/cy 不得再手写 code === 200 || '200';其余页面按各域分批迁移。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { isBizOk } = require('../../utils/response-shape.js');

const ROOT = path.join(__dirname, '..', '..');
const MIGRATED = [
  'utils/access-gate.js',
  'pages/merchant/utils/citynode-redeem-outcome.js',
  'utils/deregister-flow.js',
  'utils/play-run-session.js',
  'components/cy/scene-member-invite-history/index.js',
  'components/cy/scene-qr-citynode/index.js',
  'components/cy/scene-qr-group-code/index.js',
  'components/cy/scene-qr-ticket/index.js',
  'components/cy/scene-route-content/index.js',
];
const HAND_ROLLED = /code\s*===?\s*'200'/;

test('isBizOk 同时接受数字与字符串 200,其余一律 false', () => {
  assert.equal(isBizOk({ code: 200 }), true);
  assert.equal(isBizOk({ code: '200' }), true);
  for (const res of [null, undefined, {}, { code: 500 }, { code: '500' }, { code: 2002 }, { code: ' 200' }]) {
    assert.equal(isBizOk(res), false, JSON.stringify(res));
  }
});

for (const rel of MIGRATED) {
  test(`${rel} 走 isBizOk,不再手写成功码判断`, () => {
    const src = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    assert.ok(!HAND_ROLLED.test(src), `${rel} 仍手写 code === '200'`);
    assert.match(src, /require\([^)]*response-shape\.js'\)/);
    assert.match(src, /isBizOk/);
  });
}
