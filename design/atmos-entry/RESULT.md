# Round 9 · m3-atmos result

Branch `claude/map-atmos-20261103`, cut from `origin/master` at `c6eac3b8`. Nothing on master, no PR merged, no production touched.

## Item: atmosphere-entry video

**Status:** done in the first pass. Superseded by the second pass below: the video, sheet and render source have since been replaced.

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


## Second pass: make Earth real (ROUND9M3B)

The owner's note on the first video said the Earth looked fake and the background should be black space with stars. The planet is now rebuilt from NASA imagery and lit by physically based atmospheric scattering. The structure, length, resolution, codec profile, cloud fly-through and parting that the root liked are kept.

**Files** (under `design/atmos-entry/`)
- `atmos.mp4`: replaced
- `sheet.jpg`: replaced (16 frames from the encoded video)
- `planet-closeup.jpg`: new. Full-size frame 66 (2.2 s), the planet at its largest just before the volumetric deck forms
- `CREDITS.md`: rewritten with NASA sources, mirror URLs and SHA-256 hashes
- `render/prep.py`: new. Builds textures from the NASA files
- `render/scene.js`, `render/run.cjs`, `render/index.html`: reworked
- `render/textures.js`: deleted (Natural Earth painting is no longer used)

**Spec check** (`ffprobe` on the committed file)

| Asked | Got |
|---|---|
| keep 780x1688 | 780x1688 |
| keep H.264 profile | H.264 High, level 4.0, yuv420p, `+faststart`, no audio |
| 3.5–4 s | 3.8 s, 114 frames at 30 fps |
| under 3 MB | 1,934,704 bytes (CRF 21; CRF 19 came out at 3.1 MB) |
| open on the planet fully visible | frame 0 shows the whole lit disc. The black fade-in was removed |
| pure black space | decoded frame 0: 99.7% of pixels in the top 300 rows are exactly `#000000`, and the rest are star pixels. The purple haze, gradient and vignette were removed |
| last frame | unchanged design: flat lilac-white, mean RGB 241,239,247 |

**What changed**
- **Surface:** NASA Blue Marble Next Generation (Dec 2004), source 21600x10800. The globe uses an 8192 px resample. The dive uses the native-resolution 3960x2400 window over China, so the ground stays sharp to the cut.
- **Clouds:** the NASA Blue Marble cloud composite (8192x4096) on a separate shell 7 km above the ground. It has view parallax and casts soft shadows: the shell is sampled along the sun ray at a blurred mip. The procedural white blobs on the globe are gone.
- **Night side:** NASA Black Marble 2016 lights, dim and warm (sodium-like tint). They appear only past the terminator, fading in over sun elevations of about −7° to +2°, and are dimmed under cloud.
- **Ocean:** BMNG paints sea-floor relief, which can't be seen from orbit, so open water uses a deep navy albedo. Only bright shelf water keeps its texture tint. The sun glint is a small GGX lobe with Fresnel, attenuated by cloud shadow.
- **Atmosphere:** single-scattering Rayleigh + Mie, ray-marched per pixel at real Earth scale heights (8 km / 1.2 km) and standard coefficients. Mie is set to clear maritime air. Sun transmittance uses the Chapman-function approximation. The result is a thin blue limb, brightest on the sunlit side, that dies within a few pixels. The ground near the limb reddens through the long air path. There's no fresnel halo and no glow ring.
- **Lighting:** one hard sun about 59° off the view axis. Most of the disc is lit, and a real terminator crosses the left side to a dark limb. Lighting is linear HDR, then ACES filmic tone mapping. There's no saturation boost, outline or contrast paint.
- **Stars:** the Yale Bright Star Catalogue down to V 5.6, drawn as sub-pixel Gaussian points with brightness from magnitude and colour from catalogue temperature, softened toward white. About 25–30 stars are visible in the opening frame. They're direction-only, so they drift only with camera rotation.
- **Motion:** rotation is already turning slowly on frame 0 and settles onto China. The dive ease is steeper (power 2.9), so the opening reads as a heavy hold before the fall accelerates.
- **Geography:** no borders or labels. The dive ends on China's east side (118°E, 31°N). From 1.3 s to 2.2 s, a real NASA cloud field (a North Pacific storm near 150°W, chosen because it has no swath seams) is blended over the target region. The country's outline therefore disappears under genuine weather before the frame could read as a map. The volumetric deck now starts at 2.2 s instead of 1.75 s, so its puffs no longer float over a still-readable planet. Before the deck closes, they're masked to the planet and never appear in empty space.
- **Banding:** triangular dither runs on every pixel before 8-bit output. The per-pixel scattering jitter was removed because it showed as grain on dark water. I checked a decoded frame across the terminator with gamma 2.2 and contrast 1.6 and saw no visible steps.

