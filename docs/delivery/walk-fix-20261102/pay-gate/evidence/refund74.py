import sys
sys.argv=[sys.argv[0]]
exec(open('/home/user/hengyin/docs/delivery/walk-fix-20261102/pay-gate/proof_http.py').read().replace('if __name__ == "__main__":','if False:'))
ptok,_=app_token(PLAYER_PHONE)
r=http("POST","/api/registration/cancel-refund",token=ptok,form={"id":74})
print(j(r,500))
print(q("SELECT payment_status,registration_status FROM cms_registration WHERE id=74"))
print(q("SELECT refund_no,refund_amount,status,payout_status FROM refund_application WHERE source_type='registration' AND source_id=74"))
