# City Monopoly hook: landing on a shop

Shop games do not change City Monopoly. Its board, dice, claim, rent and yield rules stay with the City Monopoly owner. This hook only lets City Monopoly read a shop's game and offer it to a player who lands on that shop.

## Read

`GET /api/shop-games/city-landing?poiId=<roam_poi.id>` (or `?merchantId=<mms_merchant.id>` for a cell bound to a shop without a map place). The Java equivalent is `CityShopLandingHook#onShopLanding(poiId, merchantId)`.

```json
{
  "profile": {
    "merchantId": 7, "poiId": 900, "available": true,
    "kind": {"code": "CAFE", "nameZh": "咖啡茶饮", "nameEn": "Café", "pool": [...], "defaultGame": "qaPick"},
    "source": "DEFAULT | MERCHANT | OPERATOR",
    "allowance": 1,
    "games": [{"key": "qaPick", "nameZh": "快问快答", "seconds": 60, "rounds": 3, "passRounds": 2, "templateId": 12, "ready": true}],
    "rules": {"rounds": 3, "passRounds": 2, "pointsEventType": 31}
  },
  "launch": {"method": "POST", "path": "/api/shop-games/launch", "source": "CITY_LANDING", "needsPoiId": true},
  "changesCityRules": false
}
```

When the cell is not a shop, `profile.available` is false and `profile.reason` is `NOT_A_SHOP`. The read never throws. A failure answers `available: false, reason: UNAVAILABLE`.

`GET /api/shop-games/profile?poiId=` returns the same profile without the launch block.

## Launch (optional, the City Monopoly owner decides)

`POST /api/shop-games/launch` with `{"poiId": 900, "source": "CITY_LANDING", "lat": 30.27, "lng": 120.15}`. You can send `merchantId` instead of `poiId`.

- The location fix is scored with location trust (`SHOP_VISIT`). The player must be within the place's radius plus 50 m, using the shop's own coordinates when only `merchantId` is sent.
- A player gets one play per shop per day, whatever the source. A second launch returns the same play. The game is picked by a fixed rotation over (player, day), never by the player, a plan or a sponsor.
- The answer is `{available, playId, game{key, templateId, ...}, rules, expiresAt, status, playedToday}`. When it is not available, `reason` is one of `NOT_A_SHOP`, `NOT_READY`, `TOO_FAR` or `LOCATION_NOT_TRUSTED`.

The result goes to `POST /api/shop-games/result` with `{playId, roundsWon}`. A win (2 of 3) awards points through `IPointsAwardService.award` (event type 31). That path applies the points governance daily cap and the monthly budget. **A shop-game result never changes a City Monopoly outcome.** If City Monopoly wants a shop-game win to count for something on the board, that is a City Monopoly rule change for its owner to decide.

## What City Monopoly code changed

Nothing. No file under `business/citygame` or `ApiCityGame*` was modified, and `ShopGamesTest.cityMonopolyRulesAreUntouched` fails if a City Monopoly class starts depending on shop games.
