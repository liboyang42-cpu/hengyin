'use strict'

const { exactSessionId } = require('./roam-recovery.js')

function tileCursor(value) {
  if (value === 0 || value === '0') return 0
  if (typeof value !== 'number' && typeof value !== 'string') return null
  const exact = exactSessionId(value)
  if (!exact) return null
  return Number.isSafeInteger(Number(exact)) ? Number(exact) : exact
}

// Inputs are validated cursors; compare without rounding Long values or requiring BigInt.
function compareCursors(a, b) {
  const x = String(a), y = String(b)
  return x.length !== y.length ? x.length - y.length : x === y ? 0 : x < y ? -1 : 1
}

module.exports = { tileCursor, compareCursors }
