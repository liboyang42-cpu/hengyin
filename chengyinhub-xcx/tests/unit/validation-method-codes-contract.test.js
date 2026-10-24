'use strict';
// B:P1-067:核验方式码值前后端各写一份,以前靠魔法值 5 / 8 / 9 / 10 对齐。
// 这里把小程序 VM 常量对着后端 ValidationMethod.java 的 public static final int 逐个钉住,
// 后端改值或加常量而小程序没跟上,这条测试会红。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { VM, VALIDATION_METHOD_LABELS } = require('../../utils/validation-method-labels.js');

const JAVA = path.join(__dirname, '../../../chengyinhub-common/src/main/java/com/chengyinhub/common/constant/ValidationMethod.java');

test('小程序 VM 常量与后端 ValidationMethod 同名同值', () => {
  const src = fs.readFileSync(JAVA, 'utf8');
  const javaConsts = {};
  for (const m of src.matchAll(/public static final int (\w+)\s*=\s*(\d+);/g)) javaConsts[m[1]] = Number(m[2]);
  assert.ok(Object.keys(javaConsts).length >= 7, '没解析出后端常量,正则或文件路径失效');
  for (const [name, value] of Object.entries(javaConsts)) {
    assert.equal(VM[name], value, `后端 ValidationMethod.${name}=${value},小程序 VM.${name}=${VM[name]}`);
  }
});

test('GPS 到达码值为 5,且有中文名', () => {
  assert.equal(VM.GPS, 5);
  assert.equal(VALIDATION_METHOD_LABELS[VM.GPS], 'GPS 到达');
});

test('VM 常量不可被运行时改写', () => {
  assert.ok(Object.isFrozen(VM));
  assert.equal(new Set(Object.values(VM)).size, Object.values(VM).length, '码值不得重复');
});

test('play 页不再用 vm === 5 之类的字面量判定 GPS 节点', () => {
  const play = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.doesNotMatch(play, /\bvm\s*[!=]==\s*5\b/);
});
