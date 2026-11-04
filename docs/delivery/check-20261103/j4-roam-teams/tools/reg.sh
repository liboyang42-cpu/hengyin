#!/bin/bash
# reg.sh openid activity ticket phone name
o=$1; a=$2; t=$3; ph=$4; nm=$5
./api.sh $o POST /api/compliance/consents "{\"docType\":\"activity_host_data_sharing\",\"scene\":\"activity_signup\",\"eventType\":\"AGREE\",\"requestId\":\"j4-consent-$o-$a\"}" >/dev/null
b="{\"ownerType\":2,\"ownerId\":$a,\"ticketId\":$t,\"tickets\":[{\"ticketId\":$t,\"count\":1}],\"realName\":\"$nm\",\"phone\":\"$ph\""
sign=$(./api.sh $o POST /api/registration/quote "$b}" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('data',{}).get('quoteSign','') if d.get('data') else 'ERR '+str(d))")
./api.sh $o POST /api/registration/create "$b,\"quoteSign\":\"$sign\",\"requestId\":\"j4-reg-$o-$a\"}"
