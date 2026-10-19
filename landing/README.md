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
- **How it works** — auto-advancing animated usage guide; screenshots in a phone
  mockup crossfade with each step (pauses on hover, clickable steps).
- **Screenshots** — horizontal snap-scroll rail of phone mockups
  (copied from `store_listing/screenshots/phone_new/`).
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

## Screenshots for the page

Refresh from on-device captures with:

```sh
cp ../store_listing/screenshots/phone_new/*.png public/screenshots/
```

## Debug helper

Append `?goto=#features` (etc.) to the URL to auto-scroll to a section — handy
for headless screenshots (see `scripts/shot.cjs`, uses system chromium via
playwright-core):

```sh
node scripts/shot.cjs
```

## Store copy rule

Never mention "YE3D" in user-facing copy (see the repo-root `AGENTS.md`); say
"Centauri Carbon 2". The trademark disclaimer in the footer is the one place
the name appears, mirroring the Play Store listing.
