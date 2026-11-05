"""Import a made-up word into the library through the real admin endpoint. Usage: p_import_word.py <word>"""
import sys
from common import *
print(http("POST", "/business/sensitive/words/import", token=admin_token(), json_body={"text": "%s,OTHER,BLOCK" % sys.argv[1]}))
