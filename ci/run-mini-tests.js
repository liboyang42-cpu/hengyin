#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');

const shard = Number(process.argv[2]);
const shards = Number(process.argv[3]);
if (!Number.isInteger(shard) || !Number.isInteger(shards) || shard < 1 || shard > shards || shards > 20)
  throw new Error('invalid test partition');
const plan = JSON.parse(fs.readFileSync(path.join(root, '.ci-impact.json'), 'utf8'));
const files = plan.tests.filter((_, index) => index % shards === shard - 1);
console.log(`mini impact partition ${shard}/${shards}: ${files.length} files`);
if (!files.length) process.exit(0);
const arguments_ = plan.full
  ? ['--test', '--test-timeout=60000', `--test-shard=${shard}/20`, 'tests/unit/**/*.test.js']
  : ['--test', '--test-timeout=60000', ...files];
const child = cp.spawn(process.execPath, arguments_, {
  cwd: path.join(root, 'chengyinhub-xcx'), stdio: 'inherit',
});
// Module initialization and leaked handles also need an outer bound; Node's per-test timer cannot stop them.
const timer = setTimeout(() => {
  console.error('mini partition exceeded 8 minutes (including module initialization / leaked handles)');
  child.kill('SIGKILL');
}, 8 * 60 * 1000);
child.on('error', error => { clearTimeout(timer); console.error(error); process.exitCode = 1; });
child.on('exit', (code, signal) => { clearTimeout(timer); process.exitCode = signal ? 1 : code; });
