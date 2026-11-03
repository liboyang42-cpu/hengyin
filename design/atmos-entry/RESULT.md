# Round 9 · m3-atmos result

Branch `claude/map-atmos-20261103`, cut from `origin/master` at `c6eac3b8`. Nothing on master, no PR merged, no production touched.

## Item: atmosphere-entry video

**Status:** done, with honest limits listed below.

**Files** (all under `design/atmos-entry/`, outside the mini-program packages, so no package bytes added)
- `atmos.mp4`: the video
- `sheet.jpg`: 16 evenly spaced frames from the encoded video (8x2)
- `CREDITS.md`: data and code sources
- `render/`: the render source
  - `scene.js`: timeline, three.js scene, GLSL (stars, earth, atmospheric scattering, raymarched clouds, final grade)
  - `textures.js`: earth textures painted from Natural Earth vectors
  - `noise3d.js`: tileable Perlin-Worley and Worley volumes for the clouds
  - `run.cjs`: headless Chrome driver, then ffmpeg encode and contact sheet
  - `index.html`, `package.json`

**Spec check** (from `ffprobe` on the committed file)

| Asked | Got |
|---|---|
| 780x1688 | 780x1688 |
| H.264 | H.264 High, level 4.0, yuv420p, `+faststart` |
| 3.5–4 s | 3.8 s, 114 frames at 30 fps |
| under 3 MB | 1,854,755 bytes |
| no audio | no audio stream |
| white-ish last frame that clears | last frame is a flat lilac white (averages RGB 245,241,247), with no pattern, so the player's fade over the map reads as the fog lifting |

**What it shows**
- 0–0.3 s: fade up from black into deep space with sparse, soft, anti-aliased stars and a faint lilac haze.
- 0.3–2.0 s: a stylised planet, lit from the right, turns in its real direction (west to east). China starts on the night side with warm city lights and turns into morning light, while the camera drifts in.
- 1.6–2.6 s: the camera dives toward China on an eased, accelerating curve. A thin blue limb comes from a small in-shader scattering march, not a fresnel ring alone.
- 2.0–2.7 s: a volumetric cloud deck forms under the camera, lit from above with lilac self-shadowed undersides. It thickens until it covers the frame. The cut from planet to cloud world happens at 2.7 s, while the deck is fully opaque, so it can't be seen.
- 2.85–3.8 s: the camera settles onto the deck, which opens outward from the centre with ragged, noise-driven edges onto soft lilac light. The last 0.3 s lifts to the white-ish end frame.

**Method:** three.js 0.170 and custom GLSL in headless Chromium with SwiftShader (software GL). Every frame is rendered at a fixed timestep (`frame / 30`) and read back as PNG, then encoded with ffmpeg x264 (veryslow, CRF 19). All motion uses smoothstep or quintic easing, with no linear moves and no camera shake. Triangular dither is applied before 8-bit output against banding. Clouds raymarch at full resolution: 72 steps with 5 light steps each, jittered start, energy-conserving integration and Henyey-Greenstein phase.

**Earth source:** Natural Earth vectors (public domain) at a pinned commit. Land, lakes, glaciers, deserts, plateaus, mountain ranges and plains are painted into stylised colour, relief and city-light maps, plus a sharper second texture window over China for the dive. NASA's image servers are blocked from this container, so Blue Marble wasn't used. No photo imagery is in the video. See `CREDITS.md`.

**How to re-render**
```
cd design/atmos-entry/render
npm install                      # three@0.170.0
node run.cjs                     # needs Playwright Chromium + ffmpeg; writes ../atmos.mp4 and ../sheet.jpg
node run.cjs --scale 0.5 --frames 30,60,90   # quick look at single frames
```
The first run downloads Natural Earth and paints the textures, which takes about 15 minutes in software Chrome. The textures are cached under `render/.cache/` (git-ignored), and later runs start in about 15 s. A full render takes about 6 minutes on 4 CPU cores.

**Self-review against the reference.** I looked at the sheet and at full-size frames over four rounds and fixed what didn't hold up:
1. Planet buried under clouds: fixed by equalising the noise so coverage means a real fraction of sky.
2. Starfield too dense and blue: fixed.
3. Flat white after the cut: fixed by redesigning the ending so the camera settles onto a deck that parts.
4. Milky, soft clouds: fixed with a full-resolution cloud pass, denser edges and stronger erosion.

**Unverified / honest limits**
- **Next to the reference, this is good but not equal.** The cloud half is the strongest part: soft, billowed, lilac-shadowed, and it parts convincingly. The planet is clean and readable and ends on China, but it is a crisper, more "illustrated" look than the reference's painterly warm planet. The ocean is still a clear mid blue rather than a warm-shifted tone. The clouds on the planet read as soft cotton patches, not swirled weather systems. The ground under the first cloud wisps (about 2.1–2.4 s) is soft because the textures are 4096 px. Closing the gap would need a hand-tuned palette pass with the art owner, or a higher-resolution China texture window. I didn't want to keep tuning without someone to judge taste.
- **Never played on a phone.** Decode smoothness on low-end Android in the WeChat `<video>` component, how the last frame meets the real map under m1's fade, and how the dark start looks on OLED at low brightness can only be checked on a device. The file is H.264 High@4.0 with `faststart` and no audio, which is the safe profile for WeChat video.
- **Not hosted.** As agreed, hosting comes later. m1's player keeps the config URL empty until then, so it is skipped.
- Rendering uses software GL, so very fine shader detail could differ slightly from a GPU render. That has no effect on the shipped MP4.

STATUS: COMPLETE
