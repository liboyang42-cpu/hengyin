import sys, json
sys.argv=[sys.argv[0]]
exec(open('/home/user/hengyin/docs/delivery/walk-fix-20261102/pay-gate/proof_http.py').read().replace('if __name__ == "__main__":','if False:'))
ptok,_ = app_token(PLAYER_PHONE)
res = buy_pay_refund("C-club-standalone", ptok, 19, 4)
print("\nRESULT " + json.dumps(res, ensure_ascii=False, default=str))
