import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { FAQS, GUIDES, PLAY_URL, REPO_URL, SITE_URL, VERSION } from "../src/seoData.js"

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DIST = path.resolve(__dirname, "../dist")

const esc = (s) =>
  String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")

const indexPath = path.join(DIST, "index.html")
let indexHtml = fs.readFileSync(indexPath, "utf8")

// Extract the compiled Vite stylesheet so standalone guide pages share the exact Tailwind design system
const cssMatch = indexHtml.match(/href="\.?\/(assets\/[^"]+\.css)"/)
if (!cssMatch) {
  throw new Error("Could not find compiled CSS link in dist/index.html")
}
const cssHref = `/${cssMatch[1]}`

// 1. Build rich JSON-LD graph for the homepage
const homeJsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      "@id": `${SITE_URL}/#website`,
      url: `${SITE_URL}/`,
      name: "SlicePilot",
      description:
        "On-device Android 3D slicer, MakerWorld parametric customizer, multi-colour AMS/Canvas print controller for Elegoo Centauri Carbon 2, and OpenCentauri COSMOS / Klipper (Moonraker) controller with mid-print Skip Part.",
    },
    {
      "@type": "SoftwareApplication",
      "@id": `${SITE_URL}/#app`,
      name: "SlicePilot",
      applicationCategory: "UtilitiesApplication",
      applicationSubCategory: "3D Printing Slicer",
      operatingSystem: "Android",
      softwareVersion: VERSION,
      url: `${SITE_URL}/`,
      downloadUrl: PLAY_URL,
      installUrl: PLAY_URL,
      image: `${SITE_URL}/feature_graphic.png`,
      screenshot: [
        `${SITE_URL}/screenshots/v1.10.2/01_customize_3d.webp`,
        `${SITE_URL}/screenshots/v1.10.2/02_colour_picker.webp`,
        `${SITE_URL}/screenshots/v1.10.2/03_multicolour_ams.webp`,
        `${SITE_URL}/screenshots/v1.10.2/04_gcode_preview.webp`,
        `${SITE_URL}/screenshots/v1.10.2/05_discover_models.webp`,
        `${SITE_URL}/screenshots/v1.10.2/06_printer_control.webp`,
        `${SITE_URL}/screenshots/v1.10.2/07_themes.webp`,
      ],
      offers: {
        "@type": "Offer",
        price: "0",
        priceCurrency: "USD",
      },
      featureList: [
        "Native on-device libslic3r 3D slicing for Android (STL and multi-colour 3MF)",
        "In-app MakerWorld OpenSCAD parametric customizer with inline 3D preview, 48+ curated fonts, and 20-swatch + RGB colour picker",
        "Multi-colour 3MF preview, automatic Elegoo Centauri Carbon 2 Canvas / AMS spool matching, and compact waste-chute prime tower inside the build plate",
        "OpenCentauri COSMOS (Centauri Carbon 1 & 2) and Klipper / Moonraker LAN printing with automatic exclude_object slicing and mid-print Skip Part (2D build plate map)",
        "Unified model search across MakerWorld, Printables, Thingiverse, and Nexprint",
        "Layer-by-layer multi-colour G-code preview with print time, filament weight, and length estimates",
        "Cloud and local Wi-Fi (LAN) printing with live printer camera, temperature, fan, and light controls",
      ],
      author: {
        "@type": "Person",
        name: "Yemyat Thu",
      },
      sameAs: [PLAY_URL, REPO_URL],
    },
    {
      "@type": "VideoObject",
      "@id": `${SITE_URL}/#demo-video`,
      name: "SlicePilot v1.10.2: Customize a MakerWorld Keychain & Multi-Colour AMS Slice on Android",
      description:
        "Live unedited Android screen recording showing SlicePilot customizing a MakerWorld parametric keychain with fonts and filament colour swatches, previewing in 3D, auto-mapping Centauri Carbon 2 AMS spools, slicing natively on-device, and inspecting the multi-colour G-code preview.",
      thumbnailUrl: [`${SITE_URL}/screenshots/demo_poster.jpg`, `${SITE_URL}/feature_graphic.png`],
      uploadDate: "2026-11-24",
      duration: "PT56S",
      contentUrl: `${SITE_URL}/videos/customize_multicolour_demo.mp4`,
      embedUrl: `${SITE_URL}/#how`,
    },
    {
      "@type": "FAQPage",
      "@id": `${SITE_URL}/#faq`,
      mainEntity: FAQS.map((f) => ({
        "@type": "Question",
        name: f.q,
        acceptedAnswer: {
          "@type": "Answer",
          text: f.a,
        },
      })),
    },
  ],
}

