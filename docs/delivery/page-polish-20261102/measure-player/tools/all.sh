while IFS='|' read n p st; do
  node run.mjs $n "{\"path\":\"$p\",\"identity\":\"e2e-openid-9009\"}" "$st" 2>&1 | grep -E "^route|FAIL" | sed "s/^/$n /"
  ~/walk-rig/venv/bin/python -I annotate.py $n out/$n.png >/dev/null
done < pages.txt
