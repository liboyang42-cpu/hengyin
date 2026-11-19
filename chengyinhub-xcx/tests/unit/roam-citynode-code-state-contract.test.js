// PG-091:citynode-code 页改为 cy-scene-qr-citynode 薄壳后,出码错误态的合同落在场景组件上(页面不再自己出码)。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')

const SCENE_MODULE = '../../components/cy/scene-qr-citynode/index.js'

let componentConfig
let requests

function loadScene() {
  delete require.cache[require.resolve(SCENE_MODULE)]
  require(SCENE_MODULE)
  const scene = Object.assign({}, componentConfig.methods, {
    data: JSON.parse(JSON.stringify(componentConfig.data)),
  })
  scene.setData = (patch, cb) => { Object.assign(scene.data, patch); if (cb) cb() }
  return scene
}

beforeEach(() => {
  componentConfig = null
  requests = []
  global.Component = (config) => { componentConfig = config }
  global.getApp = () => ({
    sendRequest: (request) => { requests.push(request) },
  })
  global.wx = {}
})

test('据点码 HTTP 异常必须离开 loading，且不向用户透传内部错误', () => {
  const scene = loadScene()
  scene.issue('poi-1')

  assert.equal(requests.length, 1)
  assert.equal(typeof requests[0].successStatusAbnormal, 'function')

  requests[0].successStatusAbnormal({ statusCode: 502, msg: '/api/verify/citynode/issue upstream timeout' })

  assert.equal(scene.data.state, 'error')
  assert.equal(scene.data.errorText, '暂时无法生成核销码，请稍后重试')
  assert.equal(scene.data.qr, '')
  assert.equal(scene.data.code, '')
})

test('据点码业务失败同样使用用户可理解的重试文案', () => {
  const scene = loadScene()
  scene.issue('poi-1')

  requests[0].success({ code: 500, msg: 'permission denied: /api/verify/citynode/issue' })

  assert.equal(scene.data.state, 'error')
  assert.equal(scene.data.errorText, '暂时无法生成核销码，请稍后重试')
})
