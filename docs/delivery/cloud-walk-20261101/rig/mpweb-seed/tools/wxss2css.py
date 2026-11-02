"""Convert real WXSS to browser CSS: resolve @import, rpx->px (390 phone), :host/page scoping, per-component prefix."""
import re, sys, os
ROOT = sys.argv[1]  # chengyinhub-xcx
import os
K = float(os.environ.get('RPX_W', '390'))/750

def resolve(path, seen):
    path = os.path.normpath(path)
    if path in seen or not os.path.exists(path): return ''
    seen.add(path)
    src = open(path, encoding='utf-8').read()
    def imp(m):
        p = m.group(1)
        full = os.path.join(ROOT, p.lstrip('/')) if p.startswith('/') else os.path.join(os.path.dirname(path), p)
        return resolve(full, seen)
    return re.sub(r'@import\s+["\']([^"\']+)["\']\s*;', imp, src)

def rpx(css):
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
    return re.sub(r'(-?\d*\.?\d+)rpx', lambda m: ('%.2fpx' % (float(m.group(1))*K)).replace('.00px','px'), css)

def split_blocks(css):
    out=[];i=0;n=len(css)
    while i<n:
        j=css.find('{',i)
        if j<0: break
        sel=css[i:j].strip(); depth=1;k=j+1
        while k<n and depth:
            if css[k]=='{': depth+=1
            elif css[k]=='}': depth-=1
            k+=1
        out.append((sel, css[j+1:k-1])); i=k
    return out

def scope(css, prefix, host):
    res=[]
    for sel, body in split_blocks(css):
        if sel.startswith('@keyframes') or sel.startswith('@-webkit-keyframes') or sel.startswith('@font-face'):
            res.append(sel+'{'+body+'}'); continue
        if sel.startswith('@media') or sel.startswith('@supports'):
            res.append(sel+'{'+scope(body,prefix,host)+'}'); continue
        if sel.startswith('@'): continue
        parts=[]
        for s in sel.split(','):
            s=s.strip()
            if not s: continue
            s=re.sub(r'(^|[\s>+~(])page(?=[\s.:{,\[]|$)', lambda m: m.group(1)+(host or '.phone'), s)
            s=re.sub(r'(^|[\s>+~(])image(?=[\s.:{,\[]|$)', r'\1img', s)
            s=re.sub(r'(^|[\s>+~(])(view|text|scroll-view|swiper|swiper-item)(?=[\s.:{,\[]|$)', lambda m: m.group(1)+{'view':'div','text':'span','scroll-view':'.sv','swiper':'.swiper','swiper-item':'.swiper-item'}[m.group(2)], s)
            if host:
                if s.startswith(':host'):
                    s = host + s[5:]
                    s = re.sub(r'^'+re.escape(host)+r'\(([^)]*)\)', lambda m: host+m.group(1), s)
                else:
                    s = host + ' ' + s
            elif prefix:
                s = prefix + ' ' + s if not s.startswith(prefix) else s
            parts.append(s)
        if parts: res.append(','.join(parts)+'{'+body+'}')
    return '\n'.join(res)

if __name__=='__main__':
    mode=sys.argv[2]; f=sys.argv[3]
    css=rpx(resolve(os.path.join(ROOT,f), set()))
    if mode=='global': print(scope(css, '', ''))
    else: print(scope(css, '', '.c-'+mode))
