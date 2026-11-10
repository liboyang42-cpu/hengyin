// r2-create(2026-11-10):素材来源声明放进模板配置(业主答「放到模板配置里」)。
// 钉住:来源类型白名单(不给选 unknown)、授权说明必填(服务端公开校验同口径)、长度上限、素材编号形状;
// 页面:只有本次上传拿到素材编号才出「素材来源」行,保存走 PUT /api/media/asset/{id}/provenance。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const p = require('../../pages/publish/utils/publish/media-provenance.js')

test('来源类型四选一,不给作者选「来源不明」', () => {
  assert.deepEqual(p.TYPES.map((t) => t.value), ['original', 'licensed', 'ai-generated', 'public-domain'])
  assert.equal(p.label('ai-generated'), 'AI 生成')
  assert.equal(p.label('unknown'), '')
})

test('校验:必须选来源、写授权说明;超长拒绝;只带有值的字段', () => {
  assert.equal(p.payload({}).error, '请选择素材来源')
  assert.equal(p.payload({ sourceType: 'original' }).error, '请写明授权说明')
  assert.equal(p.payload({ sourceType: 'original', licenseNote: 'x'.repeat(2001) }).error, '授权说明最多 2000 字')
  assert.equal(p.payload({ sourceType: 'original', licenseNote: '本人拍摄', attribution: 'y'.repeat(256) }).error, '署名最多 255 字')
  assert.deepEqual(p.payload({ sourceType: 'original', licenseNote: ' 本人拍摄 ', attribution: ' ' }).data, { sourceType: 'original', licenseNote: '本人拍摄' })
  assert.deepEqual(p.payload({ sourceType: 'licensed', licenseNote: 'CC BY 4.0', attribution: '摄影 张三' }).data,
    { sourceType: 'licensed', licenseNote: 'CC BY 4.0', attribution: '摄影 张三' })
})

test('素材编号只认正整数', () => {
  assert.equal(p.assetIdOf(42), '42')
  assert.equal(p.assetIdOf('0'), '')
  assert.equal(p.assetIdOf('media-asset:3'), '')
  assert.equal(p.assetIdOf(null), '')
})

test('页面:封面上传带回素材编号,「素材来源」行只在有编号时出现,保存写 PUT provenance', () => {
  const dir = path.join(__dirname, '../../pages/publish/temp')
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.match(js, /mapResult: function \(data\) \{ return \{ url: data\.url, assetId: data\.assetId \}; \}/)
  assert.match(js, /url: '\/api\/media\/asset\/' \+ assetId \+ '\/provenance', method: 'PUT'/)
  assert.match(wxml, /<view class="cg-prov-row" wx:if="\{\{coverProvenance\.assetId\}\}" bindtap="openProvenance"/)
  assert.match(wxml, /class="cg-prov__close" bindtap="cgDismiss" data-close="closeProvenance"/)
  assert.match(wxml, /bind:change="pickProvenanceType"/)
})
