import sys; sys.path.insert(0, '/root/sc')
from common import *
tid, club = int(sys.argv[1]), int(sys.argv[2]); atok = cl.admin_token()
prof = {"legalName": "沪上慢走社文化传播（走查样例）有限公司", "unifiedSocialCreditCode": "91310000WALK00001X",
        "licenseObjectKey": "walk/club-license.png", "licenseHash": "walk-license-hash-%d" % club,
        "bankAccountName": "沪上慢走社文化传播（走查样例）有限公司", "bankName": "走查样例银行上海分行",
        "corporateBankAccountNo": "6222000000000000%03d" % (club % 1000), "invoiceType": "NONE", "taxNo": "91310000WALK00001X",
        "financeContactName": "林小舟", "financeContactPhone": "13800001111"}
v = cl.http("GET", "/business/club-payee/%d" % club, token=atok)
if not (v.get('data') or {}).get('verifyStatus') == 'VERIFIED':
    print('payee save', J(cl.http("PUT", "/business/club-payee/%d" % club, token=atok, json_body=prof))[:160])
    rid = cl.q("SELECT id FROM club_corporate_payee_profile WHERE club_id=%s AND is_current=1", (club,))[0][0]
    print('payee verify', J(cl.http("POST", "/business/club-payee/%d/verify/%d" % (club, rid), token=atok, json_body={"pass": True, "licenseHash": prof["licenseHash"]}))[:160])
for kind, h in (("PREP", 4), ("CONTENT", 4), ("ONSITE", 3), ("REVIEW", 1)):
    print('hours', kind, (cl.http("PUT", "/business/club-compensation/%d/hours/plan" % tid, token=atok, json_body={"clubId": club, "hourKind": kind, "plannedHours": h}).get('msg') or '')[:50])
cost = {"taxCost": 0, "paymentCost": 0, "materialCost": 0, "kitCost": 0, "insuranceRiskCost": 0, "acquisitionCost": 0, "platformLaborCost": 0, "otherCost": 0,
        "fixedCompensation": 2000, "bonuses": 0, "platformOnsiteHours": 0, "breakEvenFixedCost": 0, "variableCostPerTicket": 0, "extraApproverUserId": 1, "note": "走查成本版本"}
print('cost', (cl.http("PUT", "/business/topic-edition-funds/%d/cost" % tid, token=atok, json_body=cost).get('msg') or '')[:60])
print('organizer-actor', (cl.http("PUT", "/business/topic/%d/organizer-actor" % tid, token=atok).get('msg') or '')[:60])
