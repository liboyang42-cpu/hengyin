const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// ONB-5:控制台在打卡码旁画「App 与微信通用码」,内容与后端 linkQr 同一链接。
const source = fs.readFileSync(path.resolve(__dirname, '../../src/components/CyLinkQrCode/index.vue'), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .+$/gm, '')
  .replace(/export\s+default\s*/, 'return ')
const component = new Function('QRCode', script)({})

test('通用码链接与后端 IPlayerWxacodeService.linkFor 一致', () => {
  const target = component.computed.target
  assert.equal(target.call({ link: '', scene: 'abcdef0123456789abcdef0123456789' }),
    'https://www.chengyinhub.com/door?scene=abcdef0123456789abcdef0123456789')
  assert.equal(target.call({ link: '', scene: 'tm.a&b' }), 'https://www.chengyinhub.com/door?scene=tm.a%26b')
  assert.equal(target.call({ link: 'https://www.chengyinhub.com/door?scene=sh.1', scene: 'x' }),
    'https://www.chengyinhub.com/door?scene=sh.1')
  assert.equal(target.call({ link: '', scene: '' }), '')
})

test('依赖精确锁版本,节点弹窗挂上通用码', () => {
  const pkg = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../package.json'), 'utf8'))
  assert.equal(pkg.dependencies.qrcode, '1.5.4')
  const detail = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/topic/detail.vue'), 'utf8')
  assert.match(detail, /<cy-link-qr-code :scene="nodeForm\.checkinCode" \/>/)
  assert.match(source, /App 与微信通用码/)
})
