const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { extractOwnHandlerEntries, extractHandlerNames } = require('../../scripts/lib/xcx-scan')

function fixture(moduleSource, pageSource, check) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-factory-source-'))
  try {
    const moduleFile = path.join(dir, 'mixed.js')
    const pageFile = path.join(dir, 'index.js')
    fs.writeFileSync(moduleFile, moduleSource)
    fs.writeFileSync(pageFile, pageSource)
    const entries = extractOwnHandlerEntries(pageFile)
    check(entries, { moduleFile, pageFile, names: extractHandlerNames(pageFile) })
  } finally { fs.rmSync(dir, { recursive: true, force: true }) }
}

const mixed = `const unrelated = { wrongObject() { wx.getLocation({}) } };
function unused() { return { wrongFactory() { wx.requestPayment({}) } }; }
function methods() {
  function nested() { return { wrongNested() { wx.scanCode({}) } }; }
  const callback = () => { return { wrongCallback() { wx.scanCode({}) } }; };
  return {
    first() { this.second(); },
    second() { return '/api/play/theme/action'; },
    override() { return 'factory'; }
  };
}
module.exports = { methods, make: methods, unused, unrelated };
`

test('只提取已导出且实际调用的混入工厂返回对象，保留原方法范围与行号', () => {
  fixture(mixed, "const bag = require('./mixed');\nPage({...bag.methods()});\n", (entries, { moduleFile, names }) => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second', 'override'])
    assert.deepEqual(names, ['first', 'second', 'override'])
    const first = entries.find(x => x.name === 'first')
    assert.equal(first.file, moduleFile)
    assert.equal(first.line, 7)
    assert.equal(mixed.slice(first.start, first.end), 'first() { this.second(); }')
  })
})

test('命名导入及导出别名定位同一工厂，不把别名或整个模块当处理器', () => {
  fixture(mixed, "const { make: build } = require('./mixed');\nPage({...build()});\n", (entries) => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second', 'override'])
  })
})

test('依对象展开顺序选择获胜方法，包括后置混入覆盖本文件定义', () => {
  fixture(mixed, `const bag = require('./mixed');
Page({ override() { return 'before'; }, ...bag.methods(), second() { return 'after'; } });
`, (entries, { moduleFile, pageFile }) => {
    assert.equal(entries.find(x => x.name === 'override').file, moduleFile)
    assert.equal(entries.find(x => x.name === 'second').file, pageFile)
    assert.equal(entries.find(x => x.name === 'second').line, 2)
  })
})

test('注释、字符串、嵌套data与未调用的工厂不能凭同文件方法使扫描假绿', () => {
  fixture(mixed, `const bag = require('./mixed');
// Page({...bag.unused()});
const note = '...bag.unused()';
Page({ data: { ...bag.unused() }, text: '...bag.methods()', ...bag.methods, own() {} });
`, (entries, { names }) => {
    assert.deepEqual(entries.map(x => x.name), ['own'])
    assert.deepEqual(names, ['own'])
  })
})

test('模块未导出工厂时不能沿同名声明捏造混入', () => {
  fixture(mixed.replace('module.exports = { methods, make: methods, unused, unrelated };', 'module.exports = { unused };'),
    "const bag = require('./mixed');\nPage({...bag.methods(), own() {}});\n", (entries) => {
      assert.deepEqual(entries.map(x => x.name), ['own'])
    })
})

test('工厂return换行触发ASI，后面的对象不可当返回值', () => {
  fixture("function methods() { return\n{ leaked: () => {} }; }\nmodule.exports = { methods };\n",
    "const bag = require('./mixed'); Page({...bag.methods()});", (entries) => assert.deepEqual(entries, []))
})

test('普通对象混入仍限定所选导出对象，不读同模块未混入对象', () => {
  fixture("const A = { selected() {} }; const B = { unselected() {} }; module.exports = { A, B };\n",
    "const { A: chosen } = require('./mixed'); Page({...chosen});", (entries, { names }) => {
      assert.deepEqual(entries.map(x => x.name), ['selected'])
      assert.deepEqual(names, ['selected'])
    })
})

