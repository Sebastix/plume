# Soft Plume

Tagline: **Nostr with nothing in the way.**

Use the tagline on its own. Do not add supporting slogans; describe concrete features when more copy is needed.

Plume uses a rounded sky-blue feather, a lowercase wordmark, and white backgrounds in light mode. Dark mode follows Android automatically, using blue-charcoal backgrounds (`#10171C`), soft light text (`#E5EDF2`), and plume-blue actions. The selected direction is concept 02, Soft Plume. Production marks are lightweight vectors, adapted from that direction.

- Plume blue: `#79C4F2` (brand mark and decorative accents).
- Pale blue: `#F0F9FF` (subtle light-mode surfaces).
- Ink: `#293945` (text).
- Accessible action blue: `#2479A9` (links, buttons, selected controls).
- White: `#FFFFFF` (page and app backgrounds).

Use system sans-serif typography and standard Android components. Avoid decorative cards, drop shadows, and unnecessary animation. Pale brand blue is not used for small text on white.

The interface feather shape lives in `website/assets/feather.svg` and Android's `res/drawable/feather.xml`.

## Launcher and store icon

The launcher uses the 16-bit pixel plume from `scripts/plume-icon.svg` on a `#0F2337` plate, not the smooth interface feather. `scripts/plume-icon-android.py` generates `res/drawable/ic_launcher_plume_foreground.xml` and `ic_launcher_plume_monochrome.xml`; `res/mipmap-anydpi/ic_launcher.xml` assembles them as an adaptive icon with `@color/ic_launcher_background` and a monochrome layer for themed icons. The mark is cropped to the vane (the bare quill is dropped) so it fills the adaptive-icon safe circle, and it is scaled to a 66 dp safe radius. `zapstore-icon.png` is the store export, generated from the same art with a rounded plate.

The system splash screen still uses the smooth `splash_feather` drawable, which the welcome animation recolours by its named `body` path; converting it to the pixel mark would break that transfer and is a separate change.
