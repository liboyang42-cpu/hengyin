'use strict'
// 2026-11-21 图片按槽位出图:utils/image-url.js sized() 与 utils/wxs/img.wxs fit() 同一规则。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const imageUrl = require('../../utils/image-url.js')

const HEX = '0123456789abcdef0123456789abcdef'
const QUERY = 'Expires=4102444800&OSSAccessKeyId=LTAIxx&Signature=ab%2Bc%2Fd%3D'
const SIGNED = 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg?' + QUERY
const BASE = 'https://api.example.test/prod-api'

function loadImgWxs() {
  const sandbox = { module: { exports: {} }, getRegExp: (p, f) => new RegExp(p, f) }
  vm.createContext(sandbox)
  vm.runInContext(fs.readFileSync(path.join(root, 'utils/wxs/img.wxs'), 'utf8'), sandbox)
  return sandbox.module.exports
}

function readBackendWidths() {
  const java = fs.readFileSync(path.join(root, '../chengyinhub-common/src/main/java/com/chengyinhub/common/utils/file/FileOssUploadUtils.java'), 'utf8')
  const m = java.match(/IMAGE_RENDITION_WIDTHS\s*=\s*\{([^}]*)\}/)
  return m[1].split(',').map((s) => Number(s.trim()))
}

test('每个槽位按 CSS 宽 × DPR 向上取到固定档位', () => {
  const expected = {
    1: { hero: 480, card: 480, half: 240, thumb: 160, avatar: 160, preview: 1280 },
    2: { hero: 750, card: 750, half: 480, thumb: 240, avatar: 160, preview: 1280 },
    3: { hero: 1280, card: 1080, half: 640, thumb: 320, avatar: 160, preview: 1280 },
  }
  for (const dpr of [1, 2, 3]) {
    for (const slot of Object.keys(imageUrl.SLOTS)) {
      assert.equal(imageUrl.renditionWidth(slot, dpr), expected[dpr][slot], slot + '@' + dpr)
      assert.ok(imageUrl.WIDTHS.includes(imageUrl.renditionWidth(slot, dpr)))
    }
  }
  assert.equal(imageUrl.renditionWidth(100, 2), 240, 'CSS 宽数字也认')
  assert.equal(imageUrl.renditionWidth('nope', 2), 0)
})

test('档位与后端签名白名单完全一致', () => {
  assert.deepEqual(imageUrl.WIDTHS, readBackendWidths())
})

test('本桶签名原图改写成后端缩略地址,签名查询串原样透传', () => {
  assert.equal(imageUrl.sized(SIGNED, 'hero', { dpr: 2, base: BASE }),
    BASE + '/api/common/img/750/orig/upload/' + HEX + '.jpg?' + QUERY)
  assert.equal(imageUrl.sized(SIGNED, 'card', { dpr: 3, base: BASE + '/', webp: true }),
    BASE + '/api/common/img/1080/webp/upload/' + HEX + '.jpg?' + QUERY)
  // 历史 http 签名链接先升 https 再改写
  assert.equal(imageUrl.sized(SIGNED.replace('https:', 'http:'), 'thumb', { dpr: 2, base: BASE }),
    BASE + '/api/common/img/240/orig/upload/' + HEX + '.jpg?' + QUERY)
  // png 也改写(orig 保留透明)
  const png = SIGNED.replace('.jpg?', '.png?')
  assert.equal(imageUrl.sized(png, 'half', { dpr: 2, base: BASE }),
    BASE + '/api/common/img/480/orig/upload/' + HEX + '.png?' + QUERY)
  // 参数顺序不同也认
  const reordered = 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg?OSSAccessKeyId=a&Signature=b&Expires=1'
  assert.ok(imageUrl.sized(reordered, 'thumb', { dpr: 2, base: BASE }).startsWith(BASE + '/api/common/img/240/orig/'))
})

test('非本桶签名原图一律不动(只升自有域名 https)', () => {
  const untouched = [
    '/images/route_city_cover.png',
    'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/t1.png',
    'https://www.chengyinhub.com/prod-api/profile/upload/2026/01/01/a.jpg',
    'https://example.com/upload/' + HEX + '.jpg?' + QUERY,
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg',
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.gif?' + QUERY,
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.mp4?' + QUERY,
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/tmp_abc.jpg?' + QUERY,
    SIGNED + '&x-oss-process=image/resize,w_100',
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg?Expires=1&Expires=2&Signature=s',
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg?Expires=&OSSAccessKeyId=a&Signature=s',
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.jpg?x-oss-signature-version=OSS4-HMAC-SHA256&x-oss-credential=a&x-oss-signature=b',
  ]
  for (const src of untouched) assert.equal(imageUrl.sized(src, 'hero', { dpr: 2, base: BASE }), src, src)
  assert.equal(imageUrl.sized('http://www.chengyinhub.com/prod-api/profile/a.png', 'hero', { dpr: 2, base: BASE }),
    'https://www.chengyinhub.com/prod-api/profile/a.png')
  assert.equal(imageUrl.sized('', 'hero'), '')
  assert.equal(imageUrl.sized(null, 'hero'), null)
  assert.equal(imageUrl.sized(SIGNED, 'unknown-slot', { dpr: 2, base: BASE }), SIGNED)
})

test('默认取 App 的接口域名与窗口 DPR', () => {
  const prevApp = global.getApp
  const prevWx = global.wx
  try {
    global.getApp = () => ({ globalData: { siteBaseUrl: 'https://test.example/api/' } })
    global.wx = { getWindowInfo: () => ({ pixelRatio: 3 }) }
    imageUrl.resetForTest()
    assert.equal(imageUrl.sized(SIGNED, 'half'), 'https://test.example/api/api/common/img/640/orig/upload/' + HEX + '.jpg?' + QUERY)
    assert.deepEqual(imageUrl.wxsContext(), { base: 'https://test.example/api', dpr: 3 })
    global.getApp = () => ({ globalData: { siteBaseUrl: 'http://insecure.example' } })
    assert.ok(imageUrl.sized(SIGNED, 'half').startsWith('https://www.chengyinhub.com/prod-api/api/common/img/'))
  } finally {
    global.getApp = prevApp
    global.wx = prevWx
    imageUrl.resetForTest()
  }
})

test('img.wxs fit() 与 sized() 逐条对拍', () => {
  const wxs = loadImgWxs()
  const corpus = [SIGNED, SIGNED.replace('https:', 'http:'), SIGNED.replace('.jpg?', '.png?'),
    '/images/a.png', 'https://example.com/a.jpg', SIGNED + '&x-oss-process=a', '',
    'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + HEX + '.gif?' + QUERY]
  for (const dpr of [1, 2, 2.75, 3]) {
    for (const slot of Object.keys(imageUrl.SLOTS).concat([120])) {
      for (const webp of [false, true]) {
        for (const src of corpus) {
          assert.equal(wxs.fit(src, { base: BASE, dpr }, slot, webp), imageUrl.sized(src, slot, { dpr, base: BASE, webp }) || '',
            [src, slot, dpr, webp].join(' | '))
        }
      }
    }
  }
  assert.equal(wxs.fit(SIGNED, null, 'hero'), SIGNED, '没有上下文时不改写')
  assert.equal(wxs.fit(null, { base: BASE, dpr: 2 }, 'hero'), '')
})
