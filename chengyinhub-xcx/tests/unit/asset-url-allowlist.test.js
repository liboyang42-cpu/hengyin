const test = require('node:test');
const assert = require('node:assert/strict');
const config = require('../../utils/config.js');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const legacyHost = 'xianghuioss.oss-cn-shanghai.aliyuncs.com';
const rejectedUrls = [
  `https://${legacyHost}.evil.test/chenyin/t1.png`,
  `https://other-bucket.oss-cn-shanghai.aliyuncs.com/chenyin/t1.png`,
  `https://${legacyHost}@evil.test/chenyin/t1.png`,
  `https://user@${legacyHost}/chenyin/t1.png`,
  `https://${legacyHost}:8443/chenyin/t1.png`,
  `https://127.0.0.1/chenyin/t1.png?host=${legacyHost}`,
  `https://192.168.1.1/chenyin/t1.png?host=${legacyHost}`,
  `https://evil.test/chenyin/t1.png?next=https://${legacyHost}/chenyin/t1.png`,
  `https://evil.test/#https://${legacyHost}/chenyin/t1.png`,
];

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

test('已发布旧桶图片保留精确 HTTPS 域名，查询内容不能替代可信主机', () => {
  assert.equal(config.isTrustedAssetUrl(`https://${legacyHost}/chenyin/t1.png`), true);
  assert.equal(config.isTrustedAssetUrl(`https://${legacyHost}/upload/old.jpg?Expires=123&Signature=example%3D`), true);
  assert.equal(config.isTrustedAssetUrl(`http://${legacyHost}/chenyin/t1.png`), false);
  assert.equal(config.isTrustedAssetUrl(`ftp://${legacyHost}/chenyin/t1.png`), false);
  for (const url of rejectedUrls) assert.equal(config.isTrustedAssetUrl(url), false, url);
});

test('实际 app.getImgUrl 保留旧桶原图和签名，拒绝伪主机且不改变本地图及运行时基址', () => {
  const appFile = path.resolve(__dirname, '../../app.js');
  let app;
  vm.runInNewContext(fs.readFileSync(appFile, 'utf8'), {
    App(value) { app = value; }, wx: {}, getApp: () => ({}),
    require: createRequire(appFile), console,
  }, { filename: appFile });
  for (const suffix of ['chenyin/t1.png', 'upload/old.jpg?Expires=123&Signature=example%3D']) {
    const url = `https://${legacyHost}/${suffix}`;
    assert.equal(app.getImgUrl(url), url);
  }
  for (const url of rejectedUrls) assert.equal(app.getImgUrl(url), '', url);
  assert.equal(app.getImgUrl('/images/route_city_cover.png'), '/images/route_city_cover.png');
  app.globalData.assetBaseUrl = 'https://www.chengyinhub.com/prod-api/profile/';
  assert.equal(app.getImgUrl('2026/a.jpg'), 'https://www.chengyinhub.com/prod-api/profile/2026/a.jpg');
});
