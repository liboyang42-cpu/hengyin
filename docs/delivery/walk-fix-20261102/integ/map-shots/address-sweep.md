# Round 9 m1 item 9: address sweep

Sweep: `grep -rnE '\{\{[^}]*(address|addressName|poiName|locationName)' --include=*.wxml chengyinhub-xcx`, run after the change on `integ/launch-test-20261103`. 86 lines. Each line is listed below.

One helper does the work: `chengyinhub-xcx/utils/open-address.js`. When there are GCJ-02 coordinates it calls `wx.openLocation` at scale 17. When there is only text and the caller allows it, it calls `POST /api/map/geocode`, which runs on the existing `TencentMapClient` / `MapGeocodeServiceImpl` and is cached on the server for 5 minutes and on the device for the last 60 addresses. Without either, the address has no tap target. For an editor, a tap opens the picker (`chooseLocation`) at the saved point to edit it, and a tap in a read-only state opens the map.

Status key:
- **C**: covered by this change.
- **E**: already opened the map before this change. Left as it was.
- **P**: an editor field. A tap opens the picker at the saved point, and the read-only state opens the map where noted.
- **N**: not covered. The reason is given.
- **L**: the lane lead owns this file (`pages/roam/index.*`).

| # | File:line | What it shows | Status | How / why |
|---|---|---|---|---|
| 1 | subpackageA/pages/myproject/index.wxml:55 | saved project card address | C | `onAddressTap`. Activity rows now carry `latitude/longitude` from `/api/project/my`. Topic rows have text only and use geocode. |
| 2 | components/cy/merchant-card/index.wxml:14 | merchant card address (with coordinates) | C | `onAddress` opens the map. Used by coop/nearby, club venue list and roam discover. |
| 3 | components/cy/merchant-card/index.wxml:15 | merchant card address (no coordinates) | N | Same address with no coordinates on the record, so no tap target. The card tap still opens the shop. |
| 4 | subpackageMember/components/cy/scene-roam-poi-detail/index.wxml:58 | POI detail merchant address | E | Already used `openLocation` when `hasLocation`. The plain branch has no coordinates. |
| 5 | subpackageMember/components/cy/profile/index.wxml:416 | merchant home 门店地址 row | E | `goMerchantLocation`, tappable only with `merchantHasGeo`. |
| 6 | subpackageMember/components/cy/profile/index.wxml:419 | same row value | E | as above |
| 7 | subpackageMember/components/cy/scene-member-order-history/index.wxml:52 | order history activity place | C | `onAddressTap`: activity coordinates or geocode. With no place it falls back to order detail. |
| 8 | subpackageMember/components/cy/scene-member-order-history/index.wxml:71 | order history topic place | C | Same handler. Text only, so it uses geocode. |
| 9 | subpackageMember/components/cy/scene-route-content/index.wxml:112 | `merchant-apply` fallback form text input | N | Free-text input while editing. These scenes route to `pages/merchant/apply`, which has the picker and map (rows 56-59). |
| 10 | subpackageMember/components/cy/scene-route-content/index.wxml:127 | `merchant-decor` fallback form text input | N | Same reason. These scenes route to `pages/merchant/decor` (row 55). |
| 11 | subpackageMember/components/cy/scene-play-activity-detail/index.wxml:36 | activity meeting point | E | `openLocation` already wired when coordinates exist. |
| 12 | subpackageMember/components/cy/scene-play-activity-detail/index.wxml:37 | meeting point without coordinates | N | The activity has no picked point. The publish flow requires one, so this is legacy data only. |
| 13 | subpackageRoam/components/roam-city-guide/index.wxml:18 | city guide list row address | N | The row already opens the place detail, and the address is tappable there (row 14). |
| 14 | subpackageRoam/components/roam-city-guide/index.wxml:33 | city guide place detail address | C | `openPlace`. The catalog has `coordinate: null`, so it uses geocode with the city name as region. |
| 15 | subpackageRoam/official-route/index.wxml:22 | official route stop address | E→C | The row already opened the map. It now goes through the helper. |
| 16 | subpackageRoam/nearby/index.wxml:227 | nearby POI card address (with coordinates) | C | Tapping the address uses the existing `onPoiNavigate`, which now goes through the helper. |
| 17 | subpackageRoam/nearby/index.wxml:228 | same, no coordinates | N | No coordinates on the POI. |
| 18 | subpackageB/pages/im/chat/index.wxml:72 | chat location card data | E→C | `onLocationTap` now goes through the helper, so bad coordinates no longer reach `openLocation`. |
| 19 | subpackageB/pages/im/chat/index.wxml:77 | chat location card address text | E→C | The whole card opens the map. |
| 20 | pages/activity/baoming/baoming.wxml:38 | `addressList` (participant list) | N | Not an address. The variable name matches only. |
| 21 | pages/activity/baoming/baoming.wxml:100 | checkout activity address | C | `onAddressTap`: activity coordinates or geocode. |
| 22 | pages/activity/baoming/baoming.wxml:220 | `addressList` | N | Not an address (participants). |
| 23 | pages/activity/baoming/baoming.wxml:227 | `addressList` | N | Not an address (participants). |
| 24 | pages/activity/baoming/baoming.wxml:239 | `addressList` | N | Not an address (participants). |
| 25 | pages/roam/index.wxml:40 | entry activity card meta | N | The whole card opens the activity; the address is only a fallback for `meta`. A second tap target inside it would compete. |
| 26 | pages/roam/index.wxml:167 | entry card meta | N | Same as row 25. |
| 27 | pages/roam/index.wxml:382 | pace card address | C | Wired by the lead: `catchtap="onAddrTap"` → `open-address.openFromEvent` with the place's coordinates. |
| 28 | pages/roam/index.wxml:490 | POI list address | C | Wired by the lead: `poi__addr` `catchtap="onAddrTap"` with the row's coordinates. |
| 29 | pages/coop/nearby/index.wxml:32 | nearby merchant list | C | Passes `latitude/longitude` and `nav`. The address opens the map, and there is a new 导航 pill. |
| 30 | pages/coop/invite/index.wxml:104 | invite target row data | N | The row is a toggle to select a merchant. The address is only a fallback meta line, and a second tap target would fight the selection. |
| 31 | pages/coop/invite/index.wxml:107 | same row meta | N | Same as row 30. |
| 32 | pages/club/detail/index.wxml:172 | club event card meta (`addressName · category`) | N | The card opens the event. Place and category share one line, and the event detail opens the map (row 11). |
| 33 | pages/club/detail/index.wxml:237 | club fact 所在城市 | N | A city name, not a place. |
| 34 | pages/club/detail/index.wxml:532 | club venue merchant cards | C | Passes `locationLat/locationLng` to the merchant card. |
| 35 | pages/club/detail/index.wxml:959 | AI plan stop address | C | `onAddressTap`: node coordinates or geocode. |
| 36 | pages/club/topic-story/index.wxml:38 | topic story stop address (data attrs) | C | `onAddressTap`: node coordinates or geocode. |
| 37 | pages/club/topic-story/index.wxml:40 | same, text | C | as above |
| 38 | pages/publish/activity/index.wxml:43 | editor 活动地点 row | P | Picker opens at the saved point. Once sold out, the row keeps its existing rule (a toast explains why it can't change, pinned by `publish-activity-edit.test.js`). |
| 39 | pages/publish/activity/index.wxml:44 | same, value | P | as above |
| 40 | pages/publish/activity/index.wxml:46 | field error | N | Error text, not a place. |
| 41 | pages/publish/simple/index.wxml:108 | AI draft node place | P | Picker opens at the confirmed point. In view-only draft mode, a confirmed point opens the map. |
| 42 | pages/publish/fabu/index.wxml:369 | chapter node place | P | `pickNodeLocation` opens the picker at the node's point. |
| 43 | pages/publish/fabu/index.wxml:835 | node sheet 地点 row | P | `choosePoiForNode` opens the picker at `nodesForm`'s point. |
| 44 | pages/publish/fabu/index.wxml:1472 | story map node card | N | The card opens the node editor (row 43). Inside the editor, the address belongs to the editing flow. |
| 45 | pages/play/components/playkit/index.wxml:52 | passes `address` to daily sign | C | See row 47. |
| 46 | pages/play/components/scene-roam-discover/index.wxml:9 | discover merchant cards | C | Passes `locationLat/Lng` and `nav`. The address opens the map, and there is a 导航 pill. |
| 47 | pages/play/components/playkit-dailysign/index.wxml:41 | daily sign 出发 address | C | `onAddressTap` uses geocode (the station address is text only). |
| 48 | pages/play/index.wxml:768 | hero shop 导航 | E | `openHeroNav` |
| 49 | pages/play/index.wxml:769 | same aria | E | |
| 50 | pages/play/index.wxml:771 | same address text | E | |
| 51 | pages/play/merchant/index.wxml:51 | merchant section | E | `openMap` |
| 52 | pages/play/merchant/index.wxml:54 | merchant address | E | `openMap` (says so when there are no coordinates) |
| 53 | pages/searchmap/index.wxml:21 | search result address (data attrs) | C | `onAddressTap`. With coordinates it opens the map, otherwise it opens the activity as before. |
| 54 | pages/searchmap/index.wxml:23 | same, text | C | as above |
| 55 | pages/merchant/decor/index.wxml:392 | shop 店铺地址 cell | P | In edit mode the picker opens at the saved point. Outside edit mode the cell opens the map. |
| 56 | pages/merchant/apply/index.wxml:98 | submitted application address | C | `onAddressTap`: record coordinates or geocode. |
| 57 | pages/merchant/apply/index.wxml:148 | form 店铺地址 (picker) | P | Picker opens at the already picked point. |
| 58 | pages/merchant/apply/index.wxml:149 | field error | N | Error text. |
| 59 | pages/merchant/apply/index.wxml:209 | preview step address | C | Opens the map at the picked point. |
| 60 | pages/merchant/citynode/create/index.wxml:45 | `addressConfirmed` flag | N | A boolean, not an address. |
| 61 | pages/merchant/citynode/create/index.wxml:46 | 已确认/待确认 label | N | Status text. |
| 62 | pages/merchant/citynode/create/index.wxml:56 | picked shop address | P | The name and address block opens the map, and 重新选择 opens the picker at the point. |
| 63 | pages/merchant/citynode/create/index.wxml:59 | hint under `!addressConfirmed` | N | Flag only. |
| 64 | pages/merchant/citynode/create/index.wxml:62 | actions under `!addressConfirmed` | N | Flag only. |
| 65 | pages/merchant/citynode/create/index.wxml:67 | repick button condition | N | Flag only. The button itself opens the picker. |
| 66 | pages/topic/components/project-join/index.wxml:41 | header title fallback (`topicName` or `addressName`) | N | A title, not a place line. |
| 67 | pages/topic/components/project-join/index.wxml:88 | my node address | C | `onAddressTap`: node coordinates or geocode. |
| 68 | pages/topic/components/project-join/index.wxml:268 | card title fallback | N | Title, same as row 66. |
| 69 | pages/topic/components/project-host/index.wxml:328 | pending node address | C | `onAddressTap` |
| 70 | pages/topic/components/cy/chapter-node-form/index.wxml:116 | 到店地址 textarea | P | Edited as text. 地图选点 opens the picker at the confirmed point, and 位置已确认 opens the map. |
| 71 | pages/topic/merchantapply/index.wxml:69 | shop card condition | C | See row 72. |
| 72 | pages/topic/merchantapply/index.wxml:76 | shop address | C | `onAddressTap`: shop coordinates or geocode. |
| 73 | pages/topic/merchantapply/index.wxml:87 | 承接地点 row classes | P | `choosePoiForNode` opens the picker at the saved point. |
| 74 | pages/topic/merchantapply/index.wxml:88 | 承接地点 value | P | as above |
| 75 | pages/topic/merchantapply/index.wxml:90 | field error | N | Error text. |
| 76 | pages/topic/merchantinfo/merchantinfo.wxml:318 | my node address | C | `onAddressTap` |
| 77 | pages/topic/index/index.wxml:438 | topic stop row | E | `goLocation` already opened the map. |
| 78 | pages/topic/index/index.wxml:446 | same, address | E | |
| 79 | pages/index/index.wxml:189 | home upcoming preview subtitle | N | A full-bleed image card. The subtitle falls back to the time, and the card opens the activity, whose detail opens the map. |
| 80 | pages/index/index.wxml:231 | home topic 地点 row (data attrs) | C | `onAddressTap` uses geocode. With no place it falls back to the topic. |
| 81 | pages/index/index.wxml:233 | same, text | C | |
| 82 | pages/index/index.wxml:268 | home activity 地点 row (data attrs) | C | Coordinates or geocode. With no place it falls back to the activity. |
| 83 | pages/index/index.wxml:270 | same, text | C | |
| 84 | pages/square/components/cy/post-compose/index.wxml:47 | preview condition | P | See row 85. |
| 85 | pages/square/components/cy/post-compose/index.wxml:56 | chosen location chip | P | The chip opens the picker at the chosen point. ✕ clears it. |
| 86 | pages/square/components/cy/post-compose/index.wxml:58 | chip text | P | as above |

## Counts (86 lines)

| Status | Lines |
|---|---|
| C, covered by this change, including E→C | 33 |
| E, already opened the map | 11 |
| P, editor (picker for edit, map for view) | 14 |
| N, not covered | 28 |

Not covered, by reason:
- Roam entry cards (25, 26): the whole card opens the activity and the address is only the `meta` fallback, 2 lines.
- Not an address, 15 lines: `addressList` participants (20, 22-24), the `addressConfirmed` flag and its status text (60, 61, 63-65), error text (40, 58, 75), a city name (33) and title fallbacks (66, 68).
- No coordinates on the record and no text worth geocoding, so the line stays plain, 3 lines: the merchant card fallback (3), the activity meeting point (12) and the nearby POI (17). Each is the `wx:else` of a covered element.
- The tap on that spot already does something else, 6 lines: a selection toggle (30, 31), an event card (32), a list row that opens a detail where the address is tappable (13), the story-map node card that opens the node editor (44) and a full-bleed home card (79).
- Fallback free-text forms that are never routed to, because the scene registry sends these scenes to the real pages, 2 lines (9, 10).

Other surfaces in item 9 that the sweep does not reach:
- Coupons: `/api/coupon/myrecvlist` now carries the public issuing shop's `shopName/shopAddress/shopLat/shopLng`. The wallet card shows the address (tap to open the map) and a 导航 pill when there are coordinates.
- Shop share: the merchant home has a share button next to ···, and ··· → 分享主页 now does the same. It opens the shared share card with a `shop` kind. The code comes from `POST /api/merchant/share-code` (`sh.<memberId>` scene, shown only for public merchants). A scan lands on the merchant home.
- Profile: both avatar taps are `button open-type="chooseAvatar"` (the existing crop, upload and review path). The nickname field is `input type="nickname"`.
