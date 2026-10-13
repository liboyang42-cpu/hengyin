const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const privacy = require('../../utils/roam-route-privacy');
const { simplifyTrack } = require('../../utils/roam-track-simplify');
const snapshot = require('../../utils/roam-share-snapshot');
const zone = { id: 'z', lat: 31.2, lng: 121.4, r: 100 };
const point = (x, y) => ({ lat: zone.lat + y / 111320, lng: zone.lng + x / (111320 * Math.cos(zone.lat * Math.PI / 180)) });

test('敏感区域两端在外但线段穿越、相切或折返时不能重连', () => {
  const setting = { clipEnabled: false, maskZones: [zone] };
  assert.deepEqual(privacy.maskTrackForSharing([point(-200, 0), point(200, 0)], setting), []);
  assert.deepEqual(privacy.maskTrackForSharing([point(-200, 100), point(200, 100)], setting), []);
  assert.deepEqual(privacy.maskTrackForSharing([point(-200, 0), point(-200, 0)], setting), [point(-200, 0), point(-200, 0)]);
  assert.deepEqual(privacy.maskTrackForSharing([], setting), []);
  assert.deepEqual(privacy.maskTrackForSharing([point(-200, 0)], setting), []);
  const track = [point(-400, 0), point(-200, 0), point(0, 0), point(200, 0), point(400, 0), point(600, 0)];
  assert.deepEqual(privacy.maskTrackForSharing(track, setting), track.slice(3));
  assert.equal(track.length, 6, '本机原记录保持不变');
  const denseShort = [point(-250, 0), point(-240, 0), point(-230, 0), point(-220, 0)];
  const sparseLong = [point(200, 0), point(600, 0)];
  assert.deepEqual(privacy.maskTrackForSharing(denseShort.concat(point(0, 0), sparseLong), setting), sparseLong,
    '最长按实际距离选择，不能因采点密度保留更短的段');
});

test('抽稀后新直线也必须过区域闸，实际快照不能含穿越区域的连接线', () => {
  const setting = { clipEnabled: false, maskZones: [zone] };
  const arc = [point(-200, 0), point(-200, 200), point(200, 200), point(200, 0)];
  const simplified = simplifyTrack(arc, { maxPoints: 2 });
  assert.equal(simplified.length, 2);
  assert.deepEqual(privacy.maskTrackForSharing(simplified, setting), []);
  const longArc = Array.from({ length: 240 }, (_, i) => point(200 * Math.cos(i * Math.PI / 239), 200 * Math.sin(i * Math.PI / 239)));
  const result = snapshot.buildSharePayload({ track: longArc, pois: [] }, setting);
  assert.deepEqual(privacy.maskTrackForSharing(result.track, setting), result.track);
  assert.ok(result.track.length <= 180);
  assert.equal(JSON.stringify(result).includes('maskZones'), false);
});

test('已挂载组件即时同步偏好，失败不广播，显式页面减动效优先，卸载退订', () => {
  const helper = require('../../utils/motion-preference');
  const saved = {};
  global.wx = { getStorageSync: key => saved[key], setStorageSync: (key, value) => { saved[key] = value; } };
  let behavior;
  vm.runInNewContext(fs.readFileSync(require.resolve('../../behaviors/reduced-motion'), 'utf8'), {
    module: { exports: {} }, require: () => helper, Behavior: config => { behavior = config; return config; },
  });
  function instance(explicit) {
    const component = { data: { reducedMotion: explicit }, ...behavior.methods,
      setData(fields, done) { Object.assign(this.data, fields); behavior.observers.reducedMotion.call(this, this.data.reducedMotion); if (done) done(); } };
    behavior.lifetimes.attached.call(component);
    return component;
  }
  const a = instance(false), b = instance(true);
  helper.writeReducedMotion(null, true);
  assert.equal(a.data.reducedMotion, true);
  assert.equal(b.data.reducedMotion, true);
  helper.writeReducedMotion(null, false);
  assert.equal(a.data.reducedMotion, false);
  assert.equal(b.data.reducedMotion, true);
  global.wx.setStorageSync = () => { throw Error('full'); };
  assert.equal(helper.writeReducedMotion(null, true), null);
  assert.equal(a.data.reducedMotion, false);
  behavior.lifetimes.detached.call(a);
  global.wx.setStorageSync = (key, value) => { saved[key] = value; };
  helper.writeReducedMotion(null, true);
  assert.equal(a.data.reducedMotion, false);
  behavior.lifetimes.detached.call(b);
  delete global.wx;
});

test('游玩页消费现有字体真源，叠层与处理中语义不误导读屏', () => {
  const play = fs.readFileSync(require.resolve('../../pages/play/index'), 'utf8');
  const wxml = fs.readFileSync(require.resolve('../../pages/play/index').replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(play, /pageStyle: readPageStyle\(\)/);
  assert.match(wxml, /<page-meta page-style="\{\{pageStyle\}\}"/);
  const sheet = fs.readFileSync(require.resolve('../../components/cy/sheet/index').replace(/\.js$/, '.wxml'), 'utf8');
  const modal = fs.readFileSync(require.resolve('../../components/cy/modal/index').replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(sheet, /aria-hidden="\{\{stacked \|\| _closing\}\}"/);
  assert.match(modal, /aria-hidden="\{\{!maskClosable \|\| loading\}\}"/);
  assert.match(modal, /aria-disabled="\{\{loading \|\| _closing\}\}"/);
  assert.match(modal, /aria-role="status" aria-live="polite">处理中/);
});
