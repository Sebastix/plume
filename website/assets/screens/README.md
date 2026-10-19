# Public screenshots

Only cropped, metadata-stripped WebP derivatives belong here. Original Android captures stay outside the repository.

Paired captures use `<picture>` and `prefers-color-scheme`, so changing theme preserves the screen being shown. Slight differences in scroll position/content reflect the original captures; no screenshot colors are synthesized.

October 19 captures were 1008 × 2244 pixels. Normal screens lose 112 pixels at the top (status bar) and 56 at the bottom (gesture bar), then resize to 720 pixels wide. Profile photo grids already hide the status bar, so only the bottom 56 pixels are removed. The immersive video has neither system bar; it is shared across themes. The Photos grid also has no top status bar; only its bottom gesture bar is cropped.

| Asset | Original filename (not published) | Top crop | Bottom crop |
| --- | --- | ---: | ---: |
| home-light.webp | Screenshot_20261019-160022.png | 112 | 56 |
| home-dark.webp | Screenshot_20261019-150633.png | 112 | 56 |
| notifications-light.webp | Screenshot_20261019-150737.png | 112 | 56 |
| notifications-dark.webp | Screenshot_20261019-150650.png | 112 | 56 |
| search-light.webp | Screenshot_20261019-150815.png | 112 | 56 |
| search-dark.webp | Screenshot_20261019-150717.png | 112 | 56 |
| articles-light.webp | Screenshot_20261019-160111.png | 112 | 56 |
| articles-dark.webp | Screenshot_20261019-160058.png | 112 | 56 |
| podcasts-light.webp | Screenshot_20261019-160134.png | 112 | 56 |
| podcasts-dark.webp | Screenshot_20261019-160143.png | 112 | 56 |
| profile-photos-light.webp | Screenshot_20261019-160304.png | 0 | 56 |
| profile-photos-dark.webp | Screenshot_20261019-160256.png | 0 | 56 |
| reply-light.webp | Screenshot_20261019-160534.png | 112 | 56 |
| reply-dark.webp | Screenshot_20261019-160501.png | 112 | 56 |
| photos.webp | Screenshot_20261019-150641.png | 0 | 56 |
| video-fullscreen.webp | Screenshot_20261019-150925.png | 0 | 0 |

The shared homepage/blog demo shows the app’s first five tabs in order: Search, Notifications, Home, Photos, Videos. Photos uses the existing immersive dark grid in both themes until a matching light home-grid capture is supplied; the profile photo pair is kept separate rather than represented as the home grid. Video shows the actual full-screen player capture. Home captures share the first post but differ farther down the feed.

The existing `gallery.webp` (feather image), `settings.webp`, and `reactions.webp` remain in the Zapstore selection. `zapstore.yaml` determines its presentation order.
