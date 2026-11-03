# Credits

## Earth

All land, lake, glacier, landform-region and city-light shapes come from **Natural Earth** vector data, which is in the public domain ("All versions of Natural Earth raster + vector map data … are in the public domain").

- Source: https://github.com/nvkelso/natural-earth-vector, commit `ca96624a56bd078437bca8184e78163e5039ad19`
- Files: `ne_50m_land`, `ne_50m_lakes`, `ne_50m_geography_regions_polys`, `ne_50m_glaciated_areas`, `ne_10m_populated_places_simple` (GeoJSON)
- Terms: https://www.naturalearthdata.com/about/terms-of-use/

The data is not committed. `render/run.cjs` downloads it from that pinned commit. The textures are painted from it at render time (`render/textures.js`). No photo or satellite imagery is used. Colours, relief, night lights and clouds are original and procedural.

## Code

- three.js 0.170.0, MIT licence (npm `three`), used only by the offline renderer. It does not ship in the mini-program.
- Shaders, noise volumes and the timeline in `render/` are original to this repository.

## Reference

Motion and mood were referenced from a post by `Nal_uh` (x.com/Nal_uh/status/2117021997163221359). No frames, art or assets from it were copied or used.
