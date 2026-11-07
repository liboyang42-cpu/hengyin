import re,os,sys
root=sys.argv[1]
pat=re.compile(r'\{\{\s*([^{}]*?)\s*\}\}')
key=re.compile(r'(?:\.|\b)(state|status|code|type|roleCode|[a-zA-Z]*(?:Status|State|Code|Type))\s*$')
for dp,dn,fn in os.walk(root):
    if 'node_modules' in dp or '/tests' in dp or 'miniprogram_npm' in dp: continue
    for f in fn:
        if not f.endswith('.wxml'): continue
        p=os.path.join(dp,f); s=open(p,encoding='utf8').read()
        # text nodes only
        for m in re.finditer(r'>([^<>]*)<',s):
            txt=m.group(1)
            for e in pat.finditer(txt):
                ex=e.group(1)
                # bare path or ternary/|| ending with a code-ish field
                parts=re.split(r'\|\||\?|:',ex)
                for part in parts:
                    part=part.strip().strip('()')
                    if re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[\w$]+|\[[^\]]+\])*',part) and key.search(part):
                        line=s[:m.start()+e.start()+1].count('\n')+1
                        print(f"{os.path.relpath(p,root)}:{line}: {{{{{ex}}}}}")
                        break