test('真实play主题工厂的章节调用链全部保留真实来源，不能以依赖文件替代', () => {
  const file = path.resolve(__dirname, '../../pages/play/index.js')
  const entries = extractOwnHandlerEntries(file)
  const expected = ['_acceptThemeNodes', 'refreshThemeBackpack', '_enterThemeChapter', '_themeCommand']
  const source = fs.readFileSync(path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js'), 'utf8')
  for (const name of expected) {
    const entry = entries.find(x => x.name === name)
    assert.ok(entry, name)
    assert.equal(entry.file, path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js'))
    assert.match(source.split('\n')[entry.line - 1], new RegExp('\\b' + name + '\\('))
  }
})

// These are source-parser tests, not execution of the untrusted fixture modules.
test('导出成员必须是精确引用，不能把成员访问或条件表达式误当被调用工厂', () => {
  for (const exportValue of ['methods.other', 'methods || unused']) {
    fixture(mixed.replace('module.exports = { methods, make: methods, unused, unrelated };',
      'module.exports = { methods: ' + exportValue + ' };'),
    "const bag = require('./mixed'); Page({...bag.methods()});", entries => assert.deepEqual(entries, []))
  }
})

test('Component只接methods里的工厂混入，data和页面外对象不能污染方法', () => {
  fixture(mixed, `const bag = require('./mixed');
const outside = { ...bag.unused() };
Component({ data: { ...bag.unused() }, methods: { ...bag.methods(), own() {} } });
`, (entries, { names }) => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second', 'override', 'own'])
    assert.deepEqual(names, ['first', 'second', 'override', 'own'])
  })
})

test('后置非函数属性覆盖处理器后不保留不存在的方法', () => {
  fixture(mixed, "const bag = require('./mixed'); Page({...bag.methods(), override: null});", entries => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second'])
  })
})

test('默认导出的命名工厂只在被调用时提供处理器', () => {
  const source = mixed.replace('module.exports = { methods, make: methods, unused, unrelated };', 'module.exports = methods;')
  fixture(source, "const make = require('./mixed'); Page({...make()});", entries => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second', 'override'])
  })
  fixture(source, "const make = require('./mixed'); Page({...make});", entries => assert.deepEqual(entries, []))
})

test('module.exports整体表达式未直接导出对象时不能借字面量中的工厂假绿', () => {
  fixture(mixed.replace('module.exports = { methods, make: methods, unused, unrelated };',
    'module.exports = { methods }.other;'),
  "const bag = require('./mixed'); Page({...bag.methods()});", entries => assert.deepEqual(entries, []))
})

test('无模块绑定的命名函数表达式不能被误认成导出工厂声明', () => {
  for (const declaration of [
    'const other = function methods() { return { leaked() {} }; };',
    'const other = [function methods() { return { leaked() {} }; }];',
  ]) {
    fixture(declaration + ' module.exports = { methods };',
      "const bag = require('./mixed'); Page({...bag.methods()});", entries => assert.deepEqual(entries, []))
  }
})

test('后置exports成员写入不能继续使用旧工厂，明确阻断静态审计', () => {
  const source = "function methods(){return{oldWrite(){}}} function newer(){return{realWrite(){}}} module.exports={methods};module.exports.methods=newer;"
  assert.throws(() => fixture(source, "const bag=require('./mixed'); Page({...bag.methods()});", () => {}),
    /无法静态解析已修改的混入导出/)
})

test('重复导出key遵循JS后者覆盖，仅解析真正获胜的新工厂', () => {
  const source = "function methods(){return{oldWrite(){}}} function newer(){return{realWrite(){}}} module.exports={methods,methods:newer};"
  fixture(source, "const bag=require('./mixed'); Page({...bag.methods()});", entries => {
    assert.deepEqual(entries.map(x => x.name), ['realWrite'])
  })
})