// 2. Semantic fallback HTML inside <div id="root"> for non-JS crawlers (LLM bots, Bingbot, curl)
// React immediately mounts over #root on JS-enabled browsers with zero flicker.
const staticHomeBody = `
<div class="bg-ink-950 text-cream-100">
  <header class="border-b border-ink-800">
    <nav class="mx-auto flex h-16 max-w-6xl items-center justify-between px-5" aria-label="Main navigation">
      <a href="/" class="font-display text-lg font-bold text-cream-50">SlicePilot</a>
      <div class="flex items-center gap-6 text-sm text-cream-500">
        <a href="#features">Features</a>
        <a href="#how">Demo</a>
        <a href="#screenshots">Screenshots</a>
        <a href="#faq">FAQ</a>
        <a href="${PLAY_URL}">Get it on Google Play</a>
      </div>
    </nav>
  </header>
  <main class="mx-auto max-w-5xl px-5 py-16 space-y-16">
    <section id="top">
      <p class="font-mono text-xs uppercase tracking-widest text-ember-400">Now on Google Play — v${VERSION}</p>
      <h1 class="mt-4 font-display text-5xl font-extrabold text-cream-50">Your printer's co-pilot.</h1>
      <p class="mt-4 max-w-2xl text-lg text-cream-500">
        SlicePilot puts a desktop-class 3D slicer (native libslic3r), MakerWorld parametric customizer, multi-colour AMS / Canvas printing, and OpenCentauri COSMOS / Klipper (Moonraker) support with mid-print Skip Part in your Android pocket.
      </p>
      <p class="mt-6"><a href="${PLAY_URL}" class="font-semibold text-ember-400">Download SlicePilot Free on Google Play</a></p>
    </section>

    <section id="features">
      <h2 class="font-display text-3xl font-bold text-cream-50">Everything a slicer does. In your pocket.</h2>
      <ul class="mt-6 grid gap-6 sm:grid-cols-2">
        <li><h3 class="font-semibold text-cream-50">In-app MakerWorld parametric customizer</h3><p class="mt-1 text-sm text-cream-500">Personalize MakerWorld OpenSCAD models (name keychains, signs, tags) right on Android with an inline 3D preview, 48+ curated fonts, and a 20-swatch + RGB filament colour picker.</p></li>
        <li><h3 class="font-semibold text-cream-50">Multi-colour 3MF &amp; AMS / Canvas support</h3><p class="mt-1 text-sm text-cream-500">Colour 3D model previews, automatic spool matching against loaded Centauri Carbon 2 AMS / Canvas filaments, and compact waste-chute prime tower placement strictly inside the 256×256 mm build plate.</p></li>
        <li><h3 class="font-semibold text-cream-50">4 model communities in one app</h3><p class="mt-1 text-sm text-cream-500">Search and browse MakerWorld, Printables, Thingiverse, and Nexprint together. Filter customizable models, inspect photos and comments, and download straight into the slicer.</p></li>
        <li><h3 class="font-semibold text-cream-50">Desktop-class on-device engine</h3><p class="mt-1 text-sm text-cream-500">Full native C++ libslic3r slicing running locally on Android. Multi-part placement, supports (including multi-material support interfaces), brim, seams, speed, and background slicing.</p></li>
        <li><h3 class="font-semibold text-cream-50">Multi-colour G-code preview</h3><p class="mt-1 text-sm text-cream-500">Inspect multi-colour 3MF and STL projects in seconds, then scrub sliced G-code layer by layer with print time, filament weight, and length estimates.</p></li>
        <li><h3 class="font-semibold text-cream-50">Cloud, LAN &amp; OpenCentauri COSMOS (Moonraker)</h3><p class="mt-1 text-sm text-cream-500">Connect to your Elegoo Centauri Carbon 1 &amp; 2 over Elegoo Cloud or local Wi-Fi, or connect directly to OpenCentauri COSMOS and Klipper / Moonraker printers with live camera, controls, and mid-print Skip Part (exclude_object).</p></li>
      </ul>
    </section>

    <section id="how">
      <h2 class="font-display text-3xl font-bold text-cream-50">Customize to multi-colour G-code in one flow</h2>
      <ol class="mt-6 space-y-3 text-sm text-cream-500">
        <li><strong class="text-cream-100">01. Customize parametric models in-app:</strong> Open customizable MakerWorld models in SlicePilot, edit names and OpenSCAD parameters, and choose from 48+ curated fonts.</li>
        <li><strong class="text-cream-100">02. Pick filament colours &amp; preview in 3D:</strong> Pick colours with 20 one-tap swatches or RGB sliders and orbit the live multi-colour 3D preview.</li>
        <li><strong class="text-cream-100">03. Auto-map AMS spools &amp; slice on-device:</strong> Automatic Centauri Carbon 2 AMS / Canvas spool matching with compact prime tower placement and background slicing.</li>
        <li><strong class="text-cream-100">04. Inspect G-code &amp; print over cloud or LAN:</strong> Scrub every layer, verify prime tower placement, check time and filament weight, and start printing over cloud or local Wi-Fi.</li>
      </ol>
    </section>

    <section id="faq">
      <h2 class="font-display text-3xl font-bold text-cream-50">Frequently asked questions</h2>
      <dl class="mt-6 space-y-5">
        ${FAQS.map((f) => `<div><dt class="font-semibold text-cream-50">${esc(f.q)}</dt><dd class="mt-1 text-sm text-cream-500">${esc(f.a)}</dd></div>`).join("\n        ")}
      </dl>
    </section>

    <section id="guides">
      <h2 class="font-display text-2xl font-bold text-cream-50">Deep-dive guides</h2>
      <ul class="mt-4 grid gap-3 sm:grid-cols-2">
        ${GUIDES.map((g) => `<li><a href="/${g.slug}/" class="text-ember-400 underline">${esc(g.title)}</a> — ${esc(g.description)}</li>`).join("\n        ")}
      </ul>
    </section>
  </main>
  <footer class="border-t border-ink-800 py-10 text-center text-xs text-cream-500">
    <p><a href="${PLAY_URL}">Google Play</a> · <a href="${REPO_URL}">GitHub</a> · <a href="/llms.txt">llms.txt</a> · <a href="http://yemyat.world/apps/slicepilot/privacy.html">Privacy policy</a></p>
    <p class="mt-3">SlicePilot is an independent app and is not affiliated with, endorsed by, or sponsored by MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, Nexprint, or Elegoo.</p>
  </footer>
</div>`

