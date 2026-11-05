# Restart the app with the OSS stand-in settings (dummy, offline).
export RIG_HOME=$HOME/walk-rig CHENGYIN_OSS_ENDPOINT=https://oss-rig.local:9443 CHENGYIN_OSS_BUCKET_NAME=rig-offline CHENGYIN_OSS_ACCESS_KEY_ID=rig-offline-id CHENGYIN_OSS_ACCESS_KEY_SECRET=rig-offline-not-a-secret
$RIG_HOME/venv/bin/python $HOME/rig/docs/delivery/cloud-walk-20261101/rig/app.py restart 2>&1 | tail -1
