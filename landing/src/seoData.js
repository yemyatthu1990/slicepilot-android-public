export const SITE_URL = "https://slicepilot.eu"
export const PLAY_URL = "https://play.google.com/store/apps/details?id=world.yemyat.slicepilot"
export const REPO_URL = "https://github.com/yemyatthu1990/slicepilot-android-public"
export const VERSION = "1.10.2"

export const FAQS = [
  {
    q: "Can SlicePilot slice 3MF and STL files directly on Android without a PC?",
    a: "Yes. SlicePilot embeds a full native C++ slicing engine (libslic3r) that runs 100% on your Android phone. You do not need a desktop computer, remote desktop, or cloud slicing server to slice STL or multi-part, multi-colour 3MF projects.",
  },
  {
    q: "Does SlicePilot work with the Elegoo Centauri Carbon 2 and Canvas AMS?",
    a: "Yes. SlicePilot connects to the Elegoo Centauri Carbon 2 over both Elegoo Cloud and local Wi-Fi (LAN). It reads your loaded Canvas / AMS filament spools, automatically maps multi-colour 3MF colours to matching slots, places a compact waste-chute prime tower strictly inside the 256×256 mm build plate, and streams the live printer camera.",
  },
  {
    q: "Can I customize MakerWorld parametric models (like name keychains) on my phone?",
    a: "Yes. SlicePilot includes an in-app MakerWorld parametric customizer for OpenSCAD models. You can edit text, sizes, and model parameters, choose from 48+ curated fonts, pick filament colours with 20 one-tap swatches or custom RGB sliders, orbit the live 3D preview, and export straight into multi-colour AMS slicing.",
  },
  {
    q: "Which 3D model websites can I search inside SlicePilot?",
    a: "SlicePilot lets you search and browse MakerWorld, Printables, Thingiverse, and Nexprint from a single app — including trending models, comments, photos, print profiles, and local STL/3MF files opened from your phone.",
  },
  {
    q: "Can I use SlicePilot with 3D printers other than the Centauri Carbon 2?",
    a: "Direct cloud and local LAN print upload, live camera streaming, and AMS spool syncing are built for the Elegoo Centauri Carbon 2. However, anyone can browse models, customize MakerWorld designs, slice STL/3MF files on-device, preview G-code layers, and save the exported G-code file to their Android device.",
  },
  {
    q: "Does SlicePilot support local LAN printing as well as cloud printing?",
    a: "Yes. You can connect over Elegoo Cloud (including Sign in with Google) to print and monitor from anywhere, or connect directly over local Wi-Fi (LAN) on the same network as your Centauri Carbon 2.",
  },
  {
    q: "Is SlicePilot free, and is it affiliated with Elegoo or Bambu Lab?",
    a: "SlicePilot is free on Google Play. It is an independent open-source project and is not affiliated with, endorsed by, or sponsored by Elegoo, MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, or Nexprint.",
  },
]

