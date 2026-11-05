from common import *
a = admin_token()
print(http("POST", "/business/sensitive/words/import", token=a, json_body={"text": "# j5 made-up\n%s,OTHER,BLOCK" % WORD}))
print(http("POST", "/business/sensitive/allows/import", token=a, json_body={"text": ALLOW}))
print(q("SELECT word,action,enabled FROM content_sensitive_word"), q("SELECT phrase FROM content_sensitive_allow"))
print(q("SELECT config_key,config_value FROM sys_config WHERE config_key LIKE 'content.%%' OR config_key LIKE '%%sec%%'"))
