"""Rig fixture gap: the seed has no activity categories (sys_category type=2), so 活动类型 shows 「暂无分类」 and no
activity can be published. Insert two made-up type-2 categories into the throwaway rig DB only."""
from common import *
if not q("SELECT id FROM sys_category WHERE type=2"):
    ex("INSERT INTO sys_category(type,parent_id,category_name,level,sort_id,status,del_flag,create_time) VALUES (2,0,'品鉴',0,1,1,0,NOW()),(2,0,'城市漫步',0,2,1,0,NOW())")
print(q("SELECT id,type,category_name FROM sys_category WHERE type=2"))
