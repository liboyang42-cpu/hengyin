const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const viewsRoot = path.join(__dirname, '../../src/views/error')
const page401 = fs.readFileSync(path.join(viewsRoot, '401.vue'), 'utf8')
const page404 = fs.readFileSync(path.join(viewsRoot, '404.vue'), 'utf8')
const errorStyle = fs.readFileSync(path.join(__dirname, '../../src/assets/styles/error-pages.scss'), 'utf8')

function loadComponent(source) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

test('401 与 404 都说明原因并提供明确恢复动作', () => {
  for (const source of [page401, page404]) {
    assert.match(source, /<main class="cy-error-page/)
    assert.match(source, /<h1[^>]*>/)
    assert.match(source, /返回工作台/)
    assert.doesNotMatch(source, /错误!/)
  }
})

test('404 不提供可能回到无效地址的伪返回动作', () => {
  assert.match(page404, /@click="goHome">返回工作台/)
  assert.doesNotMatch(page404, /返回上一页|\$router\.go\(-1\)|back\(\)/)
})

test('404 的恢复动作固定回工作台', () => {
  const component = loadComponent(page404)
  const routes = []

  component.methods.goHome.call({ $router: { push: route => routes.push(route) } })

  assert.deepEqual(routes, [{ path: '/' }])
  assert.equal(component.methods.back, undefined)
})

test('错误页使用后台设计系统的响应式信息卡，而不是旧框架插画布局', () => {
  assert.match(errorStyle, /\.cy-error-page__panel/)
  assert.match(errorStyle, /max-width:\s*620px/)
  assert.match(errorStyle, /var\(--cy-color-bg-page\)/)
  assert.match(errorStyle, /@media \(max-width:\s*560px\)/)
  assert.doesNotMatch(page401, /401_images/)
  assert.doesNotMatch(page404, /404_images/)
})
