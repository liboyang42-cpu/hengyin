'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { ticketWindowState } = require('../../utils/ticket-window')
const { toTimestamp } = require('../../utils/datetime')

test('server-confirmed self-guided tickets remain available within the window', () => {
  const now = toTimestamp('2026-09-28 12:00:00')
  const ticket = { startTime: '2026-09-27 00:00:00', endTime: '2026-10-04 23:00:00' }
  assert.equal(ticketWindowState(ticket, now), 'started')
  assert.equal(ticketWindowState({ ...ticket, selfGuided: 'true' }, now), 'started')
  const self = { ...ticket, selfGuided: true }
  assert.equal(ticketWindowState(self, now), '')
  assert.equal(ticketWindowState(self, toTimestamp(self.endTime)), 'ended')
  assert.equal(ticketWindowState({ ...self, endTime: null }, now), 'ended')
  assert.equal(ticketWindowState({ ...self, signupDeadline: '2026-09-28 12:00:00' }, now), 'closed')
})
