# Credits

## Earth imagery (NASA, public domain)

NASA imagery is not copyrighted in the United States, and NASA's Visible Earth use policy allows free re-use. NASA's own image servers (`visibleearth.nasa.gov`, `eoimages.gsfc.nasa.gov`, `neo.gsfc.nasa.gov`) are blocked from the render container, so `render/run.cjs` fetches byte-identical copies of the same NASA files from public GitHub mirrors and checks each one against a pinned SHA-256. None of the imagery is committed. Only the rendered video and stills derived from it are.

| Layer | NASA product (original page) | Mirror used by `run.cjs` | SHA-256 |
|---|---|---|---|
| Surface | Blue Marble Next Generation, December 2004, topography + bathymetry, 21600x10800 (`world.topo.bathy.200412.3x21600x10800.jpg`). Reto Stöckli, NASA Earth Observatory. https://visibleearth.nasa.gov/images/73909/december-blue-marble-next-generation-w-topography-and-bathymetry | https://raw.githubusercontent.com/stdcout1/airline3d/master/airports/world.topo.bathy.200412.3x21600x10800.jpg | `3006c58b…f5d4d4` |
| Clouds | Blue Marble: Clouds, 8192x4096 cloud composite (`cloud_combined_8192`). NASA Goddard / MODIS. https://visibleearth.nasa.gov/images/57747/blue-marble-clouds | https://media.githubusercontent.com/media/Prograda/Skybolt/master/Assets/Globe/Environment/Cloud/cloud_combined_8192.png | `396bee0a…932c37` |
| City lights | Black Marble 2016, 3 km greyscale, 13500x6750 (`BlackMarble_2016_3km_gray.jpg`). NASA Earth Observatory / Suomi NPP VIIRS. https://earthobservatory.nasa.gov/features/NightLights | https://media.githubusercontent.com/media/VeljkoS2/3D-Orbital-Physics-Engine/main/Assets/Planets%20of%20the%20Solar%20System%203D/Materials/earth/BlackMarble_2016_3km_gray.jpg | `3fbc8aae…0b134d` |

The full hashes are in `render/run.cjs`. Use policy: https://visibleearth.nasa.gov/image-use-policy and https://www.nasa.gov/nasa-brand-center/images-and-media/.

`render/prep.py` resamples these files. The surface becomes 8192 px for the globe plus a full-resolution 3960x2400 window over lon 72–138, lat 14–54 for the dive. It also derives a water mask from the BMNG colours. Nothing is painted by hand. No borders, labels or vector map data are used.

## Stars

**Yale Bright Star Catalogue, 5th revised edition** (Hoffleit & Warren, 1991), distributed by NASA HEASARC and the Harvard-Smithsonian CfA (http://tdc-www.harvard.edu/catalogs/bsc5.html). The positions, V magnitudes and spectral colour temperatures are factual catalogue data. They're used through the JSON conversion at https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-short.json (SHA-256 `94b05813…960e0d8`). Stars down to V 5.6 are drawn.

## Code

- three.js 0.170.0, MIT licence (npm `three`), used only by the offline renderer. It does not ship in the mini-program.
- Pillow and numpy are used by `prep.py`, offline only.
- Shaders, noise volumes and the timeline in `render/` are original to this repository. The Chapman-function approximation for sun optical depth follows the published approximation by Christian Schüler (GPU Pro 3, 2012). Scattering coefficients are the standard Earth values used in Bruneton & Neyret (2008).

## Reference

Motion and mood came from a post by `Nal_uh` (x.com/Nal_uh/status/2117021997163221359). No frames, art or assets from it were copied. Realism was checked against NASA's public-domain EPIC and Apollo 17 "Blue Marble" (AS17-148-22727) photographs, which were used for comparison only.
