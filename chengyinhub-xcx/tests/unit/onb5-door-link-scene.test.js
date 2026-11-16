'use strict'

// ONB-5:印在小程序码旁的标准二维码 https://www.chengyinhub.com/door?scene=<scene>,
// 微信「扫普通链接二维码打开小程序」后整条链接在 options.q,取出的 scene 与小程序码相同。
const test = require('node:test')
const assert = require('node:assert/strict')
const { sceneFromDoorLink, withDoorLinkScene, captureDoorScene } = require('../../utils/game-door-entry.js')

const HEX = 'abcdef0123456789abcdef0123456789'
const q = (link) => encodeURIComponent(link)

test('standard door link yields the same scene as the mini-program code', () => {
  assert.equal(sceneFromDoorLink(q('https://www.chengyinhub.com/door?scene=sh.12')), 'sh.12')
  assert.equal(sceneFromDoorLink(q('https://www.chengyinhub.com/door?scene=' + HEX)), HEX)
  assert.equal(sceneFromDoorLink(q('https://www.chengyinhub.com/door?scene=tm.a%26b')), 'tm.a&b')
  assert.equal(sceneFromDoorLink('https://www.chengyinhub.com/door?scene=tp.7'), 'tp.7')
})

test('other links and malformed scenes are not scenes', () => {
  for (const link of [
    'https://evil.example/door?scene=sh.12',
    'https://www.chengyinhub.com/doorx?scene=sh.12',
    'http://www.chengyinhub.com/door?scene=sh.12',
    'https://www.chengyinhub.com/door?scene=',
    'https://www.chengyinhub.com/door?scene=a&scene=b',
    'https://www.chengyinhub.com/door?inviter=3',
    'https://www.chengyinhub.com/door?scene=' + 'x'.repeat(33),
  ]) assert.equal(sceneFromDoorLink(q(link)), null, link)
  assert.equal(sceneFromDoorLink(null), null)
  assert.equal(sceneFromDoorLink('%E0%A4%A'), null)
})

test('page options keep a real scene and only fill it from q', () => {
  assert.deepEqual(withDoorLinkScene({ q: q('https://www.chengyinhub.com/door?scene=ac.5') }).scene, 'ac.5')
  assert.equal(withDoorLinkScene({ scene: 'tp.1', q: q('https://www.chengyinhub.com/door?scene=ac.5') }).scene, 'tp.1')
  assert.equal(withDoorLinkScene(undefined), undefined)
  const plain = { id: '3' }
  assert.equal(withDoorLinkScene(plain), plain)
})

test('app-level capture accepts link-QR entries 1011-1013 only', () => {
  const link = q('https://www.chengyinhub.com/door?scene=' + HEX)
  assert.equal(captureDoorScene({ scene: 1011, query: { q: link } }, null), HEX)
  assert.equal(captureDoorScene({ scene: 1012, query: { q: q('https://www.chengyinhub.com/door?scene=sh.12') } }, null), 'sh.12')
  assert.equal(captureDoorScene({ scene: 1089, query: { q: link } }, null), null)
  assert.equal(captureDoorScene({ scene: 1011, query: { q: q('https://evil.example/door?scene=' + HEX) } }, 'keep'), 'keep')
})
