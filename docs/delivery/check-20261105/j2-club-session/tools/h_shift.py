"""FIXTURE TIME SHIFT (throwaway DB): after moving the session start to now, move the self-hosted stations' service
windows that were frozen at PREPARE (old start 11:14:37) back by 20 minutes so START can run now. Caused by my own
earlier time shift, not by the product."""
from common import *
cl.ex("UPDATE game_station_run SET service_start_at=DATE_SUB(service_start_at, INTERVAL 20 MINUTE) WHERE activity_id=9 AND service_start_at > '2026-11-05 11:05:00'")
print(cl.q("SELECT id,node_id,service_start_at,service_end_at FROM game_station_run WHERE activity_id=9"))
