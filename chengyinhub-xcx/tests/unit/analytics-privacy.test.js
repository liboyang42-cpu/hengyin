const test = require('node:test');
const assert = require('node:assert/strict');
const { sanitizeProperties } = require('../../utils/analytics');

test('埋点属性仅保留非敏感标量，移除身份、联系方式、地址和用户内容', () => {
  const properties = sanitizeProperties({
    action: 'coupon_publish',
    publishCount: 3,
    phone: '13800138000',
    bankAccount: '6222021234567890',
    address: '上海市黄浦区',
    keyword: '用户输入的搜索词',
    name: '用户创建的活动名',
    openid: 'oAbc123',
    nested: { email: 'user@example.com' }
  });

  assert.deepEqual(properties, { action: 'coupon_publish', publishCount: 3 });
});

test('埋点属性拒绝疑似手机号、邮箱和长卡号文本值', () => {
  assert.deepEqual(sanitizeProperties({ note: '13800138000', code: '6222021234567890', label: 'user@example.com' }), {});
});

test('开发版对被敏感键规则丢弃的属性键各提示一次，正式版静默；过滤结果不变', () => {
  const warnings = [];
  const originalWarn = console.warn;
  const originalWx = global.wx;
  console.warn = (msg) => warnings.push(String(msg));
  try {
    global.wx = { getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }) };
    assert.deepEqual(sanitizeProperties({ releaseTopicName: 'x', count: 1 }), { count: 1 });
    assert.equal(warnings.length, 0, '正式版不打日志');

    global.wx = { getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }) };
    assert.deepEqual(sanitizeProperties({ devTopicName: 'x', count: 1 }), { count: 1 }, '开发版同样丢键，不放宽');
    sanitizeProperties({ devTopicName: 'y' });
    assert.equal(warnings.length, 1, '同一键只提示一次');
    assert.match(warnings[0], /敏感键规则已被丢弃/);
    assert.doesNotMatch(warnings[0], /devTopicName/, '固定标签，不把键名带进开发者日志');
  } finally {
    console.warn = originalWarn;
    if (originalWx === undefined) delete global.wx; else global.wx = originalWx;
  }
});
