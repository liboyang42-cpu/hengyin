import sys
from common import q
for s in sys.argv[1:]:
    for r in q(s.replace('%', '%%')): print(r)
    print('--')
