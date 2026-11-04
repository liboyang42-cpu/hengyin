from common import *
tok,me=login(sys.argv[1]); m=sys.argv[2]; p=sys.argv[3]; b=json.loads(sys.argv[4]) if len(sys.argv)>4 else None
print(J(cl.http(m,p,token=tok,json_body=b) if m!='GET' else cl.http('GET',p,token=tok))[:3000])
