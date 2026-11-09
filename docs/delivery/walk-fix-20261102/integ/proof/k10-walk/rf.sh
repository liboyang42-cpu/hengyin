#!/bin/bash
curl -s -X POST --data-binary @"$1" http://127.0.0.1:8111/run; echo
