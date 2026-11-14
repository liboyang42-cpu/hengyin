'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { stableSiteKey } = require('../lint/stable-site-key');

test('finding identity survives inserted lines and moving an unchanged method', () => {
  const a = 'Page({\nload() {\nthis.setData(patch);\n}\n});';
  const b = '// moved\n\nPage({\nother() {},\nload() {\nthis.setData(patch);\n}\n});';
  assert.equal(stableSiteKey('page.js', a, 3), stableSiteKey('page.js', b, 6));
});

test('changed code, another owner and a new duplicate cannot inherit an exemption', () => {
  const a = 'Page({\nload() {\nthis.setData(patch);\n}\n});';
  const key = stableSiteKey('page.js', a, 3);
  assert.notEqual(key, stableSiteKey('page.js', a.replace('patch', 'other'), 3));
  assert.notEqual(key, stableSiteKey('page.js', a.replace('load()', 'save()'), 3));
  assert.notEqual(key, stableSiteKey('page.js', a.replace('Page(', 'Component('), 3));
  const request = 'Page({\nload() {\napp.sendRequest({\nurl: endpoint,\nsilentError: true\n});\n}\n});';
  assert.notEqual(stableSiteKey('request.js', request, 5), stableSiteKey('request.js', request.replace('endpoint', 'other'), 5));
  const duplicate = a.replace('this.setData(patch);', 'this.setData(patch);\nthis.setData(patch);');
  assert.equal(key, stableSiteKey('page.js', duplicate, 3));
  assert.notEqual(key, stableSiteKey('page.js', duplicate, 4));
  assert.throws(() => stableSiteKey('page.js', a, 0));
});
