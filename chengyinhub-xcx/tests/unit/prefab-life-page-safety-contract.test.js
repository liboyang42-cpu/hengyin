'use strict'

// 预制人生独立分包(subpackagePrefab)已退役,第一站改由发布页配成真主题;
// 原 F18/F21/F22 只测那个分包,随分包一起删。游玩页 mock 只在开发环境生效这条仍然有效。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const playJs = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')

test('F20 游玩页 mock 只在开发环境生效', () => {
  assert.match(playJs, /options\.mock && app\.isDevEnv && app\.isDevEnv\(\)/)
})
