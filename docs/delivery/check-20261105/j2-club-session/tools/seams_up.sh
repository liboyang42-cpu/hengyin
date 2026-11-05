# Rig seams (not product code): hosts entries, JVM trust for the stand-in cert, OSS and api.weixin.qq.com stand-ins.
S=$HOME/rig/docs/delivery/cloud-walk-20261101/rig/seams
grep -q api.weixin.qq.com /etc/hosts || echo "127.0.0.1 api.weixin.qq.com oss-rig.local rig-offline.oss-rig.local" >> /etc/hosts
keytool -list -keystore /etc/ssl/certs/java/cacerts -storepass changeit -alias rigseam >/dev/null 2>&1 || keytool -importcert -noprompt -alias rigseam -file $S/cert.pem -keystore /etc/ssl/certs/java/cacerts -storepass changeit
pgrep -f fakeoss.py >/dev/null || (cd $S && nohup python3 -I fakeoss.py > $HOME/fakeoss.log 2>&1 &)
pgrep -f wxfake.py >/dev/null || (cd $S && nohup python3 -I wxfake.py > $HOME/wxfake.log 2>&1 &)
