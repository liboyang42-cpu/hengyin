#!/usr/bin/env node
'use strict';

/*
 * 上传前把个性化地图 key 与四套样式序号填进「上传用的那份拷贝」(第九轮 m1 第 1 项;m4 第 2 项改为四套)。
 *
 * 仓库里的 utils/map-style.js 永远是 subkey: '' 且四个序号为 0。本脚本只改 --dir 指向的拷贝,
 * 拒绝改仓库本身,也不把 key 打印出来。
 *
 *   CY_MAP_SUBKEY        腾讯位置服务的个性化地图 key(整个小程序共用这一个)
 *   CY_MAP_STYLE_BLACK   black:漫游、附近、玩家搜索地图,星尘 / 末世 / 没设外观的主题(旧名 CY_MAP_LAYER_STYLE 仍认)
 *   CY_MAP_STYLE_WHITE   white:商家与编辑器地图(旧名 CY_MAP_LIGHT_STYLE 仍认)
 *   CY_MAP_STYLE_PAPER   paper:主题外观 纸本
 *   CY_MAP_STYLE_2077    2077:主题外观 夜城
 *   序号 = 该样式绑到这个 key 之后的序号(从 1 起)。
 *
 *   node scripts/release-map-style.js --dir <上传拷贝目录> [--require]
 *
 * --require:五项缺任何一项就失败(发布流水线用)。不带 --require 时全空 = 保持官方默认底图。
 * 给了 key 却缺任何一个序号永远失败:缺的那种地图会落到别的样式上。
 */

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..');
const STYLE_FILE = path.join('utils', 'map-style.js');
const BLOCK = /const MAP_STYLE = \{\n  subkey: '[^'\n]*',\n  black: \d+,\n  white: \d+,\n  paper: \d+,\n  cyber: \d+,\n\};/;
const SLOTS = [
  ['black', 'CY_MAP_STYLE_BLACK', 'CY_MAP_LAYER_STYLE'],
  ['white', 'CY_MAP_STYLE_WHITE', 'CY_MAP_LIGHT_STYLE'],
  ['paper', 'CY_MAP_STYLE_PAPER'],
  ['cyber', 'CY_MAP_STYLE_2077'],
];

function parseArgs(argv) {
  const out = { dir: '', require: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--dir') out.dir = argv[++i] || '';
    else if (argv[i] === '--require') out.require = true;
    else throw new Error('未知参数: ' + argv[i]);
  }
  return out;
}

function readEnv(env) {
  const out = { subkey: String(env.CY_MAP_SUBKEY || '').trim() };
  SLOTS.forEach(([field, name, legacy]) => {
    out[field] = String(env[name] || (legacy ? env[legacy] : '') || '').trim();
  });
  return out;
}

function validate(values, requireAll) {
  const missing = [];
  if (!values.subkey) missing.push('CY_MAP_SUBKEY');
  SLOTS.forEach(([field, name]) => { if (!values[field]) missing.push(name); });
  if (requireAll && missing.length) throw new Error('缺少环境变量: ' + missing.join(', '));
  const anyStyle = SLOTS.some(([field]) => values[field]);
  if (!values.subkey) {
    if (anyStyle) throw new Error('给了样式序号却没有 CY_MAP_SUBKEY');
    return null;
  }
  if (missing.length) throw new Error('给了 CY_MAP_SUBKEY 但缺少: ' + missing.join(', ') + '(缺的那种地图会落到别的样式上)');
  if (!/^[A-Za-z0-9-]{10,64}$/.test(values.subkey)) throw new Error('CY_MAP_SUBKEY 格式不对');
  const out = { subkey: values.subkey };
  const seen = {};
  SLOTS.forEach(([field, name]) => {
    const n = Number(values[field]);
    if (!Number.isInteger(n) || n < 1) throw new Error(name + ' 必须是从 1 起的整数');
    if (seen[n]) throw new Error(seen[n] + ' 与 ' + name + ' 序号相同');
    seen[n] = name;
    out[field] = n;
  });
  return out;
}

function render(source, style) {
  if (!BLOCK.test(source)) throw new Error(STYLE_FILE + ' 里找不到 MAP_STYLE 配置块');
  return source.replace(BLOCK, "const MAP_STYLE = {\n  subkey: '" + style.subkey + "',\n"
    + SLOTS.map(([field]) => '  ' + field + ': ' + style[field] + ',\n').join('') + '};');
}

function assertUploadCopy(dir) {
  if (!dir) throw new Error('必须用 --dir 指定上传用的拷贝目录');
  const abs = fs.realpathSync(path.resolve(dir));
  const repo = fs.realpathSync(REPO_ROOT);
  const gitRoot = path.dirname(repo);
  if (abs === repo || abs === gitRoot) throw new Error('拒绝改仓库本身,请先拷贝一份再上传');
  const file = path.join(abs, STYLE_FILE);
  if (!fs.existsSync(file)) throw new Error('上传拷贝里没有 ' + STYLE_FILE);
  return file;
}

function run(argv, env) {
  const args = parseArgs(argv);
  const style = validate(readEnv(env), args.require);
  const file = assertUploadCopy(args.dir);
  if (!style) return { changed: false, file };
  const before = fs.readFileSync(file, 'utf8');
  const after = render(before, style);
  fs.writeFileSync(file, after);
  return { changed: true, file, black: style.black, white: style.white, paper: style.paper, cyber: style.cyber };
}

if (require.main === module) {
  try {
    const r = run(process.argv.slice(2), process.env);
    if (r.changed) console.log('[map-style] 已写入上传拷贝: black ' + r.black + ' / white ' + r.white + ' / paper ' + r.paper + ' / 2077 ' + r.cyber);
    else console.log('[map-style] 未配置 key,保持官方默认底图');
  } catch (e) {
    console.error('[map-style] 失败: ' + e.message);
    process.exit(1);
  }
}

module.exports = { run, validate, render, readEnv };
