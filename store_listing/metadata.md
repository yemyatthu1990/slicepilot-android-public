# Play Store listing

Published 2026-11-13 from Play edit `13677982899649546337` (en-US listing + phone screenshots).
The first draft of this copy, edit `01915840855682935073`, expired before it could be submitted:
Play edits live about an hour, so an edit is built and committed in one sitting, not parked.
Screenshots: `store_listing/screenshots/phone_v1.9.0/` (captured on a Pixel 4a, v1.9.0 build;
earlier sets live in `phone_new/` for v1.6.2 and `phone/` before that).

## App name
SlicePilot

## Short description
Download MakerWorld, Printables, Thingiverse & Nexprint models — slice & print

## Full description
SlicePilot brings a full desktop-class slicer to your Android phone.

DOWNLOAD MODELS FROM THE CLOUD
Find and download models without leaving the app — search and browse four model communities right on your phone:
- MakerWorld
- Printables
- Thingiverse
- Nexprint
Search across all of them at once, see what's popular today, pick the exact print profile you want (file sizes and print presets included) and download it straight into the slicer. No file manager, no browser detour.

CLOUD PRINT FOR THE CENTAURI CARBON 2
SlicePilot talks to the Centauri Carbon 2 over the cloud: sign in, pick your printer, and send the finished G-code job from your phone.

DESKTOP-CLASS SLICING ENGINE
- Full slicing engine ported from a desktop slicer, running on your device
- Fast native parser: preview huge models in seconds with progress and time-left estimates
- 3MF project support: embedded printer, filament and process settings are respected
- Supports, adhesion (skirt/brim/raft), seam, speed and quality controls
- G-code preview with layer-by-layer reveal, print time and material estimates

AND THE NICE TOUCHES
- Slice in the background with a progress notification — keep using your phone
- Slicing continues when the app is backgrounded; cancel any time
- Light and dark themes
- Open STL/3MF files directly from your Files app

SlicePilot is an independent app. SlicePilot is not affiliated with, endorsed by, or sponsored by MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, Nexprint or Elegoo. All trademarks belong to their respective owners.

## What's new (1.9.2)
• Choose which pieces of a multi-part 3MF print — switch any piece off without editing the model
• Rotate a single piece 90°, or re-arrange the whole set on the plate
• Piece names come from the file; unnamed pieces are listed as Part 1, Part 2, …
• The preview draws every piece with its own pose, so what you see is what gets sliced

## What's new (1.9.1)
• Fixed: opening the full-screen camera preview could close the app
• Fixed: the model preview closing the app the same way when it moved between print setup and its full-screen viewer

## What's new (1.9.0)
<!-- What shipped, verbatim: Play caps release notes at 500 characters, so this replaced the
     longer list (the full changelog lives on the GitHub release:
     https://github.com/yemyatthu1990/slicepilot-android-public/releases/tag/v1.9.0). -->
• Home, Printer and Files are separate tabs now, with one button style app-wide
• Full-screen camera: pinch-zoom live view, a shutter, and a photo gallery
• Comments from all four model sites, with their photos and votes
• Sign in to MakerWorld or Nexprint in-app, Bambu Lab code step included
• Fixed: MakerWorld files with no download link, preview/slicer pose mismatch, empty G-code crash

## What's new (1.6.2)
- Search and download models from MakerWorld, Printables, Thingiverse and Nexprint, right inside the app
- Download progress banner so you always know what is downloading
- Much faster previews: huge 3MF models load in seconds with progress and time-left estimates
- Faster slicing: fast 3MF load path, quicker tree supports and brim
- Cancel stops slicing immediately; confirm dialog prevents accidental cancels
- Cloud print to the YE3D Centauri Carbon 2
- Light and dark themes, bottom buttons no longer hidden behind the gesture bar
- Crash fixes: seam dropdown, large model preview uploads

## Screenshot set (max 8) — v1.9.0
Shot on the wired Pixel 4a (1080×2340) off the v1.9.0 debug build, so the two themes and all
three tabs are covered. The real printer was off the network, so frame 5 uses the app's own
demo printer (`demo@demo.com` sign-in → simulated Centauri Carbon 2) instead of an empty
discovery screen; frame 6 is still the honest empty Files tab, and a pairing-sheet frame was
dropped because the sheet renders in its peek state and clipped its own Connect button.

1. `01_home_light` — Home: search, "Popular on MakerWorld" feed, Local model
2. `02_makerworld_grid_light` — MakerWorld source grid
3. `03_model_detail_light` — photos, author, downloads/likes, description, Download & slice
4. `04_makerworld_signin_light` — the MakerWorld (Bambu Lab) sign-in sheet
5. `05_printer_demo_light` — Printer tab on the demo printer: status, temps, filament,
   live camera, controls
6. `06_files_light` — Files tab, Models/Photos with the empty state
7. `07_home_dark` — Home in dark theme
8. `08_nexprint_search_dark` — Nexprint search results in dark theme

## Category
Tools

## Contact
Email: thu.yemyat@gmail.com
Website: http://yemyat.world/apps/slicepilot/privacy.html
Privacy policy: http://yemyat.world/apps/slicepilot/privacy.html

## Data safety draft
Collected:
- Firebase Analytics usage events
- Firebase Crashlytics crash reports
- Cloud sign-in session tokens and basic account information when signed in

Encryption:
- Data is transmitted over HTTPS/TLS where applicable.

Sharing:
- Firebase may process analytics and crash data.
- Cloud sign-in and printer services may process account/session data.

Account deletion:
- Users can sign out in-app, or request deletion by emailing thu.yemyat@gmail.com.
