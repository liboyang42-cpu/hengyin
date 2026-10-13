/**
 * R9-18 回归: 后台前端每个 .vue 的单文件模板必须能被真实 Vue 编译器编译。
 *
 * 背景: registrationmerchant/dispatch.vue 在 <cy-confirm-action> 与 <template v-else>
 * 之间插入了兄弟节点, 使 v-else 失去相邻的 v-if, 整份后台无法构建。
 * 这里用与 build:prod 同一个 vue-template-compiler (package.json 声明的 2.7.x)
 * 逐文件编译模板, 而不是用肉眼或正则判断。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const compiler = require(path.join(uiRoot, 'node_modules/vue-template-compiler'))
const vueVersion = require(path.join(uiRoot, 'node_modules/vue/package.json')).version

function collectVueFiles(dir, acc) {
  for (const entry of fs.readdirSync(dir)) {
    const full = path.join(dir, entry)
    const stat = fs.statSync(full)
    if (stat.isDirectory()) collectVueFiles(full, acc)
    else if (entry.endsWith('.vue')) acc.push(full)
  }
  return acc
}

test('R9-18 所有后台单文件组件模板都能被真实 Vue 编译器编译', () => {
  const files = collectVueFiles(path.join(uiRoot, 'src'), [])
  assert.ok(files.length > 100, `应扫描到全部视图/组件, 实际 ${files.length} 个 .vue`)

  const failures = []
  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8')
    const sfc = compiler.parseComponent(source)
    if (!sfc.template) continue
    const result = compiler.compile(sfc.template.content)
    if (result.errors && result.errors.length) {
      failures.push({
        file: path.relative(uiRoot, file),
        errors: result.errors
      })
    }
  }

  assert.deepEqual(
    failures,
    [],
    `Vue ${vueVersion} 编译失败文件:\n${failures.map(item => `${item.file}: ${item.errors.join(' | ')}`).join('\n')}`
  )
})

test('R9-18 dispatch.vue 的驳回确认弹层独立于加载错误态, 不与列表互斥', () => {
  const source = fs.readFileSync(
    path.join(uiRoot, 'src/views/business/registrationmerchant/dispatch.vue'),
    'utf8'
  )
  const confirmActionIndex = source.indexOf('<cy-confirm-action')
  const resourceStateIndex = source.indexOf('<cy-resource-state')
  const elseIndex = source.indexOf('<template v-else>')

  assert.ok(confirmActionIndex > -1, 'dispatch.vue 保留驳回确认弹层')
  assert.ok(resourceStateIndex > -1, 'dispatch.vue 保留加载错误态')
  assert.ok(elseIndex > -1, 'dispatch.vue 保留列表 v-else 分支')
  assert.ok(
    confirmActionIndex < resourceStateIndex || confirmActionIndex > elseIndex,
    '确认弹层不能夹在 v-if 与 v-else 之间, 否则 v-else 失去相邻 v-if'
  )
})
