import os,sys,pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy"): os.environ.pop(p,None)
sys.path.insert(0,str(pathlib.Path(os.environ["CAND"])/"scripts"/"e2e"))
from chain_lib import http, app_token
t,_=app_token(sys.argv[1]); print(http("POST","/api/verify/dyncode/issue",token=t,form={"registrationId":sys.argv[2],"type":"activity"})["data"]["code"])
