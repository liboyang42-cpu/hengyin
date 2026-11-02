"""Collect a mini-program page + component closure + JS require closure into one JSON-ish JS bundle."""
import re, os, json, sys
sys.path.insert(0, os.path.dirname(__file__))
import wxss2css as W
ROOT = os.path.abspath(sys.argv[1]); OUT = sys.argv[2]; entries = sys.argv[3:]
W.ROOT = ROOT
files = {}; css = {}; comps = {}; missing = set()
def rel(p): return os.path.relpath(p, ROOT).replace(os.sep, '/')
def resolve_path(base_dir, p):
    full = os.path.join(ROOT, p.lstrip('/')) if p.startswith('/') else os.path.join(base_dir, p)
    return os.path.normpath(full)
def add_js(path):
    if not path.endswith('.js') and os.path.exists(path + '.js'): path += '.js'
    elif not path.endswith('.js') and os.path.isdir(path) and os.path.exists(os.path.join(path,'index.js')): path=os.path.join(path,'index.js')
    if not os.path.exists(path): missing.add(rel(path)); return None
    r = rel(path)
    if r in files: return r
    src = open(path, encoding='utf-8').read(); files[r] = src
    for m in re.finditer(r'require\(\s*[\'"]([^\'"]+)[\'"]\s*\)', src):
        add_js(resolve_path(os.path.dirname(path), m.group(1)))
    return r
def add_wxml(path):
    if not os.path.exists(path): missing.add(rel(path)); return
    r = rel(path)
    if r in files: return
    src = open(path, encoding='utf-8').read(); files[r] = src
    for m in re.finditer(r'<(wxs|include|import)\b[^>]*\bsrc=["\']([^"\']+)["\']', src):
        p = resolve_path(os.path.dirname(path), m.group(2))
        if m.group(1) == 'wxs': add_wxs(p)
        else: add_wxml(p if p.endswith('.wxml') else p + '.wxml')
def add_wxs(path):
    if not path.endswith('.wxs'): path += '.wxs'
    if not os.path.exists(path): missing.add(rel(path)); return
    r = rel(path)
    if r in files: return
    src = open(path, encoding='utf-8').read(); files[r] = src
    for m in re.finditer(r'require\(\s*[\'"]([^\'"]+)[\'"]\s*\)', src):
        add_wxs(resolve_path(os.path.dirname(path), m.group(1)))
def add_comp(base, is_page=False):
    base = os.path.normpath(base)
    if os.path.isdir(base): base = os.path.join(base, 'index')
    r = rel(base)
    if r in comps: return r
    if not os.path.exists(base + '.json') and not os.path.exists(base + '.wxml'):
        missing.add(r); return None
    conf = json.load(open(base + '.json', encoding='utf-8')) if os.path.exists(base + '.json') else {}
    comps[r] = {'json': conf, 'using': {}, 'page': is_page}
    add_wxml(base + '.wxml'); add_js(base + '.js')
    if os.path.exists(base + '.wxss'):
        css[r] = W.rpx(W.resolve(base + '.wxss', set()))
    for name, p in (conf.get('usingComponents') or {}).items():
        cr = add_comp(resolve_path(os.path.dirname(base), p))
        comps[r]['using'][name] = cr
    return r
# app-level
add_js(os.path.join(ROOT, 'app.js')) if '--with-app' in entries else None
for e in [x for x in entries if not x.startswith('--')]:
    add_comp(os.path.join(ROOT, e), True)
appjson = json.load(open(os.path.join(ROOT, 'app.json'), encoding='utf-8'))
for name, p in (appjson.get('usingComponents') or {}).items():
    comps.setdefault('__app_using__', {'json': {}, 'using': {}, 'page': False})['using'][name] = add_comp(resolve_path(ROOT, p))
appcss = W.rpx(W.resolve(os.path.join(ROOT, 'app.wxss'), set()))
with open(OUT, 'w', encoding='utf-8') as f:
    f.write('window.__MP=' + json.dumps({'files': files, 'css': css, 'comps': comps, 'appcss': appcss}, ensure_ascii=False) + ';\n')
print('files', len(files), 'comps', len(comps), 'css', len(css), 'bytes', os.path.getsize(OUT))
print('missing', sorted(missing)[:60])
