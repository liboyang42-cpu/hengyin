#!/usr/bin/env node
'use strict'

const { buildLedger, sourceDigestFor, validateEvidenceOverlay } = require('./build-action-ledger')
const { mergeFragments } = require('./action-evidence-store')

try {
  const ledger = buildLedger()
  const overlay = mergeFragments(require('../lib/xcx-scan').ROOT, ledger, validateEvidenceOverlay, sourceDigestFor)
  process.stdout.write(`ACTION-EVIDENCE MERGE PASS: ${Object.keys(overlay.results).length} completed actions\n`)
} catch (error) {
  console.error(`ACTION-EVIDENCE MERGE FAIL: ${error.message}`)
  process.exitCode = 1
}
