const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

const ruoyiSource = fs.readFileSync(path.join(uiRoot, 'src/utils/ruoyi.js'), 'utf8')
const realToTimestamp = new Function(ruoyiSource.replace(/^export function /gm, 'function ') + '\nreturn toTimestamp;')()

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadChapterOffer() {
  const source = read('src/views/business/chapteroffer/index.vue')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ offerQueue, adjustOffer, deactivateOffer \} from "@\/api\/business\/chapterOffer";$/m, 'const { offerQueue, adjustOffer, deactivateOffer } = dependencies;')
    .replace(/^import \{ toTimestamp \} from '@\/utils\/ruoyi'$/m, '')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', 'toTimestamp', script.replace(/export\s+default\s*/, 'return '))({
    offerQueue: () => Promise.resolve({ data: [] }),
    adjustOffer: () => Promise.resolve({}),
    deactivateOffer: () => Promise.resolve({})
  }, realToTimestamp)
}

test('商家章节承接档以原列表权限注册唯一的 merchant/chapter-offer 入口', () => {
  const router = read('src/router/index.js')

  assert.match(router, /path:\s*['"]\/merchant\/chapter-offer['"][\s\S]*?permissions:\s*\[['"]business:chapterOffer:list['"]\][\s\S]*?name:\s*['"]merchantChapterOffer['"]/)
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/chapteroffer\/index['"]\)/)
})

test('旧章节承接入口只兼容转发，不在旧地址读取供给队列', () => {
  const component = loadChapterOffer()
  const legacyTargets = []
  let legacyReads = 0
  const legacyVm = Object.assign(component.data(), {
    $route: { path: '/business/chapteroffer', query: { status: '1' } },
    $router: { replace: target => legacyTargets.push(target) },
    getQueue: () => { legacyReads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(legacyVm)

  assert.deepEqual(legacyTargets, [{ name: 'merchantChapterOffer', query: { status: '1' } }])
  assert.equal(legacyReads, 0)
})

test('规范入口继续调用既有队列，并保留三档承接的服务端闸门边界', () => {
  const component = loadChapterOffer()
  let reads = 0
  const vm = Object.assign(component.data(), {
    $route: { path: '/merchant/chapter-offer', query: {} },
    $router: { replace: () => assert.fail('规范入口不应重定向') },
    getQueue: () => { reads += 1 }
  })

  component.methods.ensureCanonicalRoute.call(vm)

  assert.equal(reads, 1)
  const source = read('src/views/business/chapteroffer/index.vue')
  assert.match(source, /created\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /'\$route'\(\)\s*\{\s*this\.ensureCanonicalRoute\(\)/)
  assert.match(source, /v-hasPermi="\['business:chapterOffer:edit'\]"/)
  assert.match(source, /topic\.topicLifecycle >= 3 \|\| s\.row\.status !== 1/)
  assert.match(source, /adjustOffer\(payload\)/)
  assert.match(source, /deactivateOffer\(row\.id\)/)
})
