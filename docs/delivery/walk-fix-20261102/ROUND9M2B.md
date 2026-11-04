# Lane m2-routes: extra items from the root's review of m1

Do the m2-routes section of `ROUND9.md` first, then these. Same rules. Commit each one.

1. **Swipe cards.** The roam card row (`integ/map-shots/m-fly.jpg`) is white on the dark player map. Make it dark, matching `m-fly` in the prototype. Remove the caption line 「合作商家 · 探店 · 距你很近」: a name, a distance and the action are enough. Owner's rule: no secondary labels.
2. **Pin crowding.** `m-dark-pins.jpg` piles pins around the centre, with photos overlapping and small icon pins inside big ones. Tune spacing and the collision rule so they never overlap at any zoom: at most one photo pin per screen cell, the most relevant wins, the rest become dots until you zoom in. Keep participating shops first. Check at 46 markers and at 200.
3. **Density dots.** Keep real positions, but make `m-zoom-far.jpg` read as a heat of activity: slightly larger dots with a soft glow, and stacked dots brighter. No new labels.
4. **Share sheet.** `a9-shop-share.jpg` shows the shop share card empty below the name. Use the shop's cover photo, and the mini-program code, not a colour field. Look at it yourself.
5. **Atmosphere video default.** The fade from the near-white last frame into the dark map must not flash: end with a short dark dissolve in the player. Test with `design/atmos-entry/atmos.mp4` from `origin/claude/map-atmos-20261103`, and report a frame sequence around the transition.
6. **Main package.** Source is about 3.21 MB, above the target of about 3.15 MB. Move what you can out of `pages/roam` into `subpackageRoam` behind async loading, including your own new code, until it is under 3.15 MB with room. Same rule: behaviour unchanged, no dropped features. Report final bytes.
7. **Small fixes.** `pages/growthcenter/index` friend share says 「来城瘾看看我的城市探索成就」 while the receiver sees their own page: remove that share. Coop, recruit and broadcast reminders still open home: point them at the right page per role, verify each page with the rig.
8. **CI.** `Backend tests` sits on its 15-minute limit. Raise that job's `timeout-minutes` to 20 and nothing else about it, and say so. Don't add any other CI changes.

Add these to `ROUND9-M2-RESULT.md`.
