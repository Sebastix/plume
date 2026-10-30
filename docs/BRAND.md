# Plume

Tagline: **Nostr with nothing in the way.**

Use the tagline on its own. Do not add supporting slogans; describe concrete features when more copy is needed.

Plume uses a rounded graphite feather, a lowercase wordmark, and white backgrounds in light mode. Dark mode follows Android automatically, using neutral charcoal backgrounds (`#121212`), soft light text (`#EDEDED`), and burnt-orange actions. The palette is greyscale; the action color is the only chromatic exception. Production marks are lightweight vectors.

- Graphite: `#4A4A4A` (brand mark and decorative accents).
- Pale grey: `#F2F2F2` (subtle light-mode surfaces).
- Ink: `#1A1A1A` (text).
- Action orange: `#A84300` (links, buttons, selected controls, destructive actions).
- White: `#FFFFFF` (page and app backgrounds).
- Charcoal: `#121212` (dark-mode backgrounds) with `#EDEDED` light text and `#F0894E` actions.

Use system sans-serif typography and standard Android components. Avoid decorative cards, drop shadows, and unnecessary animation. Pale grey is not used for small text on white.

The interface feather shape lives in `website/assets/feather.svg` and Android's `res/drawable/feather.xml`.

## Launcher and store icon

The launcher uses the 16-bit pixel plume from `scripts/plume-icon.svg` on a `#141414` plate, not the smooth interface feather. The pixel art is greyscale; only action controls use orange. `scripts/plume-icon-android.py` generates `res/drawable/ic_launcher_plume_foreground.xml` and `ic_launcher_plume_monochrome.xml`; `res/mipmap-anydpi/ic_launcher.xml` assembles them as an adaptive icon with `@color/ic_launcher_background` and a monochrome layer for themed icons. The mark is cropped to the vane (the bare quill is dropped) so it fills the adaptive-icon safe circle, and it is scaled to a 66 dp safe radius. `zapstore-icon.png` is the store export, generated from the same art with a rounded plate.

The system splash screen still uses the smooth `splash_feather` drawable, which the welcome animation recolours by its named `body` path; it now previews graphite shades, and converting it to the pixel mark would break that transfer and is a separate change.
