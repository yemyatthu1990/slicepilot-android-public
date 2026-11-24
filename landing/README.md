# SlicePilot — landing page

Marketing landing page for the SlicePilot Android app.

**Stack:** Vite + React 19 + Tailwind CSS v4 + framer-motion + three.js
(react-three-fiber / drei / postprocessing).

## Sections

- **Hero** — three.js scene: a star-shaped model printing itself layer by layer
  (instanced layers, glowing nozzle ring, wireframe target shell, bloom), with
  pointer parallax; framed right of the copy on desktop.
- **Features** — six feature cards + community marquee
  (MakerWorld / Printables / Thingiverse / Nexprint).
- **How it works** — interactive walkthrough synced to a live on-device Android
  screen recording (`public/videos/customize_multicolour_demo.mp4`) inside a
  phone mockup with clickable chapter jumps.
- **Screenshots** — horizontal snap-scroll rail of v1.10.2 Play Store slides
  (WebP exports from `store_listing/screenshots/phone_v1.10.2/`).
- **CTA + footer** — Google Play link, privacy policy, trademark disclaimer.

## Develop

```sh
npm install
npm run dev        # http://localhost:5173
```

## Build / preview

```sh
npm run build      # static site in dist/ (base: "./" — deployable in a subdir)
npm run preview    # serve dist/
```

`dist/` is fully static — drop it on any static host (GitHub Pages, nginx, …).
The production build code-splits three.js into its own lazy chunk (~265 kB gz)
so the page shell paints before WebGL boots.

## Screenshots & video for the page

Refresh from `store_listing/` (`phone_v1.10.2/*.png` -> WebP in
`public/screenshots/v1.10.2/`, `feature_graphic.png` -> `public/`, and
`videos/customize_multicolour_demo.mp4` -> `public/videos/`).

## Debug helper

Append `?goto=#features` (etc.) to the URL to auto-scroll to a section — handy
for headless screenshots (see `scripts/shot.cjs`, uses system chromium via
playwright-core):

```sh
node scripts/shot.cjs
```

## Store copy rule

Never mention "YE3D" anywhere in user-facing copy or the footer (see the
repo-root `AGENTS.md`); say "Centauri Carbon 2".
