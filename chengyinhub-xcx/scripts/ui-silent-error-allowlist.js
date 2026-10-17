'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { parseAllowlist } = require('../../ci/silent-error-gate')

// 两道门禁共享已审阅的精确计数与原因，避免另一本名单漂移。
const { entries, problems } = parseAllowlist(fs.readFileSync(path.resolve(__dirname, '../../ci/silent-error-allowlist.txt'), 'utf8'))
if (problems.length) throw new Error(problems.join('\n'))
module.exports = [...entries]
  .filter(([file, entry]) => file.startsWith('chengyinhub-xcx/') && entry.allowed > 0)
  .map(([file, entry]) => ({ file: file.slice('chengyinhub-xcx/'.length), count: entry.allowed,
    reason: entry.lines.filter(line => line.count > 0).map(line => line.reason).join('；') }))
