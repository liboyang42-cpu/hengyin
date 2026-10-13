const test = require('node:test');
const assert = require('node:assert/strict');
const config = require('../../utils/config.js');

test('资产域名允许正式主域与项目 OSS，拒绝 http、伪后缀和任意外域', () => {
  assert.equal(config.isTrustedAssetUrl('https://www.chengyinhub.com/prod-api/profile/a.jpg'), true);
  assert.equal(config.isTrustedAssetUrl('https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.jpg?Expires=123&Signature=example'), true);
  assert.equal(config.isTrustedAssetUrl('http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/a.jpg'), false);
  assert.equal(config.isTrustedAssetUrl('https://chengyin-oss.oss-cn-shanghai.aliyuncs.com.evil.test/a.jpg'), false);
  assert.equal(config.isTrustedAssetUrl('https://another-bucket.oss-cn-shanghai.aliyuncs.com/a.jpg'), false);
  assert.equal(config.isTrustedAssetUrl('http://www.chengyinhub.com/a.jpg'), false);
  assert.equal(config.isTrustedAssetUrl('https://www.chengyinhub.com.evil.test/a.jpg'), false);
  assert.equal(config.isTrustedAssetUrl('https://evil.test/a.jpg'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(config, 'amapWebKey'), false);
});
