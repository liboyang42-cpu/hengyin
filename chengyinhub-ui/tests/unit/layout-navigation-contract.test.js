const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.join(__dirname, '../../src')
const navbarSource = fs.readFileSync(path.join(uiRoot, 'layout/components/Navbar.vue'), 'utf8')
const sidebarStyleSource = fs.readFileSync(path.join(uiRoot, 'assets/styles/sidebar.scss'), 'utf8')

test('当前侧栏状态同时使用背景、定位标记和文字权重', () => {
  const lightActiveRule = sidebarStyleSource.match(/& \.theme-light \.el-menu-item\.is-active,[\s\S]*?\n    }/)

  assert.ok(lightActiveRule)
  assert.match(lightActiveRule[0], /font-weight:\s*700/)
  assert.match(sidebarStyleSource, /theme-light \.el-menu-item\.is-active::before/)
  assert.match(sidebarStyleSource, /theme-light \.is-active > \.el-submenu__title::before/)
})

test('顶部搜索明确是菜单搜索，不伪装成业务全局搜索', () => {
  assert.match(navbarSource, /content="菜单搜索"/)
  assert.doesNotMatch(navbarSource, /content="全局搜索"/)
})
