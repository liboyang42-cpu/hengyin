'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

test('the renderer loads its existing Puppeteer API from the frozen lock', () => {
  const manifest = require('./package.json');
  const lock = require('./package-lock.json');
  const puppeteer = require('puppeteer-core');
  assert.equal(lock.packages['node_modules/puppeteer-core'].version, manifest.dependencies['puppeteer-core']);
  assert.equal(typeof puppeteer.launch, 'function');
});

test('the actual BiDi validator rejects oversized collections before walking elements', () => {
  // Resolve from BiDi itself so a separate hoisted Zod cannot mask a vulnerable copy.
  const bidiPackage = path.join(__dirname, 'node_modules/chromium-bidi/package.json');
  assert.ok(fs.existsSync(bidiPackage));
  const { z } = createRequire(bidiPackage)('zod');
  let visited = 0;
  const element = z.unknown().superRefine(() => { visited++; });
  const result = z.array(element).max(2).safeParse([0, 0, 0]);
  assert.equal(result.success, false);
  assert.equal(visited, 0);
  assert.equal(result.error.issues.length, 1);
  assert.equal(result.error.issues[0].code, 'too_big');
  assert.equal(z.array(element).max(2).safeParse([0, 0]).success, true);
  assert.equal(visited, 2);
});
