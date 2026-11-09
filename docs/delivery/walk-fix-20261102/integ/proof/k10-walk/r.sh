#!/bin/bash
# usage: r.sh 'js body'   |  r.sh -s '{"path":...}'
if [ "$1" = "-s" ]; then curl -s -X POST --data-binary "$2" http://127.0.0.1:8111/start; else curl -s -X POST --data-binary "$1" http://127.0.0.1:8111/run; fi; echo
