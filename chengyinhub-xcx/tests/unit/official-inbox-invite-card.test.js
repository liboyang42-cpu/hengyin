'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const card = require('../../pages/activity/official-inbox/invite-card.js')

test('BL-06 角色说明覆盖后端全部角色,未知角色不编造', () => {
  ;['ORGANIZER', 'CO_ORGANIZER', 'FULFILLMENT_MERCHANT', 'FULFILLMENT_CLUB', 'SPONSOR', 'PROMOTION_PARTNER']
    .forEach((role) => assert.ok(card.roleNote(role)))
  assert.equal(card.roleNote('WHATEVER'), '')
})

test('BL-06 名额与暂停只读已有字段', () => {
  assert.equal(card.capacityText(3), '承接名额 3')
  assert.equal(card.capacityText(null), '')
  assert.equal(card.capacityText(0), '')
  assert.equal(card.capacityText('x'), '')
  assert.ok(card.pausedText('PAUSED'))
  assert.equal(card.pausedText('NORMAL'), '')
})

test('BL-06 回读:接受要变为 ACCEPTED/ACTIVE,拒绝/退出要离开收件箱', () => {
  const list = [{ partyId: 1, status: 'ACCEPTED' }, { partyId: 2, status: 'INVITED' }]
  assert.equal(card.readbackMismatch(list, { partyId: 1, action: 'accept' }), false)
  assert.equal(card.readbackMismatch(list, { partyId: 2, action: 'accept' }), true)
  assert.equal(card.readbackMismatch(list, { partyId: 3, action: 'accept' }), true)
  assert.equal(card.readbackMismatch(list, { partyId: 2, action: 'decline' }), true)
  assert.equal(card.readbackMismatch(list, { partyId: 9, action: 'decline' }), false)
  assert.equal(card.readbackMismatch(list, { partyId: 1, action: 'withdraw' }), true)
  assert.equal(card.readbackMismatch(list, null), false)
})

test('BL-06 页面接线', () => {
  const dir = path.join(__dirname, '../../pages/activity/official-inbox')
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.match(js, /this\._expectReadback = \{ partyId, action \};\s*this\.fetch\(\);/)
  assert.match(js, /this\._checkReadback\(invites\);/)
  assert.match(wxml, /class="oi-role-note">\{\{item\._role\}\}：\{\{item\._roleNote\}\}/)
  assert.match(wxml, /wx:if="\{\{item\._paused\}\}"/)
})
