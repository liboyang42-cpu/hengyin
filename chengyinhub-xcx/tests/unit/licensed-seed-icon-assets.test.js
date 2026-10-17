const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');

const ROOT = path.resolve(__dirname, '../../..');
const ASSETS = 'design/icons/lucide';
const manifest = JSON.parse(fs.readFileSync(path.join(ROOT, ASSETS, 'seed-assets-mapping.json')));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const read = (relative) => fs.readFileSync(path.join(ROOT, relative));
const choices = ['moon-star', 'coffee', 'camera', 'puzzle', 'route', 'waypoints', 'landmark',
  'washing-machine', 'wrench', 'car-front', 'gift', 'palette'];

test('twelve exact seeded image candidates preserve natural keys, origin and fixture scope', () => {
  assert.equal(manifest.records.length, 12);
  assert.deepEqual(manifest.records.map((r) => r.icon_name), choices);
  assert.deepEqual(manifest.records.map((r) => r.mapping_key), [
    ...[1, 2, 3, 4].map((i) => `preset-style-${i}`),
    ...[1, 2, 3].map((i) => `preset-route-${i}`),
    ...[1, 2, 3, 4, 5].map((i) => `category-${i}`),
  ]);
  for (const r of manifest.records) {
    const fixture = r.mapping_key.startsWith('preset-');
    assert.equal(r.execution_scope, fixture ? 'TEST_FIXTURE' : 'MANAGED_PRODUCTION_SEED');
    assert.equal(r.natural_key.del_flag, 0);
    assert.ok(r.source_marker.create_by);
    assert.match(r.url_fingerprint, /^[a-f0-9]{64}$/);
    assert.equal(sha(read(r.source_file)), r.source_sha256, `${r.source_file} original SQL changed`);
    assert.ok(!('record_id' in r), 'actual DB record IDs cannot be guessed');
    assert.equal(r.table + '.' + r.field, fixture
      ? (r.mapping_key.startsWith('preset-style-') ? 'preset_theme_style.cover_img' : 'preset_route.preview_img')
      : 'sys_category.icon');
    assert.ok(!JSON.stringify(r).includes('/Users/'), 'private paths must not enter the public manifest');
  }
  assert.match(manifest.field_contract, /ordinary image URL/);
  assert.match(manifest.asset_purpose, /not maps/);
});

test('all exported vectors and complete official license match recorded immutable bytes', () => {
  assert.equal(manifest.license.sha256, 'b495047bd93a9b06913511076f504daba17d5bbeb3e0650f3bb53a4220329c57');
  assert.equal(sha(read(manifest.license.path)), manifest.license.sha256);
  const license = read(manifest.license.path).toString();
  assert.match(license, /Copyright \(c\) 2026 Lucide Icons and Contributors/);
  assert.match(license, /Copyright \(c\) 2013-present Cole Bemis/);
  assert.match(license, /permission notice appear in all copies/);
  for (const r of manifest.records) {
    const svg = read(r.svg.path);
    assert.equal(sha(svg), r.svg.sha256, r.icon_name);
    assert.match(svg.toString(), /viewBox="0 0 24 24"/);
    assert.doesNotMatch(svg.toString(), /<script\b|<image\b|<foreignObject\b|\b(?:href|onload)=/i);
    assert.match(r.upstream_svg_url, /\/dab294f313897bacaa968b725b3259fa37c17bd1\/icons\//);
    assert.match(r.figma_node_id, /^591:\d+$/);
  }
});

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function assertPng(bytes) {
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  const idat = [];
  let ended = false;
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    const end = offset + 12 + length;
    assert.ok(end <= bytes.length, 'truncated PNG chunk');
    const type = bytes.subarray(offset + 4, offset + 8).toString();
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    assert.equal(crc32(bytes.subarray(offset + 4, offset + 8 + length)), bytes.readUInt32BE(offset + 8 + length), 'PNG CRC');
    if (type === 'IHDR') {
      assert.equal(data.readUInt32BE(0), 192);
      assert.equal(data.readUInt32BE(4), 192);
      assert.equal(data[8], 8);
      assert.equal(data[9], 6, 'RGBA transparency');
      assert.equal(data[12], 0, 'non-interlaced PNG');
    }
    if (type === 'IDAT') idat.push(data);
    if (type === 'IEND') { ended = true; assert.equal(end, bytes.length); }
    offset = end;
  }
  assert.ok(ended);
  assert.equal(zlib.inflateSync(Buffer.concat(idat)).length, 192 * (192 * 4 + 1));
}

test('twelve PNGs are complete decodable 192px RGBA images; corrupt and truncated bytes fail', () => {
  for (const r of manifest.records) {
    const png = read(r.png.path);
    assert.equal(sha(png), r.png.sha256, r.icon_name);
    assert.match(r.png.original_export_sha256, /^[a-f0-9]{64}$/);
    assert.notEqual(r.png.sha256, r.png.original_export_sha256, 'sanitized bytes differ; preserve original export provenance');
    assertPng(png);
  }
  assert.equal(manifest.png_normalization.method, 'UploadImagePolicy.sanitizeMetadata(existing actual compiled class)');
  assert.equal(manifest.png_normalization.normalization_receipt_sha256, '718adff18ee26897b392b7d4acdc6a7d7c26e56f02be74f2ada04487b44e443e');
  assert.equal(manifest.png_normalization.provider_calls, 0);
  assert.equal(manifest.png_normalization.uploads, 0);
  const original = read(manifest.records[0].png.path);
  const corrupt = Buffer.from(original);
  corrupt[20] ^= 1;
  assert.throws(() => assertPng(corrupt));
  assert.throws(() => assertPng(original.subarray(0, original.length - 4)));
});

test('the existing offline credit area retains old notices and adds no new action', () => {
  const wxml = read('chengyinhub-xcx/pages/shezhi/shezhi.wxml').toString();
  assert.match(wxml, /game-icons\.net 游戏图标/);
  assert.match(wxml, /像素城市场景 · Luis Zuno/);
  const lucide = wxml.match(/<view class="sz-attribution__name">Lucide 图标[\s\S]*?来源：lucide\.dev · 许可：lucide\.dev\/license<\/view>/);
  assert.ok(lucide, 'Lucide credit must be readable without networking');
  assert.match(lucide[0], /ISC License/);
  assert.match(lucide[0], /Cole Bemis · MIT License/);
  assert.doesNotMatch(lucide[0], /\bbind|\bcatch|wx:if/);
});
