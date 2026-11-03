# m3-atmos, second pass: make Earth real

Owner on the first video: "The Earth looks a bit fake; could it be made to look a little more realistic? The background could be the blackness of space, with stars and such."

Work on `claude/map-atmos-20261103` in `design/atmos-entry/`. Keep the structure, length (3.5 to 4 s), resolution, H.264 profile, under 3 MB, and the cloud fly-through and parting, which the root liked. Never push to master. Never merge a PR.

## Change

**Earth must read as a photograph from orbit, like astronaut or Apollo imagery. Nothing stylised.**
- Surface: NASA Blue Marble Next Generation, highest resolution your memory and time allow (at least 5400x2700; use tiles for the China region or 21600 if the dive needs it, so nothing goes soft on approach). Public domain only; record URLs in `CREDITS.md`.
- Clouds: a real NASA Earth cloud composite as a separate shell slightly above the surface, with soft shadows cast on the ground. The current white blobs are the biggest giveaway. Remove them.
- Night side: NASA Black Marble city lights, dim and warm, only across a real terminator, with a soft day and night falloff.
- Ocean: deep, dark navy with a small soft sun glint moving over the water. Not mid blue.
- Atmosphere: a thin, physically plausible blue rim, brightest near the sunlit limb and fading to nothing fast. Rayleigh and Mie scattering if possible. No thick glowing halo.
- Lighting: one hard sun. Most of the disc lit, with a real terminator and a real dark limb. Tone-map filmically. No fake saturation, outline or painted contrast.

**Background: pure black space (`#000000`).** Remove the purple haze and any gradient or vignette. Stars: small, sparse, varied in brightness, slightly different in colour, no grid or pattern, no giant glowing stars, drifting very slightly with camera motion only. Real starfield data (Hipparcos, Tycho or NASA) is good if it is public domain.

**Motion.** Slow, heavy, weighty rotation, like real orbital footage, then an accelerating dive. Don't fade the planet in from dark in the first frames; open on it fully visible. Banding is a real risk on black and blue gradients: add fine dither before encoding and check.

**Geography.** No borders or labels of any kind. Still ends on China's east side, and hide the country's outline early enough in clouds that the frame never reads as a map.

## Review yourself

Compare frames side by side with real NASA photographs such as EPIC or Apollo "Blue Marble" images. Ask honestly: would someone mistake this for a real photo? If not, say what still gives it away and fix it. Replace `atmos.mp4` and `sheet.jpg`, and add `planet-closeup.jpg`, a full-size frame of the planet at its largest before the clouds. Update `RESULT.md` with a new section. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`, replacing the old status line so only one remains.
