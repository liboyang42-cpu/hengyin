'use strict';
// 第九轮 m4 第 3 项:转场视频地址是配置、仓库里默认空;片子不进小程序包;上传步骤写进发版清单。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const config = require('../../utils/config.js');

test('仓库里转场视频地址为空(空 = 不播)', () => {
  assert.equal(config.roamAtmosVideoUrl, '');
});

test('小程序目录里没有任何视频文件', () => {
  const found = [];
  (function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(mp4|mov|m4v|webm)$/i.test(e.name)) found.push(path.relative(root, p));
    }
  })(root);
  assert.deepEqual(found, []);
});

test('发版清单写明 OSS 路径、公共读、可播主机和填写位置', () => {
  const doc = fs.readFileSync(path.join(root, 'docs/发版checklist.md'), 'utf8');
  const sec = doc.slice(doc.indexOf('漫游大气层转场视频'));
  assert.match(sec, /static\/roam\/atmos-entry-v2\.mp4/);
  assert.match(sec, /公共读/);
  assert.match(sec, /TRUSTED_ASSET_HOSTS/);
  assert.match(sec, /roamAtmosVideoUrl/);
  config.trustedAssetHosts.forEach((h) => assert.ok(sec.includes(h), h));
  assert.equal(config.isTrustedAssetUrl('https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/static/roam/atmos-entry-v2.mp4'), true);
});
