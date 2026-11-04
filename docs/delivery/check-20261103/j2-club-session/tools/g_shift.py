"""FIXTURE TIME SHIFT (throwaway DB): together with seed-walk.py shift-session 9 --start-in-min -1, move the station
service windows frozen at PREPARE back by the same amount so START can run now instead of at 14:30 wall time."""
from common import *
cl.ex("UPDATE game_station_run SET service_start_at=DATE_SUB(service_start_at, INTERVAL 60 MINUTE) WHERE activity_id=9")
print(cl.q("SELECT id,node_id,service_start_at,service_end_at FROM game_station_run WHERE activity_id=9"))
