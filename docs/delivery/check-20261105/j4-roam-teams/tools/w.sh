#!/bin/bash
export TZ=Asia/Shanghai RIG_DSF=2 RIG_SHOTS=/home/user/hengyin/docs/delivery/check-20261105/j4-roam-teams/shots
cd /root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb && node walk.mjs "$1" "$2"
