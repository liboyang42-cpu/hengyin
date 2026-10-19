'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { parseJavaScript, findEventBindings, scanTags, maskWxmlComments } = require('../../scripts/lib/xcx-scan');

// Static strings only: never execute a feature module or guess an unresolved URL.
function finiteRoutes(files) {
  const modules = new Map(), nodes = new Map(), calls = [], assignmentsByBinding = new Map(), mutatedMembers = new Set();
  function visit(node, scope, parent) {
    if (!node || typeof node.type !== 'string') return;
    if (/Function/.test(node.type)) {
      const outer = scope;
      scope = { parent: outer, bindings: new Map(), fn: node };
      if (node.id) outer.bindings.set(node.id.name, { node, scope: outer });
      node.params.forEach((param, index) => {
        if (param.type === 'Identifier') scope.bindings.set(param.name, { param, index, fn: node, scope });
      });
    } else if (node.type === 'BlockStatement') scope = { parent: scope, bindings: new Map() };
    nodes.set(node, { scope, parent });
    if (node.type === 'VariableDeclaration') {
      let owner = scope;
      if (node.kind === 'var') while (owner.parent && !owner.fn) owner = owner.parent;
      node.declarations.forEach(declaration => {
        if (declaration.id.type === 'Identifier') owner.bindings.set(declaration.id.name,
          { node: declaration.init, scope, constant: node.kind === 'const' });
        if (declaration.id.type === 'ObjectPattern' && declaration.init?.callee?.name === 'require') {
          for (const property of declaration.id.properties) if (property.type === 'Property' && property.value.type === 'Identifier') {
            owner.bindings.set(property.value.name, { node: { type: 'MemberExpression', computed: false, object: declaration.init,
              property: { type: 'Identifier', name: property.key.name } }, scope, constant: node.kind === 'const' });
          }
        }
      });
    }
    if (node.type === 'CallExpression') calls.push(node);
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(child => visit(child, scope, node));
      else if (value && typeof value.type === 'string') visit(value, scope, node);
    }
  }
  function load(file) {
    if (modules.has(file)) return modules.get(file);
    const source = fs.readFileSync(file, 'utf8'), ast = parseJavaScript(source);
    const scope = { file, bindings: new Map() }, context = { file, source, ast, scope };
    modules.set(file, context);
    visit(ast, scope, null);
    return context;
  }
  files.forEach(load);
  function binding(name, scope) {
    for (; scope; scope = scope.parent) if (scope.bindings.has(name)) return scope.bindings.get(name);
    return null;
  }
  function fileOf(scope) { while (scope.parent) scope = scope.parent; return scope.file; }
  const navigationFile = path.resolve(__dirname, '../../utils/registered-navigation.js');
  function registeredNavigation(fn) {
    if (!fn || fn.id?.name !== 'navigateRegistered') return false;
    const context = modules.get(fileOf(nodes.get(fn).scope));
    if (context.file !== navigationFile || context.ast.body.length !== 5) return false;
    const [directive, dependency, declaration] = context.ast.body;
    if (directive.expression?.value !== 'use strict' || dependency.kind !== 'const' || dependency.declarations.length !== 1
      || dependency.declarations[0].id.name !== 'toast' || dependency.declarations[0].init?.callee?.name !== 'require'
      || dependency.declarations[0].init.arguments.length !== 1 || dependency.declarations[0].init.arguments[0].value !== './toast.js'
      || declaration.kind !== 'const' || declaration.declarations.length !== 1 || declaration.declarations[0].id.name !== 'REGISTERED_PATHS') return false;
    const registry = context.scope.bindings.get('REGISTERED_PATHS')?.node;
    const array = registry?.arguments?.[0];
    if (registry?.callee?.object?.name !== 'Object' || registry.callee.property.name !== 'freeze'
      || array?.type !== 'ArrayExpression' || array.elements.some(node => node.type !== 'Literal' || typeof node.value !== 'string')) return false;
    const app = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'));
    const paths = [...app.pages, ...app.subPackages.flatMap(pkg => pkg.pages.map(route => pkg.root + '/' + route))];
    if (JSON.stringify(array.elements.map(node => node.value)) !== JSON.stringify(paths)) return false;
    // Verify the reviewed boundary's entire guard/callback/dispatch body, never execute it.
    return crypto.createHash('sha256').update(context.source.slice(fn.start, fn.end)).digest('hex')
      === 'e50d4eb774675984cebea2cdca27b55f0da978b4234a6bb6703c1233683aca38';
  }
  function exported(context, name) {
    if (context.exportObject !== undefined) {
      const object = context.exportObject;
      if (!object || object.properties.filter(item => (item.key.name || item.key.value) === name).length !== 1) return null;
      const property = object.properties.find(item => (item.key.name || item.key.value) === name);
      return { node: property.value, scope: context.scope };
    }
    context.exportObject = null;
    const statements = context.ast.body.filter(item => item.type === 'ExpressionStatement'
      && item.expression.type === 'AssignmentExpression'
      && item.expression.operator === '='
      && item.expression.left.type === 'MemberExpression'
      && item.expression.left.object.name === 'module' && item.expression.left.property.name === 'exports');
    if (statements.length !== 1) return null;
    const statement = statements[0];
    const exportNodes = [];
    function walk(node) {
      if (!node || !node.type) return;
      exportNodes.push(node);
      for (const child of Object.values(node)) {
        if (Array.isArray(child)) child.forEach(walk);
        else if (child && child.type) walk(child);
      }
    }
    walk(context.ast);
    // Other references can alias, mutate or delete the export object.
    if (exportNodes.some(node => node.type === 'MemberExpression' && node.object.name === 'module'
      && (node.property.name || node.property.value) === 'exports' && node !== statement.expression.left)) return null;
    const object = statement && statement.expression.right;
    if (!object || object.type !== 'ObjectExpression') return null;
    if (object.properties.some(item => item.type !== 'Property' || item.computed)) return null;
    if (exportNodes.some(node => node.type === 'CallExpression'
      && node.arguments.some(arg => arg.type === 'MemberExpression' && arg.object.name === 'module' && arg.property.name === 'exports'))) return null;
    context.exportObject = object;
    return exported(context, name);
  }
  function functionOf(node, scope, seen = new Set()) {
    if (!node || seen.has(node)) return null;
    seen = new Set(seen).add(node);
    if (/Function/.test(node.type)) return node;
    if (node.type === 'Identifier') {
      const entry = binding(node.name, scope);
      if (assignmentsByBinding.has(entry) || mutatedMembers.has(entry)) return null;
      return entry && entry.node && (entry.constant || entry.node.type === 'FunctionDeclaration')
        && functionOf(entry.node, entry.scope, seen);
    }
    if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'ThisExpression') {
      for (let current = scope; current; current = current.parent) {
        const info = current.fn && nodes.get(current.fn);
        const property = info && info.parent;
        const object = property && property.type === 'Property' && nodes.get(property).parent;
        if (!object || object.type !== 'ObjectExpression') continue;
        const member = object.properties.find(item => item.type === 'Property'
          && (item.key.name || item.key.value) === node.property.name);
        return member && functionOf(member.value, current.parent, seen);
      }
    }
    if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'CallExpression'
        && node.object.callee.name === 'require' && node.object.arguments[0]
        && typeof node.object.arguments[0].value === 'string' && node.object.arguments[0].value.startsWith('.')) {
      const target = path.resolve(path.dirname(fileOf(scope)), node.object.arguments[0].value);
      const file = fs.existsSync(target + '.js') ? target + '.js' : target;
      if (!fs.existsSync(file) || !fs.statSync(file).isFile()) return null;
      const entry = exported(load(file), node.property.name);
      return entry && functionOf(entry.node, entry.scope, seen);
    }
    return null;
  }
  function navigationImport(node, scope, seen = new Set()) {
    if (!node || seen.has(node)) return false;
    seen = new Set(seen).add(node);
    if (node.type === 'Identifier') {
      const entry = binding(node.name, scope);
      return entry && navigationImport(entry.node, entry.scope, seen);
    }
    return node.type === 'MemberExpression' && !node.computed && node.property.name === 'navigateRegistered'
      && node.object.type === 'CallExpression' && node.object.callee.name === 'require'
      && typeof node.object.arguments[0]?.value === 'string'
      && path.resolve(path.dirname(fileOf(scope)), node.object.arguments[0].value).replace(/\.js$/, '') === navigationFile.replace(/\.js$/, '');
  }
  function combine(left, right) {
    if (!left || !right || left.length * right.length > 128) return null;
    return [...new Set(left.flatMap(a => right.map(b => a + b)))];
  }
  function union(values) {
    if (!values.length || !values.every(Boolean)) return null;
    const result = [...new Set(values.flat())];
    return result.length <= 128 ? result : null;
  }
  function memberValues(object, name, scope, env, seen) {
    if (!object) return null;
    if (object.type === 'Identifier') {
      const entry = binding(object.name, scope);
      if (!entry || !entry.node) return null;
      const assignments = assignmentsByBinding.get(entry) || [];
      if (mutatedMembers.has(entry) || assignments.some(node => node.operator !== '=')) return null;
      const sources = [{ node: entry.node, scope: entry.scope },
        ...assignments.map(node => ({ node: node.right, scope: nodes.get(node).scope }))]
        .filter(item => item.node.type !== 'Literal' || item.node.value !== null);
      return union(sources.map(item => {
        if (seen.has(item.node)) return entry.constant ? null : [];
        const next = item.node.type === 'Identifier' ? new Set(seen).add(item.node) : seen;
        return memberValues(item.node, name, item.scope, env, next);
      }));
    }
    if (object.type === 'ObjectExpression') {
      if (object.properties.some(prop => prop.type !== 'Property' || prop.computed)) return null;
      if (object.properties.filter(item => (item.key.name || item.key.value) === name).length !== 1) return null;
      const prop = object.properties.find(item => (item.key.name || item.key.value) === name);
      return prop && values(prop.value, scope, env, seen);
    }
    if (object.type === 'MemberExpression' && object.object.type === 'ThisExpression' && object.property.name === 'data') {
      return union(stateSources(name, scope).map(item => values(item.node, item.scope, env, seen)));
    }
    const dataset = datasetValues(object, name, scope, env, seen);
    if (dataset) return dataset;
    const sources = objectSources(object, scope, seen);
    if (sources) {
      const properties = sources.flatMap(item => item.node.properties.filter(prop => prop.type === 'Property' && !prop.computed
        && (prop.key.name || prop.key.value) === name).map(prop => ({ node: prop.value, scope: item.scope })));
      return union(properties.map(item => values(item.node, item.scope, env, seen)));
    }
    const memberName = node => node && node.type === 'MemberExpression' && !node.computed
      && node.object.type === 'Identifier' ? `${node.object.name}.${node.property.name}` : '';
    if (object.type === 'CallExpression' && memberName(object.callee) === 'JSON.parse'
        && object.arguments.length === 1 && object.arguments[0].type === 'CallExpression'
        && memberName(object.arguments[0].callee) === 'JSON.stringify') {
      const original = object.arguments[0].arguments[0];
      if (!original || original.type !== 'MemberExpression' || original.computed) return null;
      const context = modules.get(fileOf(scope));
      const target = context.source.slice(original.start, original.end).replace(/\s/g, '') + '.' + name;
      let statement = object, parent = nodes.get(statement).parent;
      while (parent && parent.type !== 'BlockStatement') { statement = parent; parent = nodes.get(parent).parent; }
      const index = parent && parent.body.indexOf(statement), guard = index > 0 && parent.body[index - 1];
      if (!guard || guard.type !== 'IfStatement' || guard.consequent.type !== 'ThrowStatement') return null;
      function constraint(test) {
        if (test.type === 'LogicalExpression' && test.operator === '||') return constraint(test.left) || constraint(test.right);
        if (test.type !== 'BinaryExpression' || test.operator !== '!==') return null;
        return test.right.type === 'Literal' && typeof test.right.value === 'string'
          && context.source.slice(test.left.start, test.left.end).replace(/\s/g, '') === target ? [test.right.value] : null;
      }
      return constraint(guard.test);
    }
    return null;
  }
  const callersByFunction = new Map();
  // Resolve imports before indexing mutations, including modules outside the initial file list.
  for (const call of calls) functionOf(call.callee, nodes.get(call).scope);
  for (const [node, info] of nodes) {
    if (node.type === 'CallExpression' && node.callee.type === 'MemberExpression'
      && ['push', 'pop', 'shift', 'unshift', 'splice', 'sort', 'reverse', 'fill', 'copyWithin'].includes(node.callee.property.name)
      && node.callee.object.type === 'Identifier') mutatedMembers.add(binding(node.callee.object.name, info.scope));
    if (!['AssignmentExpression', 'UpdateExpression'].includes(node.type)
      && !(node.type === 'UnaryExpression' && node.operator === 'delete')) continue;
    const target = node.left || node.argument;
    if (target.type === 'Identifier') {
      const entry = binding(target.name, info.scope);
      if (!assignmentsByBinding.has(entry)) assignmentsByBinding.set(entry, []);
      assignmentsByBinding.get(entry).push(node);
    } else if (target.type === 'MemberExpression') {
      let root = target.object;
      while (root.type === 'MemberExpression') root = root.object;
      if (root.type === 'Identifier') mutatedMembers.add(binding(root.name, info.scope));
    }
  }
  const aliases = [];
  for (const [node, info] of nodes) {
    if (node.type === 'Property' && node.value.type === 'Identifier' && info.parent.type === 'ObjectExpression') {
      const container = nodes.get(info.parent).parent;
      const entry = binding(node.value.name, info.scope);
      if (container?.type === 'VariableDeclarator' && container.id.type === 'Identifier'
        && entry?.node && (/Function/.test(entry.node.type) || ['ObjectExpression', 'Identifier'].includes(entry.node.type))) {
        aliases.push([entry, binding(container.id.name, info.scope)]);
      }
    }
    if (node.type === 'VariableDeclarator' && node.id.type === 'Identifier' && node.init && node.init.type === 'Identifier') {
      aliases.push([binding(node.id.name, info.scope), binding(node.init.name, info.scope)]);
    }
    if (node.type === 'AssignmentExpression' && node.left.type === 'Identifier' && node.right.type === 'Identifier') {
      aliases.push([binding(node.left.name, info.scope), binding(node.right.name, info.scope)]);
    }
    if (node.type === 'CallExpression') {
      const fn = functionOf(node.callee, info.scope);
      for (const [index, arg] of node.arguments.entries()) if (arg.type === 'Identifier') {
        const entry = binding(arg.name, info.scope);
        if (fn && fn.params[index]?.type === 'Identifier') {
          aliases.push([entry, binding(fn.params[index].name, nodes.get(fn).scope)]);
        } else if (!fn && entry && (entry.node?.type === 'ObjectExpression' || entry.node?.type === 'Identifier')) {
          mutatedMembers.add(entry);
        }
      }
      if (!fn && node.callee.type === 'MemberExpression' && node.callee.object.type === 'Identifier') {
        const entry = binding(node.callee.object.name, info.scope);
        if (entry?.node?.type === 'ObjectExpression' || entry?.node?.type === 'Identifier') mutatedMembers.add(entry);
      }
    }
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const [a, b] of aliases) if (a && b && (mutatedMembers.has(a) || mutatedMembers.has(b))) {
      if (!mutatedMembers.has(a) || !mutatedMembers.has(b)) changed = true;
      mutatedMembers.add(a); mutatedMembers.add(b);
    }
  }
  for (const call of calls) {
    const fn = functionOf(call.callee, nodes.get(call).scope);
    if (fn) {
      if (!callersByFunction.has(fn)) callersByFunction.set(fn, []);
      callersByFunction.get(fn).push(call);
    }
  }
  const fileNodes = new Map(), stateCache = new Map();
  for (const item of nodes) {
    const file = fileOf(item[1].scope);
    if (!fileNodes.has(file)) fileNodes.set(file, []);
    fileNodes.get(file).push(item);
  }
  function stateSources(name, scope) {
    const file = fileOf(scope), sources = [];
    const key = file + ':' + name;
    if (stateCache.has(key)) return stateCache.get(key);
    if ((fileNodes.get(file) || []).some(([node, info]) => node.type === 'VariableDeclarator'
      && node.id.type === 'Identifier' && mutatedMembers.has(binding(node.id.name, info.scope))
      && node.init?.type === 'MemberExpression' && node.init.object.type === 'ThisExpression'
      && node.init.property.name === 'data')) return [];
    function isThis(node, scope, seen = new Set()) {
      if (node.type === 'ThisExpression') return true;
      if (node.type !== 'Identifier' || seen.has(node.name)) return false;
      const entry = binding(node.name, scope);
      return entry?.node && !assignmentsByBinding.has(entry) && isThis(entry.node, entry.scope, new Set(seen).add(node.name));
    }
    for (const [node, info] of fileNodes.get(file) || []) {
      const state = value => value?.type === 'MemberExpression' && value.property.name === 'data' && isThis(value.object, info.scope);
      if (node.type === 'AssignmentExpression' && state(node.left)
        || node.type === 'CallExpression' && node.arguments.some(state)) return [];
      if (node.type === 'Property' && !node.computed && (node.key.name || node.key.value) === name) {
        const object = info.parent, container = nodes.get(object)?.parent;
        const data = container?.type === 'Property' && container.key.name === 'data';
        const setter = container?.type === 'CallExpression' && container.callee.type === 'MemberExpression'
          && container.callee.property.name === 'setData' && isThis(container.callee.object, info.scope);
        if (data || setter) sources.push({ node: node.value, scope: info.scope });
      }
      if (node.type === 'CallExpression' && node.callee.type === 'MemberExpression'
        && node.callee.property.name === 'setData' && isThis(node.callee.object, info.scope)) {
        const object = node.arguments[0];
        if (!object || object.type !== 'ObjectExpression' || object.properties.some(prop => prop.type !== 'Property' || prop.computed)) return [];
      }
      const target = node.left || node.argument;
      if (target?.type === 'MemberExpression' && target.object.type === 'MemberExpression'
        && isThis(target.object.object, info.scope) && target.object.property.name === 'data'
        && (target.computed || target.property.name === name)
        && (node.type === 'UpdateExpression' || node.type === 'UnaryExpression' && node.operator === 'delete'
          || node.type === 'AssignmentExpression' && (node.operator !== '=' || target.computed))) return [];
      if (node.type === 'AssignmentExpression' && node.operator === '=' && node.left.type === 'MemberExpression'
        && node.left.property.name === name && node.left.object.type === 'MemberExpression'
        && isThis(node.left.object.object, info.scope) && node.left.object.property.name === 'data') {
        sources.push({ node: node.right, scope: info.scope });
      }
    }
    stateCache.set(key, sources);
    return sources;
  }
  function returnNodes(fn) {
    const result = [];
    function walk(node) {
      if (!node || !node.type || /Function/.test(node.type)) return;
      if (node.type === 'ReturnStatement') result.push(node);
      for (const child of Object.values(node)) {
        if (Array.isArray(child)) child.forEach(walk);
        else if (child && child.type) walk(child);
      }
    }
    walk(fn.body);
    return result;
  }
  function objectSources(node, scope, seen = new Set()) {
    if (!node || seen.has(node) || seen.size > 32) return null;
    seen = new Set(seen).add(node);
    if (node.type === 'ObjectExpression') {
      if (node.properties.some(prop => prop.type !== 'Property' || prop.computed)) return null;
      return [{ node, scope }];
    }
    if (node.type === 'ArrayExpression') {
      const sources = node.elements.map(item => objectSources(item, scope, seen));
      return sources.every(Boolean) ? sources.flat() : null;
    }
    if (node.type === 'Identifier') {
      const entry = binding(node.name, scope);
      return entry && !mutatedMembers.has(entry) && !assignmentsByBinding.has(entry)
        ? objectSources(entry.node, entry.scope, seen) : null;
    }
    if (node.type === 'LogicalExpression' && node.operator === '||' || node.type === 'ConditionalExpression') {
      const branches = node.type === 'ConditionalExpression' ? [node.consequent, node.alternate] : [node.left, node.right];
      const sources = branches.map(item => objectSources(item, scope, seen));
      return sources.every(Boolean) ? sources.flat() : null;
    }
    if (node.type === 'MemberExpression') {
      if (node.object.type === 'ThisExpression' && node.property.name === 'data') return null;
      if (node.object.type === 'MemberExpression' && node.object.object.type === 'ThisExpression' && node.object.property.name === 'data') {
        const sources = stateSources(node.property.name, scope).map(item => objectSources(item.node, item.scope, seen));
        return sources.length && sources.every(Boolean) ? sources.flat() : null;
      }
      const sources = objectSources(node.object, scope, seen);
      if (!sources) return null;
      const keys = node.computed ? values(node.property, scope, new Map(), seen) : [node.property.name];
      const result = sources.flatMap(item => item.node.properties.filter(prop => !keys || keys.includes(prop.key.name || prop.key.value))
        .map(prop => objectSources(prop.value, item.scope, seen)));
      return result.length && result.every(Boolean) ? result.flat() : null;
    }
    if (node.type === 'CallExpression') {
      if (node.callee.type === 'MemberExpression' && ['find', 'filter'].includes(node.callee.property.name)) {
        return objectSources(node.callee.object, scope, seen);
      }
      if (node.callee.type === 'MemberExpression' && node.callee.property.name === 'flatMap') {
        const callback = node.arguments[0], result = callback?.body;
        if (callback?.params[0]?.type !== 'Identifier' || result?.type !== 'MemberExpression'
          || result.object.name !== callback.params[0].name || result.computed) return null;
        const sources = objectSources(node.callee.object, scope, seen);
        if (!sources) return null;
        const nested = sources.map(item => item.node.properties.find(prop => (prop.key.name || prop.key.value) === result.property.name)?.value)
          .map((value, index) => objectSources(value, sources[index].scope, seen));
        return nested.every(Boolean) ? nested.flat() : null;
      }
      const fn = functionOf(node.callee, scope);
      if (!fn || fn.body.type !== 'BlockStatement' || fn.body.body.at(-1)?.type !== 'ReturnStatement') return null;
      const sources = returnNodes(fn).map(item => objectSources(item.argument, nodes.get(item).scope, seen));
      return sources.length && sources.every(Boolean) ? sources.flat() : null;
    }
    return null;
  }
  function datasetValues(object, name, scope, env, seen) {
    if (object.type === 'Identifier') {
      const entry = binding(object.name, scope);
      if (!entry || assignmentsByBinding.has(entry) || !entry.node) return null;
      return datasetValues(entry.node, name, entry.scope, env, seen);
    }
    if (object.type !== 'MemberExpression' || object.property.name !== 'dataset'
      || object.object.type !== 'MemberExpression' || object.object.property.name !== 'currentTarget'
      || object.object.object.type !== 'Identifier') return null;
    const entry = binding(object.object.object.name, scope);
    if (!entry?.param) return null;
    const fn = entry.fn, parent = nodes.get(fn).parent, handler = fn.id?.name || parent?.key?.name;
    const wxml = fileOf(scope).replace(/\.js$/, '.wxml');
    if (!handler || !fs.existsSync(wxml)) return null;
    const source = maskWxmlComments(fs.readFileSync(wxml, 'utf8')), stack = [], results = [];
    for (const tag of scanTags(source)) {
      const raw = source.slice(tag.start, tag.end + 1);
      if (raw.startsWith('</')) { stack.pop(); continue; }
      const attrs = Object.fromEntries([...raw.matchAll(/([\w:-]+)\s*=\s*(["'])([\s\S]*?)\2/g)].map(match => [match[1], match[3]]));
      if (findEventBindings(raw).some(event => event.value === handler)) {
        const attribute = attrs['data-' + name] ?? attrs['data-' + name.replace(/[A-Z]/g, char => '-' + char.toLowerCase())];
        if (attribute === undefined) { results.push(null); continue; }
        const dynamic = /^\{\{\s*(\w+)\.(\w+)\s*\}\}$/.exec(attribute);
        if (!dynamic) { results.push(attribute.includes('{{') ? null : [attribute]); }
        else {
          const loop = [...stack, attrs].reverse().find(item => item['wx:for'] && (item['wx:for-item'] || 'item') === dynamic[1]);
          const list = loop && /^\{\{\s*(\w+)\s*\}\}$/.exec(loop['wx:for']);
          const sources = list && stateSources(list[1], scope).map(item => objectSources(item.node, item.scope, seen));
          if (!sources?.length || !sources.every(Boolean)) results.push(null);
          else {
            const props = sources.flat().flatMap(item => item.node.properties.filter(prop => (prop.key.name || prop.key.value) === dynamic[2])
              .map(prop => ({ node: prop.value, scope: item.scope })));
            results.push(union(props.map(prop => values(prop.node, prop.scope, env, seen))));
          }
        }
      }
      if (!raw.endsWith('/>')) stack.push(attrs);
    }
    return union(results);
  }
  function values(node, scope, env = new Map(), seen = new Set()) {
    if (!node || seen.size > 32) return null;
    if (seen.has(node)) {
      const entry = node.type === 'MemberExpression' && node.object.type === 'Identifier' && binding(node.object.name, scope);
      return entry && !entry.constant && assignmentsByBinding.has(entry) ? [] : null;
    }
    seen = new Set(seen).add(node);
    if (node.type === 'Literal') return typeof node.value === 'string' ? [node.value] : null;
    if (node.type === 'ArrayExpression') return union(node.elements.map(item => values(item, scope, env, seen)));
    if (node.type === 'Identifier') {
      const entry = binding(node.name, scope);
      if (!entry) return null;
      if (mutatedMembers.has(entry)) return null;
      if (env.has(entry)) return env.get(entry);
      if (entry.param) {
        const fnInfo = nodes.get(entry.fn);
        const parent = fnInfo.parent;
        if (parent && parent.type === 'CallExpression' && parent.callee.type === 'MemberExpression'
            && parent.callee.property.name === 'forEach' && entry.index === 0) {
          return values(parent.callee.object, nodes.get(parent).scope, env, seen);
        }
        const callers = callersByFunction.get(entry.fn) || [];
        return union(callers.map(call => values(call.arguments[entry.index], nodes.get(call).scope, env, seen)));
      }
      const initial = values(entry.node, entry.scope, env, seen), writes = assignmentsByBinding.get(entry);
      if (!writes) return initial;
      return initial && initial.every(value => value.includes('?')) && writes.every(write => write.operator === '+=')
        ? initial : null;
    }
    if (node.type === 'BinaryExpression' && node.operator === '+') {
      const left = values(node.left, scope, env, seen), right = values(node.right, scope, env, seen);
      // An unknown ID may occupy a complete path segment; it cannot supply a URL prefix or action.
      const segment = node.right.type === 'Identifier' || node.right.type === 'MemberExpression';
      const query = left && left.every(value => value.includes('?'));
      return combine(left, right || (left && (query || segment && left.every(value => /\/$/.test(value))) ? [':dynamic'] : null));
    }
    if (node.type === 'ConditionalExpression') return union([
      values(node.consequent, scope, env, seen), values(node.alternate, scope, env, seen)]);
    if (node.type === 'MemberExpression' && !node.computed) return memberValues(node.object, node.property.name, scope, env, seen);
    if (node.type === 'TemplateLiteral') {
      let result = [node.quasis[0].value.cooked];
      node.expressions.forEach((expression, index) => {
        const resolved = values(expression, scope, env, seen);
        const query = result && result.every(value => value.includes('?'));
        const segment = ['Identifier', 'MemberExpression'].includes(expression.type)
          && result && result.every(value => value.endsWith('/')) && /^(?:\/|$)/.test(node.quasis[index + 1].value.cooked);
        result = combine(combine(result, resolved || (query || segment ? [':dynamic'] : null)), [node.quasis[index + 1].value.cooked]);
      });
      return result;
    }
    if (node.type === 'CallExpression') {
      if (node.callee.name === 'encodeURIComponent' && !binding(node.callee.name, scope)) return [':dynamic'];
      const fn = functionOf(node.callee, scope);
      if (!fn || fn.body.type !== 'BlockStatement') return null;
      if (fn.body.body.at(-1)?.type !== 'ReturnStatement') return null;
      const returns = returnNodes(fn);
      if (!returns.length) return null;
      const fnScope = nodes.get(fn).scope, local = new Map(env);
      fn.params.forEach((param, index) => {
        if (param.type === 'Identifier') local.set(binding(param.name, fnScope), values(node.arguments[index], scope, env, seen));
      });
      return union(returns.map(item => values(item.argument, nodes.get(item).scope, local, seen)));
    }
    return null;
  }
  function environments(node) {
    let scope = nodes.get(node).scope;
    const ancestors = [];
    for (; scope; scope = scope.parent) if (scope.fn) ancestors.unshift(scope.fn);
    let environments = [new Map()];
    for (const fn of ancestors) {
      const callers = callersByFunction.get(fn) || [];
      if (!callers.length) continue;
      environments = environments.flatMap(env => callers.map(call => {
        const next = new Map(env), fnScope = nodes.get(fn).scope;
        fn.params.forEach((param, index) => {
          if (param.type === 'Identifier') next.set(binding(param.name, fnScope), values(call.arguments[index], nodes.get(call).scope, env));
        });
        return next;
      }));
      if (environments.length > 128) return [];
    }
    return environments;
  }
  function objectKeys(node, scope, seen = new Set()) {
    if (!node) return [];
    if (seen.has(node)) return null;
    seen = new Set(seen).add(node);
    if (node.type === 'ObjectExpression') {
      return union(node.properties.map(property => {
        if (property.type === 'SpreadElement') return objectKeys(property.argument, scope, seen);
        return property.computed ? null : [property.key.name || property.key.value];
      })) || (node.properties.length ? null : []);
    }
    if (node.type === 'LogicalExpression' && node.operator === '||') return union([
      objectKeys(node.left, scope, seen), objectKeys(node.right, scope, seen)]);
    if (node.type === 'Identifier') {
      const entry = binding(node.name, scope);
      if (!entry || mutatedMembers.has(entry) || assignmentsByBinding.has(entry)) return null;
      if (entry.param) {
        const callers = callersByFunction.get(entry.fn) || [];
        return union(callers.map(call => objectKeys(call.arguments[entry.index], nodes.get(call).scope, seen)));
      }
      return objectKeys(entry.node, entry.scope, seen);
    }
    if (node.type === 'Literal') return [];
    return null;
  }
  function guardedTruthy(call, expression, context) {
    const text = node => context.source.slice(node.start, node.end).replace(/\s/g, '');
    const target = text(expression);
    function contains(test) {
      if (test.type === 'LogicalExpression' && test.operator === '&&') return contains(test.left) || contains(test.right);
      return ['Identifier', 'MemberExpression'].includes(test.type) && text(test) === target;
    }
    for (let node = call, parent = nodes.get(node)?.parent; parent; node = parent, parent = nodes.get(node)?.parent) {
      if (parent.type === 'IfStatement' && node === parent.consequent && contains(parent.test)) return true;
      if (parent.type === 'BlockStatement') {
        const earlier = parent.body.slice(0, parent.body.indexOf(node));
        if (earlier.some(statement => statement.type === 'IfStatement' && statement.test.type === 'UnaryExpression'
          && statement.test.operator === '!' && contains(statement.test.argument)
          && (statement.consequent.type === 'ReturnStatement' || statement.consequent.type === 'BlockStatement'
            && statement.consequent.body.at(-1)?.type === 'ReturnStatement'))) return true;
      }
      if (/Function/.test(parent.type)) break;
    }
    return false;
  }
  const result = new Map(), covered = new Map();
  function cover(file, offset) {
    if (!covered.has(file)) covered.set(file, new Set());
    covered.get(file).add(offset);
  }
  for (const context of modules.values()) {
    const expressions = new Map(), comparisons = new Set(), requests = [];
    function collect(node) {
      if (!node || !nodes.has(node) || fileOf(nodes.get(node).scope) !== context.file) return;
      if (node.type === 'Literal' && typeof node.value === 'string' && node.value.startsWith('/api/')) {
        const parent = nodes.get(node).parent;
        if (parent.type === 'BinaryExpression' && ['===', '!==', '==', '!='].includes(parent.operator)) comparisons.add(node.start);
      }
      const checkedNavigation = node.type === 'CallExpression' && (node.callee.type === 'Identifier'
        && node.callee.name === 'navigateRegistered' || navigationImport(node.callee, nodes.get(node).scope));
      if (node.type === 'CallExpression' && (checkedNavigation || node.callee.type === 'MemberExpression'
          && (node.callee.property.name === 'sendRequest' || node.callee.object.name === 'wx'
          && ['navigateTo', 'redirectTo', 'reLaunch'].includes(node.callee.property.name)))) {
        const object = node.arguments[0];
        const prop = object && object.type === 'ObjectExpression' && object.properties.find(item => item.type === 'Property'
          && (item.key.name || item.key.value) === 'url');
        const urlProperties = object && object.type === 'ObjectExpression' ? object.properties.filter(item => item.type === 'Property'
          && (item.key.name || item.key.value) === 'url') : [];
        const propIndex = prop && object.properties.indexOf(prop);
        const ambiguous = object && object.type === 'ObjectExpression' && (urlProperties.length > 1
          || object.properties.slice(prop ? propIndex + 1 : 0).some(item => {
            if (item.type === 'Property') return item.computed;
            const keys = item.type === 'SpreadElement' && objectKeys(item.argument, nodes.get(item).scope);
            return !keys || keys.includes('url');
          }));
        const request = { call: node, navigation: checkedNavigation || node.callee.property.name !== 'sendRequest', ambiguous,
          checkedBoundary: checkedNavigation,
          guarded: checkedNavigation && registeredNavigation(functionOf(node.callee, nodes.get(node).scope)),
          url: prop && prop.value, routes: null };
        for (let scope = nodes.get(node).scope; scope; scope = scope.parent) {
          if (scope.fn && scope.parent && [...scope.parent.bindings.values()].some(entry => entry.node === scope.fn && mutatedMembers.has(entry))) {
            request.escapedFunction = true;
          }
        }
        requests.push(request);
        if (prop && !ambiguous) {
          const candidates = environments(prop.value);
          let resolved = union(candidates.map(env => {
            const scope = nodes.get(prop.value).scope, local = new Map(env);
            if (request.navigation && prop.value.type === 'BinaryExpression' && prop.value.left.type === 'Identifier'
              && guardedTruthy(node, prop.value.left, context)) {
              const initial = values(prop.value.left, scope, env);
              if (initial) local.set(binding(prop.value.left.name, scope), initial.filter(Boolean));
            }
            return values(prop.value, scope, local);
          }));
          if (request.navigation && resolved && guardedTruthy(node, prop.value, context)) resolved = resolved.filter(Boolean);
          if (resolved && resolved.length) {
            request.routes = resolved;
            expressions.set(prop.value.start, { end: prop.value.end, routes: resolved, call: node,
              navigation: request.navigation });
            // Only fragments contributing to a fully resolved URL can replace the old literal scan.
            function mark(value, scope, seen = new Set()) {
              if (!value || seen.has(value)) return;
              seen = new Set(seen).add(value);
              if (value.type === 'Literal') {
                cover(fileOf(scope), value.start);
                return;
              }
              if (value.type === 'Identifier') {
                const entry = binding(value.name, scope);
                if (entry && entry.node && entry.constant) mark(entry.node, entry.scope, seen);
                if (entry && entry.param) {
                  for (const call of callersByFunction.get(entry.fn) || []) {
                    mark(call.arguments[entry.index], nodes.get(call).scope, seen);
                  }
                }
              }
              for (const child of Object.values(value)) {
                if (Array.isArray(child)) child.forEach(item => item && item.type && mark(item, scope, seen));
                else if (child && child.type) mark(child, scope, seen);
              }
            }
            mark(prop.value, nodes.get(prop.value).scope);
          }
        }
      }
      for (const child of Object.values(node)) {
        if (Array.isArray(child)) child.forEach(item => collect(item));
        else if (child && child.type) collect(child);
      }
    }
    collect(context.ast);
    result.set(context.file, { expressions, comparisons, requests });
  }
  for (const [file, item] of result) item.fragments = covered.get(file) || new Set();
  return result;
}

module.exports = { finiteRoutes };