// Inject JSON-LD before </head> and static HTML inside <div id="root"></div>
const jsonLdTag = `    <script type="application/ld+json">${JSON.stringify(homeJsonLd)}</script>\n  </head>`
if (!indexHtml.includes("</head>") || !indexHtml.includes('<div id="root"></div>')) {
  throw new Error("dist/index.html missing expected </head> or <div id=\"root\"></div> markers")
}
indexHtml = indexHtml
  .replace("</head>", jsonLdTag)
  .replace('<div id="root"></div>', `<div id="root">${staticHomeBody}</div>`)
fs.writeFileSync(indexPath, indexHtml, "utf8")
console.log("Prerendered dist/index.html with JSON-LD + semantic HTML")

// 3. Render the 4 standalone, styled static HTML guide pages
function renderGuidePage(guide) {
  const canonical = `${SITE_URL}/${guide.slug}/`
  const ogImg = `${SITE_URL}${guide.screenshot}`
  const pageFaqs = (guide.faqSubset || []).map((idx) => FAQS[idx]).filter(Boolean)
  const siblingGuides = GUIDES.filter((g) => g.slug !== guide.slug)

  const jsonLd = {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "Article",
        headline: guide.h1,
        description: guide.description,
        image: ogImg,
        datePublished: "2026-11-24",
        dateModified: "2026-11-24",
        author: { "@type": "Person", name: "Yemyat Thu" },
        publisher: {
          "@type": "Organization",
          name: "SlicePilot",
          logo: { "@type": "ImageObject", url: `${SITE_URL}/icon-512.png` },
        },
        mainEntityOfPage: canonical,
      },
      {
        "@type": "BreadcrumbList",
        itemListElement: [
          { "@type": "ListItem", position: 1, name: "SlicePilot", item: `${SITE_URL}/` },
          { "@type": "ListItem", position: 2, name: guide.shortTitle, item: canonical },
        ],
      },
      ...(pageFaqs.length
        ? [
            {
              "@type": "FAQPage",
              mainEntity: pageFaqs.map((f) => ({
                "@type": "Question",
                name: f.q,
                acceptedAnswer: { "@type": "Answer", text: f.a },
              })),
            },
          ]
        : []),
    ],
  }

  const comparisonHtml = guide.comparisonRows
    ? `
      <section class="mt-14">
        <h2 class="font-display text-2xl font-bold text-cream-50 sm:text-3xl">Feature comparison</h2>
        <div class="mt-6 overflow-x-auto rounded-2xl border border-ink-700 bg-ink-900/70">
          <table class="w-full min-w-[620px] border-collapse text-left text-sm">
            <thead>
              <tr class="border-b border-ink-700 bg-ink-800/70 font-display text-cream-50">
                <th class="p-4">Capability</th>
                <th class="p-4 text-ember-400">SlicePilot (v${VERSION})</th>
                <th class="p-4">Elegoo Matrix</th>
                <th class="p-4">Desktop Slicers</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-ink-800 text-cream-300">
              ${guide.comparisonRows
                .map(
                  ([cap, sp, mat, desk]) => `<tr>
                <td class="p-4 font-medium text-cream-100">${esc(cap)}</td>
                <td class="p-4 text-ember-300">${esc(sp)}</td>
                <td class="p-4 text-cream-500">${esc(mat)}</td>
                <td class="p-4 text-cream-500">${esc(desk)}</td>
              </tr>`
                )
                .join("\n              ")}
            </tbody>
          </table>
        </div>
      </section>`
    : ""

  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/png" href="/icon-512.png" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>${esc(guide.title)}</title>
    <meta name="description" content="${esc(guide.description)}" />
    <link rel="canonical" href="${canonical}" />
    <meta property="og:title" content="${esc(guide.title)}" />
    <meta property="og:description" content="${esc(guide.description)}" />
    <meta property="og:url" content="${canonical}" />
    <meta property="og:site_name" content="SlicePilot" />
    <meta property="og:image" content="${ogImg}" />
    <meta property="og:type" content="article" />
    <meta name="twitter:card" content="summary_large_image" />
    <meta name="twitter:title" content="${esc(guide.title)}" />
    <meta name="twitter:description" content="${esc(guide.description)}" />
    <meta name="twitter:image" content="${ogImg}" />
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      href="https://fonts.googleapis.com/css2?family=Sora:wght@400;600;700;800&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;600&display=swap"
      rel="stylesheet"
    />
    <link rel="stylesheet" href="${cssHref}" />
    <script type="application/ld+json">${JSON.stringify(jsonLd)}</script>
  </head>
  <body class="bg-ink-950 text-cream-100 font-sans antialiased">
    <header class="sticky top-0 z-50 border-b border-ink-800 bg-ink-950/90 backdrop-blur-xl">
      <nav class="mx-auto flex h-16 max-w-6xl items-center justify-between px-5" aria-label="Primary">
        <a href="/" class="flex items-center gap-2.5">
          <img src="/icon-512.png" alt="SlicePilot" class="h-8 w-8 rounded-lg" />
          <span class="font-display text-lg font-bold tracking-tight text-cream-50">
            Slice<span class="text-ember-400">Pilot</span>
          </span>
        </a>
        <div class="hidden items-center gap-6 text-sm text-cream-500 md:flex">
          <a href="/" class="transition-colors hover:text-cream-50">Home</a>
          <a href="/#how" class="transition-colors hover:text-cream-50">Live demo</a>
          <a href="/compare/" class="transition-colors hover:text-cream-50">Compare</a>
        </div>
        <a
          href="${PLAY_URL}"
          target="_blank"
          rel="noreferrer"
          class="inline-flex items-center gap-2 rounded-xl bg-cream-50 px-4 py-2 font-display text-sm font-semibold text-ink-950 transition-transform hover:scale-[1.03]"
        >
          Get it on Google Play
        </a>
      </nav>
    </header>

    <main class="mx-auto max-w-5xl px-5 py-16 sm:py-24">
      <nav aria-label="Breadcrumb" class="mb-6 font-mono text-xs text-cream-500">
        <a href="/" class="hover:text-ember-400">SlicePilot</a>
        <span class="mx-2">/</span>
        <span class="text-cream-300">${esc(guide.shortTitle)}</span>
      </nav>

      <div class="grid items-center gap-10 lg:grid-cols-[1fr_290px]">
        <div>
          <p class="font-mono text-xs uppercase tracking-[0.28em] text-ember-400">${esc(guide.eyebrow)}</p>
          <h1 class="mt-4 font-display text-3xl font-extrabold leading-tight text-cream-50 sm:text-5xl">
            ${esc(guide.h1)}
          </h1>
          <p class="mt-5 text-base leading-relaxed text-cream-300 sm:text-lg">${esc(guide.lead)}</p>
          <div class="mt-8 flex flex-wrap items-center gap-4">
            <a
              href="${PLAY_URL}"
              target="_blank"
              rel="noreferrer"
              class="inline-flex items-center gap-3 rounded-xl bg-cream-50 px-5 py-3 font-display text-base font-semibold text-ink-950 transition-transform hover:scale-[1.03]"
            >
              Download Free on Google Play
            </a>
            <a href="/#how" class="text-sm font-medium text-cream-300 transition-colors hover:text-ember-400">
              Watch the 56s live demo →
            </a>
          </div>
        </div>
        <div class="mx-auto w-[240px] sm:w-[270px]">
          <div class="overflow-hidden rounded-[1.75rem] border border-ink-700 bg-ink-900 shadow-[0_25px_70px_-20px_rgba(0,0,0,0.85)]">
            <img src="${guide.screenshot}" alt="${esc(guide.screenshotAlt)}" class="aspect-[9/16] w-full object-cover" />
          </div>
        </div>
      </div>

      ${comparisonHtml}

      <section class="mt-16">
        <h2 class="font-display text-2xl font-bold text-cream-50 sm:text-3xl">Key capabilities</h2>
        <div class="mt-6 grid gap-5 sm:grid-cols-2">
          ${guide.highlights
            .map(
              (h) => `<article class="rounded-2xl border border-ink-700 bg-ink-900/70 p-6">
            <h3 class="font-display text-lg font-semibold text-cream-50">${esc(h.title)}</h3>
            <p class="mt-2 text-sm leading-relaxed text-cream-500">${esc(h.body)}</p>
          </article>`
            )
            .join("\n          ")}
        </div>
      </section>

      ${
        pageFaqs.length
          ? `<section class="mt-16">
        <h2 class="font-display text-2xl font-bold text-cream-50 sm:text-3xl">Frequently asked questions</h2>
        <div class="mt-6 space-y-4">
          ${pageFaqs
            .map(
              (f) => `<article class="rounded-2xl border border-ink-800 bg-ink-900/50 p-5">
            <h3 class="font-display text-base font-semibold text-cream-50">${esc(f.q)}</h3>
            <p class="mt-2 text-sm leading-relaxed text-cream-500">${esc(f.a)}</p>
          </article>`
            )
            .join("\n          ")}
        </div>
      </section>`
          : ""
      }

      <section class="mt-16 border-t border-ink-800 pt-10">
        <p class="font-mono text-xs uppercase tracking-[0.28em] text-ember-400">More SlicePilot guides</p>
        <div class="mt-4 grid gap-3 sm:grid-cols-3">
          ${siblingGuides
            .map(
              (sg) => `<a href="/${sg.slug}/" class="rounded-xl border border-ink-800 bg-ink-900/60 p-4 transition-colors hover:border-ember-500/40">
            <span class="block font-display text-sm font-semibold text-cream-100">${esc(sg.shortTitle)} →</span>
            <span class="mt-1 block text-xs text-cream-500">${esc(sg.description)}</span>
          </a>`
            )
            .join("\n          ")}
        </div>
      </section>
    </main>

    <footer class="border-t border-ink-800 py-12">
      <div class="mx-auto flex max-w-6xl flex-col items-center gap-5 px-5 text-center">
        <nav class="flex flex-wrap justify-center gap-6 text-sm text-cream-500">
          <a class="hover:text-cream-100" href="/">Home</a>
          <a class="hover:text-cream-100" href="${PLAY_URL}">Google Play</a>
          <a class="hover:text-cream-100" href="${REPO_URL}">GitHub</a>
          <a class="hover:text-cream-100" href="/llms.txt">llms.txt</a>
          <a class="hover:text-cream-100" href="http://yemyat.world/apps/slicepilot/privacy.html">Privacy policy</a>
        </nav>
        <p class="max-w-2xl text-xs leading-relaxed text-cream-500/60">
          SlicePilot is an independent app. SlicePilot is not affiliated with, endorsed by, or sponsored by
          MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, Nexprint, or Elegoo. All trademarks belong
          to their respective owners.
        </p>
      </div>
    </footer>
  </body>
</html>
`
}

for (const guide of GUIDES) {
  const dir = path.join(DIST, guide.slug)
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, "index.html"), renderGuidePage(guide), "utf8")
  console.log(`Generated dist/${guide.slug}/index.html`)
}
