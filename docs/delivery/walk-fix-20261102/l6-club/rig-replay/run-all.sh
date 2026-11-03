#!/usr/bin/env bash
# Replay l6-club rig evidence against a FRESH rig (up.sh just run). Usage: bash run-all.sh <before|after>
# Needs: RIG_HOME (default ~/walk-rig) and CAND = the candidate worktree the rig was built from.
set -uo pipefail
PHASE=${1:?before|after}
cd "$(dirname "$0")"
export RIG_HOME=${RIG_HOME:-$HOME/walk-rig} CAND=${CAND:?set CAND to the rig candidate worktree}
P=$RIG_HOME/venv/bin/python
mkdir -p "$PHASE"; rm -f state.json
$P fixtures.py theme && $P fixtures.py session 40 && $P fixtures.py members
node shots.mjs "$PHASE" pre                      # C-26 empty state (not prepared)
$P http_checkin.py "$PHASE/http-C-03.txt"        # C-03 HTTP: near / ~2 km + DB read-back
node shots.mjs "$PHASE" checkin                  # C-03 我到了
$P fixtures.py shift -1                          # FIXTURE TIME SHIFT (start a minute ago) before PREPARE
$P fixtures.py cmd PREPARE
node shots.mjs "$PHASE" preready                 # C-09 开始活动 with no teams
$P fixtures.py teams
node shots.mjs "$PHASE" roster                   # C-05 names, C-06 标记到场
$P fixtures.py cmd START
node shots.mjs "$PHASE" running                  # C-04 tools/release (before release), C-26 team rows
$P http_c04.py "$PHASE"                          # C-04 projection, old lead unlock, director unlock + player probe
node shots.mjs "$PHASE" released                 # C-04 workbench after release
$P attend.py walk-player-1 walk-player-2
$P http_counts.py "$PHASE/http-C-10.txt"         # C-10/C-25 counts
node shots.mjs "$PHASE" memberrunning            # C-10 member header + roster
$P fixtures.py cmd FINISH
node shots.mjs "$PHASE" ended                    # C-11 recap
node shots.mjs "$PHASE" memberended              # C-12 member after end
node shots.mjs "$PHASE" enroll                   # C-13 (free theme: no ¥ rows possible)
node shots.mjs "$PHASE" create                   # C-14 duplicate name