export const GUIDES = [
  {
    slug: "elegoo-centauri-carbon-2",
    shortTitle: "Centauri Carbon 2 & AMS",
    title: "Elegoo Centauri Carbon 2 Android App — Cloud, LAN & Canvas AMS Slicer",
    description:
      "Slice multi-colour 3MFs on Android, auto-map Canvas / AMS spools, watch the live camera, and print to your Elegoo Centauri Carbon 2 over cloud or local Wi-Fi with SlicePilot.",
    eyebrow: "Printer guide · Centauri Carbon 2",
    h1: "The on-device Android slicer & print controller for Elegoo Centauri Carbon 2",
    lead:
      "SlicePilot pairs native libslic3r slicing with direct Elegoo Cloud and local LAN control — including automatic Canvas / AMS multi-colour spool matching, compact waste-chute prime towers, and live printer camera viewing.",
    screenshot: "/screenshots/v1.10.2/03_multicolour_ams.webp",
    screenshotAlt: "SlicePilot multi-colour AMS filament mapping for Elegoo Centauri Carbon 2",
    highlights: [
      {
        title: "Automatic Canvas / AMS spool mapping",
        body: "SlicePilot reads the filaments loaded in your Centauri Carbon 2 Canvas slots and automatically maps each colour in a multi-colour 3MF project to the closest loaded spool — with one-tap manual overrides.",
      },
      {
        title: "Compact prime tower verified inside the 256×256 mm bed",
        body: "Uses the Centauri Carbon 2 waste chute (M6211) for colour purging instead of oversized purge blocks, and verifies the generated prime tower bounding box stays strictly inside the build plate.",
      },
      {
        title: "Cloud & local Wi-Fi (LAN) printing",
        body: "Send sliced G-code from anywhere via Elegoo Cloud (email/password or Sign in with Google) or upload directly over your local network with live print progress notifications.",
      },
      {
        title: "Live camera, temperatures & fan control",
        body: "Watch the full-screen pinch-to-zoom camera feed with photo capture, adjust nozzle/bed/chamber temperatures, control cooling and auxiliary fans, and toggle the case light from your phone.",
      },
    ],
    faqSubset: [1, 5, 4],
  },
  {
    slug: "android-3d-slicer",
    shortTitle: "Android 3D Slicer",
    title: "Android 3D Slicer for STL & Multi-Colour 3MF — No PC Required | SlicePilot",
    description:
      "Slice STL and multi-colour 3MF files natively on Android with SlicePilot's embedded desktop-class libslic3r engine. Multi-part placement, supports, G-code preview, and background slicing.",
    eyebrow: "On-device slicing · Android",
    h1: "Desktop-class 3D slicing running natively on Android — no PC needed",
    lead:
      "Most mobile 3D printing apps only send pre-sliced files or forward jobs to a remote server. SlicePilot runs the full C++ libslic3r slicing engine locally on your phone, giving you real slicer controls anywhere.",
    screenshot: "/screenshots/v1.10.2/04_gcode_preview.webp",
    screenshotAlt: "SlicePilot multi-colour G-code layer preview on Android",
    highlights: [
      {
        title: "100% local native libslic3r engine",
        body: "Compiled natively for Android (arm64-v8a, 16 KB page aligned) so your STL and 3MF projects slice fast right on your phone, even offline.",
      },
      {
        title: "Full 3MF & multi-part layout controls",
        body: "Respects embedded 3MF printer, filament, and process settings. Toggle individual parts on or off, rotate, mirror, lay flat on a face, move, or auto-arrange multi-part plates with an interactive 3D preview.",
      },
      {
        title: "Supports, multi-material interfaces & brim",
        body: "Configure normal or tree supports (including dedicated multi-material support interface spools for easy removal), brim/skirt/raft adhesion, seam position, infill, and quality profiles.",
      },
      {
        title: "Layer-by-layer G-code preview & background slicing",
        body: "Keep using your phone while SlicePilot slices in the background with live progress notifications, then scrub every G-code layer with print time, gram weight, and filament length estimates.",
      },
    ],
    faqSubset: [0, 3, 4],
  },
  {
    slug: "makerworld-customizer-android",
    shortTitle: "MakerWorld Customizer",
    title: "Customize MakerWorld Parametric Models on Android (Keychains, Fonts & Colours) | SlicePilot",
    description:
      "Edit MakerWorld OpenSCAD parametric models like custom name keychains right on Android. Inline 3D preview, 48+ curated fonts, 20-swatch + RGB colour picker, and multi-colour 3MF export.",
    eyebrow: "Parametric design · Mobile",
    h1: "Customize MakerWorld parametric models right on your Android phone",
    lead:
      "Skip the desktop browser and awkward mobile web forms. SlicePilot brings MakerWorld OpenSCAD parametric customization into a fast native Android workflow — from typing a name to slicing a multi-colour 3MF.",
    screenshot: "/screenshots/v1.10.2/01_customize_3d.webp",
    screenshotAlt: "SlicePilot in-app MakerWorld parametric keychain customizer with 3D preview",
    highlights: [
      {
        title: "Live inline 3D preview",
        body: "Edit text, dimensions, ring holes, thicknesses, and OpenSCAD options, tap Preview 3D, and orbit the updated multi-colour model right inside the customizer.",
      },
      {
        title: "48+ curated fonts with quick-pick chips",
        body: "Browse popular display, bold, and playful fonts with one-tap chips instead of typing font names manually.",
      },
      {
        title: "20-swatch + RGB filament colour picker",
        body: "Choose base and raised-text colours visually using 20 common filament swatches or fine-tune any colour with RGB sliders — no hex codes required.",
      },
      {
        title: "One-tap multi-colour 3MF export to AMS",
        body: "Tap 'Use preview & slice' to pack your customized multi-colour 3MF directly into Print setup with automatic Centauri Carbon 2 AMS / Canvas spool matching.",
      },
    ],
    faqSubset: [2, 1, 3],
  },
  {
    slug: "compare",
    shortTitle: "Compare Workflows",
    title: "SlicePilot vs. Elegoo Matrix vs. Desktop Slicers — Honest Comparison",
    description:
      "Compare SlicePilot with the official Elegoo Matrix app and desktop slicers (OrcaSlicer / Elegoo Slicer) across on-device slicing, multi-colour 3MF, MakerWorld customizer, and LAN/cloud control.",
    eyebrow: "Comparison · Choose your workflow",
    h1: "SlicePilot vs. Elegoo Matrix vs. Desktop Slicers",
    lead:
      "Every tool has a sweet spot. Here is an honest, factual breakdown of where SlicePilot shines on Android — and when you should still reach for a desktop slicer.",
    screenshot: "/screenshots/v1.10.2/06_printer_control.webp",
    screenshotAlt: "SlicePilot live Centauri Carbon 2 printer dashboard and controls",
    comparisonRows: [
      ["Native on-device STL / 3MF slicing on Android", "Yes (libslic3r)", "No (pre-sliced / cloud)", "Desktop only (PC/Mac)"],
      ["In-app MakerWorld parametric customizer", "Yes (3D preview, 48+ fonts, colour picker)", "No", "Via web browser"],
      ["Search 4 model communities (MakerWorld, Printables, Thingiverse, Nexprint)", "Yes (unified search)", "Nexprint only", "Separate browser tabs"],
      ["Centauri Carbon 2 Cloud + Local LAN printing", "Yes (Cloud & LAN)", "Yes", "Yes (desktop)"],
      ["Multi-colour 3MF & Canvas / AMS spool mapping", "Yes (auto-map + compact prime tower)", "Pre-sliced multi-colour", "Yes (full desktop control)"],
      ["Live printer camera, temps & fan controls", "Yes", "Yes", "Yes"],
      ["Advanced mesh painting & manual modifier meshes", "Basic multi-part / 3MF colours", "No", "Best on desktop"],
      ["Platform availability", "Android (Google Play)", "Android & iOS", "Windows, macOS, Linux"],
    ],
    highlights: [
      {
        title: "Best for phone-first 3D printing",
        body: "Choose SlicePilot when you want to find a model, customize a keychain or sign, slice a multi-colour 3MF, and start a print on your Centauri Carbon 2 directly from the couch or workshop without booting a PC.",
      },
      {
        title: "When to use a desktop slicer",
        body: "Choose OrcaSlicer or Elegoo Slicer on a computer when you need freehand brush painting on single-body STLs, complex negative-part CAD modifiers, or deep custom printer profile engineering.",
      },
    ],
    faqSubset: [0, 1, 6],
  },
]