**Self-review against real photos.** I compared frames with NASA EPIC full-disc images and Apollo 17's AS17-148-22727, from memory of those images, since NASA hosts are blocked here. I fixed what gave the frames away, in this order:
1. Overexposed, blown clouds: dropped the sun irradiance from 9 to 5 in scene units.
2. The sun near the view axis meant no terminator: moved the sun to about 59° off axis.
3. The glint was a white CG hotspot: it's now a rougher, weaker lobe.
4. Thin cirrus came out as opaque white: added a softer alpha curve.
5. Volumetric "cotton" puffs floated over space at 2.0 s: masked them and delayed the deck.
6. A smooth painted blob hid China: replaced it with a transplanted real cloud field.
7. The first transplant source (South Atlantic) carried MODIS swath seams that showed as vertical streaks over the Loess Plateau: switched to a seam-free North Pacific field.
8. Milky haze over the disc: reduced Mie from the hazy default to clear air.
9. Stars weren't drawn at all, because three's additive blending multiplies by source alpha: fixed with custom ONE/ONE blending.

*Would someone mistake it for a real photo?* `planet-closeup.jpg` and the 1.5–2.2 s frames: I think yes, at phone size. They read as orbital imagery: real weather systems, real terrain colour, dark sea, real shelf colour around Hainan and the Gulf of Tonkin. The opening disc is close. What still gives it away, honestly:
- **Twilight band:** near the terminator there is a soft blue-grey band of sunlit air over dark ground. It's physically motivated, but it's wider and greyer than in EPIC images, so the left limb reads slightly foggy rather than crisp. Fixing it properly would need multiple scattering or ozone absorption, which I didn't add.
- **Cloud texture in the dive:** the cloud composite is 8192 px (about 5 km per pixel), so clouds go soft at the lowest globe altitudes (2.0–2.2 s), while the BMNG ground under them stays sharp. NASA doesn't publish a higher-resolution global cloud composite.
- **Snow-season ground:** the December BMNG month shows snow over Mongolia and northern China. That's real, but with the cloud composite it makes the north look very white.
- **Transplanted weather:** the 1.3–2.2 s overcast over China is real NASA cloud imagery, but it is moved from another place. Its join with the local field is soft-edged and could be spotted frame by frame.
- **The cut:** after 2.7 s, the volumetric fly-through keeps its first-pass lilac palette, which the root liked. It's stylised compared with the photographic planet.
- **Still unverified:** never played on a phone or in the WeChat player. Hosting is unchanged (the config URL stays empty).

**How to re-render**
```
cd design/atmos-entry/render
npm install                       # three@0.170.0
python3 -m venv .cache/venv && .cache/venv/bin/pip install pillow numpy   # only if system Pillow is missing
node run.cjs --crf 21             # fetches + verifies NASA files (~50 MB), runs prep.py, renders, encodes
node run.cjs --scale 0.5 --frames 0,45,66   # quick look
```
Preparing the textures takes about 40 s. A full render takes about 5 minutes on 4 CPU cores with software GL.

STATUS: COMPLETE