test('工厂绑定被重赋后不能继续使用旧声明，明确阻断静态审计', () => {
  const source = "function methods(){return{oldWrite(){}}} methods=function(){return{realWrite(){}}};module.exports={methods};"
  assert.throws(() => fixture(source, "const bag=require('./mixed'); Page({...bag.methods()});", () => {}),
    /无法静态解析已重新赋值的混入绑定 methods/)
})

test('导出方括号/反射写入以及工厂复合赋值同样不能借旧函数通过', () => {
  for (const mutation of ["module.exports['methods']=newer;", 'Object.assign(module.exports,{methods:newer});', 'methods ||= newer;']) {
    const source = "function methods(){return{oldWrite(){}}} function newer(){return{realWrite(){}}} module.exports={methods};" + mutation
    assert.throws(() => fixture(source, "const bag=require('./mixed'); Page({...bag.methods()});", () => {}),
      /无法静态解析已(?:修改的混入导出|重新赋值的混入绑定)/)
  }
})

test('注释和字符串中的导出写入/绑定重赋不应误触发阻断', () => {
  const source = "// module.exports.methods = newer; methods = newer;\nconst text = 'Object.assign(module.exports,{methods:newer}); methods ||= newer;';\n" + mixed
  fixture(source, "const bag=require('./mixed'); Page({...bag.methods()});", entries => {
    assert.deepEqual(entries.map(x => x.name), ['first', 'second', 'override'])
  })
})

test('导出删除或动态键/展开可能覆盖目标时必须阻断，不能保留旧工厂', () => {
  const prefix = "function methods(){return{oldWrite(){}}} function newer(){return{realWrite(){}}} "
  for (const statement of [
    'module.exports={methods};delete module.exports.methods;',
    "module.exports={methods,['methods']:newer};",
    "module.exports={methods,'methods':newer};",
    "module.exports={methods,methods(){return{realWrite(){}}}};",
    'const later={methods:newer};module.exports={methods,...later};',
  ]) {
    assert.throws(() => fixture(prefix + statement, "const bag=require('./mixed'); Page({...bag.methods()});", () => {}),
      /无法静态解析(?:已修改的|含动态键的)混入导出/)
  }
})

test('控制流提前return不能被当嵌套函数忽略，无法精确判定时必须阻断', () => {
  const branches = [
    'if(flag){return {realWrite(){}};}',
    'if(flag) return {realWrite(){}};',
    'try { return {realWrite(){}}; } finally {}',
    'try {} catch(error) { return {realWrite(){}}; }',
    'switch(flag){case true:return {realWrite(){}};}',
    'for(let i=0;i<1;i++){return {realWrite(){}};}',
    'while(flag){return {realWrite(){}};}',
    'do {return {realWrite(){}};} while(flag);',
    'if(flag){return null;}',
  ]
  for (const branch of branches) {
    const source = 'function methods(flag){' + branch + 'return {fallbackRead(){}};} module.exports={methods};'
    assert.throws(() => fixture(source, "const bag=require('./mixed'); Page({...bag.methods(true)});", () => {}),
      /无法静态解析含多路径返回的混入工厂/, branch)
  }
})

test('嵌套函数和箭头回调的独立return不属于工厂控制流，不误伤直接返回', () => {
  const source = `function methods(flag){
    function helper(){ if(flag){return {wrongNested(){}};} return {wrongFallback(){}}; }
    const callback = () => { try { return {wrongArrow(){}}; } finally {} };
    if(flag){ function nested(){ return {wrongBlockFunction(){}}; } }
    return {realWrite(){}};
  } module.exports={methods};`
  fixture(source, "const bag=require('./mixed'); Page({...bag.methods(true)});", entries => {
    assert.deepEqual(entries.map(x => x.name), ['realWrite'])
  })
})
