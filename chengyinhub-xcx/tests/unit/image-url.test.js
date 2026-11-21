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
    assert.deepEqual(imageUrl.wxsContext(), { base: 'https://test.example/api', dpr: 3, off: false, skip: {} })
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

function withEnv(globalData, wxApi, fn) {
  const prevApp = global.getApp
  const prevWx = global.wx
  try {
    global.getApp = globalData === undefined ? undefined : () => (globalData === null ? null : { globalData })
    global.wx = wxApi
    imageUrl.resetForTest()
    return fn()
  } finally {
    global.getApp = prevApp
    global.wx = prevWx
    imageUrl.resetForTest()
  }
}

test('基址跟 app.sendRequest 同源:http 测试服照用,正式服不变,完全没配才回落正式域名', () => {
  const wxNoExt = { getWindowInfo: () => ({ pixelRatio: 2 }) }
  // 测试 / 开发构建:http 回环测试服也必须用它,不能把图片请求打到正式服
  withEnv({ siteBaseUrl: 'http://127.0.0.1:18080/dev-api/' }, wxNoExt, () => {
    assert.equal(imageUrl.sized(SIGNED, 'half'), 'http://127.0.0.1:18080/dev-api/api/common/img/480/orig/upload/' + HEX + '.jpg?' + QUERY)
    assert.equal(imageUrl.wxsContext().base, 'http://127.0.0.1:18080/dev-api')
    assert.equal(loadImgWxs().fit(SIGNED, imageUrl.wxsContext(), 'half', false), imageUrl.sized(SIGNED, 'half'))
  })
  withEnv({ siteBaseUrl: 'https://staging.example/stage-api' }, wxNoExt, () => {
    assert.equal(imageUrl.wxsContext().base, 'https://staging.example/stage-api')
  })
  // release:siteBaseUrl 就是正式域名,结果与改动前完全一致
  const config = require('../../utils/config.js')
  const release = config.resolveRuntimeConfig('release', {})
  withEnv({ siteBaseUrl: release.apiBaseUrl, runtimeConfig: release }, wxNoExt, () => {
    assert.equal(imageUrl.wxsContext().base, 'https://www.chengyinhub.com/prod-api')
    assert.equal(imageUrl.sized(SIGNED, 'hero'), 'https://www.chengyinhub.com/prod-api/api/common/img/750/orig/upload/' + HEX + '.jpg?' + QUERY)
  })
  // siteBaseUrl 缺失时先用 globalData.runtimeConfig
  withEnv({ runtimeConfig: { apiBaseUrl: 'http://10.0.0.2:8080' } }, wxNoExt, () => {
    assert.equal(imageUrl.wxsContext().base, 'http://10.0.0.2:8080')
  })
  // App 未就绪:按同一份运行配置(ext.json)解析
  const wxDevExt = {
    getWindowInfo: () => ({ pixelRatio: 2 }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getExtConfigSync: () => ({ apiEnvironments: { develop: 'https://dev.example/dev-api' } }),
  }
  withEnv(null, wxDevExt, () => {
    assert.equal(imageUrl.wxsContext().base, 'https://dev.example/dev-api')
  })
  // 完全没配(无 App、无 wx)才回落正式域名
  withEnv(undefined, undefined, () => {
    assert.equal(imageUrl.wxsContext().base, imageUrl.DEFAULT_API_BASE)
  })
  withEnv({ siteBaseUrl: '   ' }, undefined, () => {
    assert.equal(imageUrl.wxsContext().base, imageUrl.DEFAULT_API_BASE)
  })
})

const signedOf = (n) => 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/' + String(n).repeat(32).slice(0, 32) + '.jpg?' + QUERY

function fakePage(ctx) {
  const page = { data: { imgCtx: ctx }, calls: 0 }
  page.setData = function (patch) { page.calls += 1; Object.assign(page.data, patch) }
  return page
}

function fireError(page, raw) {
  imageUrl.onImgRenditionError.call(page, { currentTarget: { dataset: { raw } } })
}

test('binderror:缩略图出错该图退回原签名图,只换一次,原图再错不循环', () => {
  withEnv({ siteBaseUrl: 'http://127.0.0.1:18080' }, { getWindowInfo: () => ({ pixelRatio: 2 }) }, () => {
    const wxs = loadImgWxs()
    const page = fakePage(imageUrl.wxsContext())
    const other = signedOf('b')
    assert.ok(wxs.fit(SIGNED, page.data.imgCtx, 'hero', true).includes('/api/common/img/'), '先改写')

    fireError(page, SIGNED)
    assert.equal(page.calls, 1)
    assert.equal(wxs.fit(SIGNED, page.data.imgCtx, 'hero', true), SIGNED, '出错后同一张图换回原签名地址')
    assert.equal(wxs.fit(SIGNED.replace('https:', 'http:'), page.data.imgCtx, 'card', false), SIGNED, '该文件其他槽位也用原图')
    assert.ok(wxs.fit(other, page.data.imgCtx, 'hero', true).includes('/api/common/img/'), '其他图照常改写')
    assert.equal(imageUrl.sized(SIGNED, 'hero'), SIGNED, 'JS 侧同步跳过该文件')

    // 原图本身也坏:不再 setData,不计数
    fireError(page, SIGNED)
    fireError(page, SIGNED)
    assert.equal(page.calls, 1, '只换一次,不循环')
    assert.equal(imageUrl.breakerTripped(), false)

    // 别的页面拿着旧 ctx 出错同一文件:换成新 ctx,但不重复计数
    const stale = fakePage({ base: 'http://127.0.0.1:18080', dpr: 2, off: false, skip: {} })
    fireError(stale, SIGNED)
    assert.equal(stale.calls, 1)
    assert.equal(wxs.fit(SIGNED, stale.data.imgCtx, 'hero', true), SIGNED)

    // 非改写图(本地图 / 外域 / 空)出错一概不处理
    for (const raw of ['/images/a.png', 'https://example.com/a.jpg', '', undefined]) fireError(page, raw)
    assert.equal(page.calls, 1)
    // 本来就没改写(无 ctx)的页面也不处理
    const plain = fakePage(null)
    fireError(plain, SIGNED)
    assert.equal(plain.calls, 0)
    assert.equal(imageUrl.breakerTripped(), false)
  })
})

test('熔断:本会话累计 BREAKER_LIMIT 个文件缩略图出错后不再改写任何图片', () => {
  withEnv({ siteBaseUrl: 'https://www.chengyinhub.com/prod-api' }, { getWindowInfo: () => ({ pixelRatio: 2 }) }, () => {
    const wxs = loadImgWxs()
    assert.equal(imageUrl.BREAKER_LIMIT, 3)
    const page = fakePage(imageUrl.wxsContext())
    const files = ['1', '2', '3', '4'].map(signedOf)
    fireError(page, files[0])
    fireError(page, files[1])
    assert.equal(imageUrl.breakerTripped(), false)
    assert.ok(wxs.fit(files[3], page.data.imgCtx, 'hero', true).includes('/api/common/img/'), '未到阈值照常改写')
    fireError(page, files[2])
    assert.equal(imageUrl.breakerTripped(), true)
    assert.equal(page.data.imgCtx.off, true)
    for (const f of files) assert.equal(wxs.fit(f, page.data.imgCtx, 'hero', true), f, '熔断后模板全部用原图')
    assert.equal(imageUrl.sized(files[3], 'card', { webp: true }), files[3], '熔断后 JS 侧也不改写')
    assert.equal(imageUrl.wxsContext().off, true, '之后新开页面拿到的 ctx 也是熔断态')
    // 熔断后出错的是原图,不再 setData
    const calls = page.calls
    fireError(page, files[3])
    assert.equal(page.calls, calls)
  })
})

test('改写过的 <image> 都挂了 binderror 退回原图,页面 / 组件都接了共用处理', () => {
  const targets = {
    'pages/index/index': 'page', 'pages/play/index': 'page', 'pages/topic/index/index': 'page', 'components/cy/post-card/index': 'component',
  }
  for (const [base, kind] of Object.entries(targets)) {
    const wxml = fs.readFileSync(path.join(root, base + '.wxml'), 'utf8')
    const tags = (wxml.match(/<image\b[\s\S]*?(?:\/>|<\/image>)/g) || []).filter((t) => t.includes('img.fit('))
    assert.ok(tags.length > 0, base)
    for (const tag of tags) {
      assert.match(tag, /binderror="onImgRenditionError"/, base + ': ' + tag.slice(0, 120))
      const arg = tag.match(/src="\{\{\s*img\.fit\((.*?), imgCtx,/)
      assert.ok(arg, base + ': fit 用 imgCtx ' + tag.slice(0, 120))
      assert.ok(tag.includes('data-raw="{{' + arg[1] + '}}"'), base + ': data-raw 与 fit 首参一致 ' + tag.slice(0, 120))
    }
    const js = fs.readFileSync(path.join(root, base + '.js'), 'utf8')
    if (kind === 'page') assert.match(js, /onImgRenditionError\(e\) \{ imageUrl\.onImgRenditionError\.call\(this, e\) \}/, base)
    else assert.match(js, /onImgRenditionError\(e\) \{ if \(imageUrl\) imageUrl\.onImgRenditionError\.call\(this, e\)/, base)
  }
})
