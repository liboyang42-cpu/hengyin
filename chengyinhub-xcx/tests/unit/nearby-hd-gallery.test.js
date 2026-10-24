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
  const page = { ...definition, data: { ...definition.data }, _pois: [{ id: 10, type: 1, name: 'Museum', coverImg: cover }],
    _syncMarkers() {}, _myIdentity: () => identity, setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const fields = key.split('.'); let target = this.data;
        while (fields.length > 1) target = target[fields.shift()];
        target[fields[0]] = value;
      }
    },
  };
  return { page, requests, navigation, changeActor() { identity = 'actor-2'; } };
}
test('opening a card loads its public gallery without authorization and keeps navigation', () => {
  const h = mount(); h.page.openPinCard('poi', 10);
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].auth, false);
  h.requests[0].success({ code: 200, data: document });
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

test('missing galleries retain the cover; closed cards and changed identities reject late replies', () => {
  for (const change of [h => h.page.closePoiCard(), h => h.changeActor(), h => { h.page._unloaded = true; }]) {
    const h = mount(); h.page.openPinCard('poi', 10); change(h);
    h.requests[0].success({ code: 200, data: document });
    assert.equal(h.page.data.poiCard.thumbs.length, 0);
  }
  const h = mount(); h.page.openPinCard('poi', 10); h.requests[0].success({ code: 404 });
  assert.equal(h.page.data.poiCard.img, 'https://asset.example/' + cover);
});

test('a playable official place expands into the existing detail and ignores duplicate navigation', () => {
  const h = mount(); h.page._pois[0].templateId = 88;
  h.page.openPinCard('poi', 10);
  assert.equal(h.page.data.poiCard.primary, '查看玩法与详情');
  h.page.onPoiExpand(); h.page.onPoiPrimary();
  assert.equal(h.navigation.length, 1);
  assert.equal(h.navigation[0].url, '/subpackageRoam/poi-detail/index?poiId=10');
  h.navigation[0].complete(); h.page.onPoiPrimary();
  assert.equal(h.navigation.length, 2);
});
