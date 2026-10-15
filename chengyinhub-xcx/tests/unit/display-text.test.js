const { test } = require('node:test');
const assert = require('node:assert/strict');
const { decodeDisplayText, decodeDisplayFields } = require('../../utils/display-text.js');

test('解码服务端过滤器产出的五种实体', () => {
  assert.equal(decodeDisplayText('a&lt;b 且 c>d'), 'a<b 且 c>d');
  assert.equal(decodeDisplayText('x&lt;y'), 'x<y');
  assert.equal(decodeDisplayText('&lt;&gt;&amp;&quot;&#39;'), '<>&"\'');
});

test('只认这五个字符的数字实体(十进制、带前导零、十六进制)', () => {
  assert.equal(decodeDisplayText('&#60;&#62;&#38;&#34;&#39;&#039;&#x3c;&#X3E;&#x26;&#x22;&#x27;'), '<>&"\'\'<>&"\'');
  assert.equal(decodeDisplayText('&#65;&#x41;&#8364;'), '&#65;&#x41;&#8364;');
});

test('单遍解码:&amp;lt; 只变成 &lt;,不会再变成 <', () => {
  assert.equal(decodeDisplayText('&amp;lt;script&amp;gt;'), '&lt;script&gt;');
  assert.equal(decodeDisplayText('&amp;amp;'), '&amp;');
});

test('未知实体、缺分号、大小写不符的写法原样保留', () => {
  for (const s of ['&nbsp;', '&copy;', '&lt', 'a & b', '&LT;', '&#;', '&#x;', '&unknown;', 'AT&T']) {
    assert.equal(decodeDisplayText(s), s);
  }
});

test('非字符串原样返回', () => {
  for (const v of [null, undefined, 0, 42, true, ['&lt;']]) assert.equal(decodeDisplayText(v), v);
  const obj = { a: '&lt;' };
  assert.equal(decodeDisplayText(obj), obj);
});

test('decodeDisplayFields 只解码列出的字符串字段,返回浅拷贝', () => {
  const row = { content: 'x&lt;y', title: 'a&amp;b', count: 3, url: 'https://e.com/?a=1&amp;b=2' };
  const out = decodeDisplayFields(row, ['content', 'title', 'count', 'missing']);
  assert.deepEqual(out, { content: 'x<y', title: 'a&b', count: 3, url: 'https://e.com/?a=1&amp;b=2' });
  assert.notEqual(out, row);
  assert.equal(row.content, 'x&lt;y');
  assert.equal(decodeDisplayFields(null, ['a']), null);
});
