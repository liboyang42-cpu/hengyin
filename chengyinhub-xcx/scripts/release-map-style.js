#!/usr/bin/env node
'use strict';

/*
 * 上传前把个性化地图 key 与样式序号填进「上传用的那份拷贝」(第九轮 m1 第 1 项)。
 *
 * 仓库里的 utils/map-style.js 永远是 subkey: ''。本脚本只改 --dir 指向的拷贝,
 * 拒绝改仓库本身,也不把 key 打印出来。
 *
 *   CY_MAP_SUBKEY        腾讯位置服务的个性化地图 key(整个小程序共用这一个)
 *   CY_MAP_LAYER_STYLE   玩家暗色样式在该 key 下的绑定序号(从 1 起)
 *   CY_MAP_LIGHT_STYLE   商家 / 编辑器浅色样式在同一 key 下的绑定序号
 *
 *   node scripts/release-map-style.js --dir <上传拷贝目录> [--require]
 *
 * --require:三项缺任何一项就失败(发布流水线用)。不带 --require 时三项全空 = 保持官方默认底图。
 * 只给了 key 而缺浅色序号永远失败:同一个 key 下商家地图会跟着变暗。
 */

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..');
const STYLE_FILE = path.join('utils', 'map-style.js');
const BLOCK = /const MAP_STYLE = \{\n  subkey: '[^'\n]*',\n  layerStyle: \d+,\n  lightLayerStyle: \d+,\n\};/;

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
  return {
    subkey: String(env.CY_MAP_SUBKEY || '').trim(),
    layerStyle: String(env.CY_MAP_LAYER_STYLE || '').trim(),
    lightLayerStyle: String(env.CY_MAP_LIGHT_STYLE || '').trim(),
  };
}

function validate(values, requireAll) {
  const missing = [];
  if (!values.subkey) missing.push('CY_MAP_SUBKEY');
  if (!values.layerStyle) missing.push('CY_MAP_LAYER_STYLE');
  if (!values.lightLayerStyle) missing.push('CY_MAP_LIGHT_STYLE');
  if (requireAll && missing.length) throw new Error('缺少环境变量: ' + missing.join(', '));
  if (!values.subkey) {
    if (values.layerStyle || values.lightLayerStyle) throw new Error('给了样式序号却没有 CY_MAP_SUBKEY');
    return null;
  }
  if (missing.length) throw new Error('给了 CY_MAP_SUBKEY 但缺少: ' + missing.join(', ') + '(商家地图会跟着变暗)');
  if (!/^[A-Za-z0-9-]{10,64}$/.test(values.subkey)) throw new Error('CY_MAP_SUBKEY 格式不对');
  const dark = Number(values.layerStyle);
  const light = Number(values.lightLayerStyle);
  if (!Number.isInteger(dark) || dark < 1) throw new Error('CY_MAP_LAYER_STYLE 必须是从 1 起的整数');
  if (!Number.isInteger(light) || light < 1) throw new Error('CY_MAP_LIGHT_STYLE 必须是从 1 起的整数');
  if (dark === light) throw new Error('暗色与浅色样式序号相同');
  return { subkey: values.subkey, layerStyle: dark, lightLayerStyle: light };
}

function render(source, style) {
  if (!BLOCK.test(source)) throw new Error(STYLE_FILE + ' 里找不到 MAP_STYLE 配置块');
  return source.replace(BLOCK, "const MAP_STYLE = {\n  subkey: '" + style.subkey + "',\n  layerStyle: "
    + style.layerStyle + ',\n  lightLayerStyle: ' + style.lightLayerStyle + ',\n};');
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
  return { changed: true, file, layerStyle: style.layerStyle, lightLayerStyle: style.lightLayerStyle };
}

if (require.main === module) {
  try {
    const r = run(process.argv.slice(2), process.env);
    if (r.changed) console.log('[map-style] 已写入上传拷贝: 暗色序号 ' + r.layerStyle + ' / 浅色序号 ' + r.lightLayerStyle);
    else console.log('[map-style] 未配置 key,保持官方默认底图');
  } catch (e) {
    console.error('[map-style] 失败: ' + e.message);
    process.exit(1);
  }
}

module.exports = { run, validate, render, readEnv };
