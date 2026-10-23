'use strict';
// Read-only counterfactual: run the same tests with only the original public Page source.
// cwd=chengyinhub-xcx; C_PHOTO_ORIGINAL_SOURCE points to a git-show extracted file.
const fs = require('node:fs');
const path = require('node:path');
const read = fs.readFileSync;
const target = path.resolve(process.cwd(), 'pages/play/index.js');
const original = read(process.env.C_PHOTO_ORIGINAL_SOURCE);
fs.readFileSync = function (filename, options) {
  if (typeof filename === 'string' && path.resolve(filename) === target) {
    const encoding = typeof options === 'string' ? options : options && options.encoding;
    return encoding ? original.toString(encoding) : Buffer.from(original);
  }
  return read.apply(this, arguments);
};
