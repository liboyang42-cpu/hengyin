const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const L = require('../../subpackageRoam/utils/map-layers.js');
const cover = 'curated/shanghai/10.jpg';
const document = { poiId: 10, coverKey: cover, images: [
  { key: cover, width: 4032, height: 3024 }, { key: 'curated/shanghai/10-2.jpg', width: 2048, height: 1536 },
], credit: 'Photo author and source' };

test('optional gallery binds place and cover, rejects remote paths, traversal and small images', () => {
  for (const input of ['https://example.org/x.jpg', '/profile/x.jpg', 'a/../b.jpg', 'a.jpg?x=1', null]) assert.equal(L.poiGalleryPath(input), '');
  assert.equal(L.poiGalleryPath(cover), '/profile/curated/shanghai/10.gallery.json');
  assert.equal(L.poiGallery(document, 10, cover).keys.length, 2);
  for (const change of [{ poiId: 11 }, { coverKey: 'other.jpg' }, { images: Array(7).fill(document.images[0]) }]) {
    assert.equal(L.poiGallery({ ...document, ...change }, 10, cover).keys.length, 0);
  }
  const images = ['https://example.org/x.jpg', 'curated/shanghai/../other.jpg', 'another/x.jpg'].map(key => ({ key, width: 4000, height: 3000 }));
  images.push({ key: cover, width: 600, height: 400 });
  assert.equal(L.poiGallery({ ...document, images }, 10, cover).keys.length, 0);
});

function mount() {
  let definition, identity = 'actor-1'; const requests = [], navigation = [];
  const wx = { getWindowInfo: () => ({ windowWidth: 375, windowHeight: 667 }), navigateTo: options => navigation.push(options) };
  const app = { getImgUrl: key => 'https://asset.example/' + key, sendRequest: r => requests.push(r) };
  const filename = path.resolve(__dirname, '../../subpackageRoam/nearby/index.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Page: d => { definition = d; }, getApp: () => app,
    wx, require: createRequire(filename),
    setTimeout: () => 1, clearTimeout() {},
  }, { filename });
  const page = { ...definition, data: { ...definition.data }, _pois: [{ id: 10, type: 1, name: 'Museum', coverImg: cover, tags: '["roam:seed:v1:test"]' }],
    _syncMarkers() {}, _myIdentity: () => identity, setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const fields = key.split('.'); let target = this.data;
        while (fields.length > 1) target = target[fields.shift()];
        target[fields[0]] = value;
      }
    },
  };
  return { page, requests, navigation, wx, changeActor() { identity = 'actor-2'; } };
}
async function loadPlaces(h, metadata = document) {
  const rows = h.page._pois; h.page._pois = [];
  h.page._syncLayerOptions = () => {}; h.page._queueIcons = () => Promise.resolve(false);
  h.page.fetchPois({ lat: 31.2, lng: 121.4 }, 1000);
  const pending = h.requests.at(-1).success({ code: 200, data: rows });
  const manifest = h.requests.at(-1);
  assert.match(manifest.url, /gallery\.json$/); assert.equal(manifest.auth, false);
  assert.equal(h.page._pois.length, 0, 'licence has not been read: publish no photo yet');
  manifest.success(metadata ? { code: 200, data: metadata } : { code: 404 });
  await pending;
}
test('a public gallery is checked before publishing a card, retaining preview and navigation', async () => {
  const h = mount(); await loadPlaces(h); h.page.openPinCard('poi', 10);
  assert.equal(h.page.data.poiCard.thumbs.length, 2); assert.equal(h.page.data.poiCard.primary, '去这里');
  h.page.onPoiPhotoCredit(); assert.equal(h.page.data.poiCard.creditOpen, true);
  h.page.onPoiPhotoCredit(); assert.equal(h.page.data.poiCard.creditOpen, false);
  const calls = []; global.wx = { previewImage: r => calls.push(r) };
  try { h.page.onPoiImagePreview({ currentTarget: { dataset: { index: 1 } } }); }
  finally { delete global.wx; }
  assert.equal(calls[0].current, 'https://asset.example/curated/shanghai/10-2.jpg');
});
test('a failed place photo stays a normal marker instead of becoming a black photo pin', async () => {
  const h = mount(); h.page._pins = {}; h.page._iconFailed = {};
  h.page._canvas = async () => ({});
  h.page._loadImage = async () => { throw new Error('photo unavailable'); };
  assert.equal(await h.page._genPins([{ id: 106, kind: 'spot', img: cover }]), false);
  assert.equal(Object.keys(h.page._pins).length, 0);
});

test('missing licence blocks the cover; closed cards and changed identities reject late replies', async () => {
  for (const change of [h => h.page.closePoiCard(), h => h.changeActor(), h => { h.page._unloaded = true; }]) {
    const h = mount(); h.page._pois[0].templateId = 88; await loadPlaces(h); h.page.openPinCard('poi', 10);
    const node = h.requests.find(r => r.url === '/api/city/nodes/10');
    const pending = node.success({ code: 200, data: { ...h.page._pois[0], templateTitle: 'late private progress' } });
    const manifest = h.requests.at(-1); assert.match(manifest.url, /gallery\.json$/);
    change(h); manifest.success({ code: 200, data: document }); await pending;
    assert.ok(!h.page.data.poiCard || !h.page.data.poiCard.game);
  }
  const h = mount(); await loadPlaces(h, null); h.page.openPinCard('poi', 10);
  assert.equal(h.page.data.poiCard.img, ''); assert.equal(h.page.data.poiCard.thumbs.length, 0);
});

test('a playable official place expands in the card; start checks arrival and opens the map camera, never a second sheet', async () => {
  const h = mount(); h.page._pois[0].templateId = 88; h.page._pois[0].lat = 31.2; h.page._pois[0].lng = 121.4;
  await loadPlaces(h); h.page.openPinCard('poi', 10);
  assert.equal(h.page.data.poiCard.official, true);
  assert.equal(h.page.data.poiCard.primary, '开始挑战');
  const node = h.requests.find((r) => r.url === '/api/city/nodes/10');
  const pending = node.success({ code: 200, data: { ...h.page._pois[0], gallery: undefined, galleryJson: JSON.stringify(['a.jpg', 'b.jpg']), templateTitle: '红色字母档案', validationMethod: 3, badgeCode: 'B', completed: false, radiusM: 80, ratingCount: 2, averageRating: 4.5 } });
  h.requests.at(-1).success({ code: 200, data: document }); await pending;
  assert.equal(JSON.stringify(h.page.data.poiCard.thumbs), JSON.stringify(['https://asset.example/a.jpg', 'https://asset.example/b.jpg']));
  assert.equal(h.page.data.poiCard.ratingText, '★ 4.5 · 2 条评价');
  h.page.onPoiExpand();
  assert.equal(h.page.data.poiExpand, true); assert.equal(h.navigation.length, 0);
  let located; h.wx.getLocation = (o) => { located = o; };
  h.page.onPoiPrimary(); located.success({ latitude: 31.25, longitude: 121.4 });
  assert.match(h.page.data.poiCard.hint, /^还没到/); assert.equal(h.page.data.camOpen, false);
  h.page.onPoiPrimary(); located.success({ latitude: 31.2001, longitude: 121.4 });
  assert.equal(h.page.data.camOpen, true); assert.equal(h.navigation.length, 0);
  h.page.closeSpotCam(); assert.equal(h.page.data.camOpen, false);
});
