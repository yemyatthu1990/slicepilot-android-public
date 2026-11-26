# Play Store listing

Published 2026-11-24 from Play edit `18093862744728633418` (en-US listing + 7 `phone_v1.10.2` promotional screenshots + `1024×500` feature graphic + v1.10.2 production 100%).
**v1.10.2 (49) went to production at 100 % on 2026-11-24** (Play edit `18093862744728633418`; internal upload edit `17281019097205255164`, bundle sha1 `3cd877681e1b6197278f4945b1fae46bc88ce2a3`, ProGuard mapping + native symbols uploaded).
**v1.10.1 (48) is on the internal track since 2026-11-24** (Play edit `07714278245451368962`, bundle sha1 `a80b3f487eecb7b9cd008c4e0d5b4a5a7be2fcfa`, ProGuard mapping + native symbols uploaded).
**v1.10.0 (47) is on the internal track since 2026-11-24** (Play edit `06110040950066223659`, bundle sha1 `fe5b261f0ced31dbbaeafbf422babf474e4c475d`, ProGuard mapping + native symbols uploaded).
**v1.9.13 (46) went to production at 100 % on 2026-11-23** (Play edit `13275814218166966505`; internal upload edit `09303183755892173428`, bundle sha1 `a6c4e1a3869813c4d1ab1739a587de68dd13656e`, ProGuard mapping + native symbols uploaded).
**v1.9.12 (45) is on the internal track since 2026-11-23** (Play edit `12094404409159502249`, bundle sha1 `ea084abd74074b2018a69e465a4350fc4f95020b`, ProGuard mapping + native symbols uploaded).
**v1.9.11 (44) is on the internal track since 2026-11-23** (Play edit `03715875132519618644`, bundle sha1 `0a1fadf3859e6e273292389758f2b8767cb483c3`, ProGuard mapping + native symbols uploaded).
**v1.9.10 (43) went to production at 100 % on 2026-11-22** (Play edit `17732108991673397774`; internal upload edit `14373001468196358516`, bundle sha1 `e61deeb95622a515dd91c2356246aae57830cc27`, ProGuard mapping + native symbols uploaded).
**v1.9.9 (42) went to production at 100 % on 2026-11-22** (Play edit `04999452179185381205`; internal upload edit `01648696905188893446`, internal publish edit `13562093267041175623`, bundle sha1 `20fe9b5c98f9b1cb214eb569afeffdc80fa18e77`, ProGuard mapping + native symbols uploaded).
**v1.9.8 (41) is on the internal track since 2026-11-22** (Play edit `12439253403719215867`, bundle sha1 `b0a0c17f1e61baa2a6c7afecd51a0ad5c59decb7`, ProGuard mapping + native symbols uploaded).
**v1.9.7 (40) is on the internal track since 2026-11-22** (Play edit `00964468449163369777`, bundle sha1 `6786fcf2c1df5bb84cb0dbbd99b8890e384f4e2b`, ProGuard mapping + native symbols uploaded).
**v1.9.6 (39) went to production at 100 % on 2026-11-14** (Play edit `10678779014496414016`);
production had been v1.9.5 (38) for a few hours, and internal matches at 39 (upload edit
`10814106495084609895`).
Earlier: **v1.9.5 (38) went to production at 100 % on 2026-11-14** (Play edit `02924853408639980845`);
production had been v1.8.3 (32) since 2026-10-21.
The first draft of this copy, edit `01915840855682935073`, expired before it could be submitted:
Play edits live about an hour, so an edit is built and committed in one sitting, not parked.
Screenshots: `store_listing/screenshots/phone_v1.10.2/` (7 promotional 1080×1920 phone slides built from live v1.10.2 Pixel 4a captures + `1024×500` `store_listing/feature_graphic.png`; demo video at `store_listing/videos/customize_multicolour_demo.mp4`; earlier sets live in `phone_v1.9.0/`, `phone_new/`, and `phone/`).

## App name
SlicePilot

## Short description
Customize, slice & multi-colour print 3D models right from your phone

## Full description
SlicePilot brings a full desktop-class 3D slicer, MakerWorld parametric customizer, and multi-colour AMS/Canvas printing straight to your Android phone.

CUSTOMIZE PARAMETRIC MODELS IN-APP
Personalize MakerWorld parametric models — like custom name keychains, signs, and tags — without leaving the app:
- Edit text, sizes, and OpenSCAD options with an interactive inline 3D preview
- Browse 48+ curated fonts with quick-pick chips
- Pick filament colours with 20 one-tap swatches or custom RGB sliders
- Automatic multi-colour 3MF export ready for AMS/Canvas slicing

MULTI-COLOUR 3MF & AMS / CANVAS SUPPORT
- Colour 3D model preview for multi-colour 3MF projects
- Automatic spool matching against the filaments loaded in your Centauri Carbon 2 Canvas
- Smart, compact prime tower placement verified inside your build plate
- Layer-by-layer G-code preview with print time, filament weight, and length estimates

DOWNLOAD MODELS FROM 4 COMMUNITIES
Search and browse four 3D model communities right on your phone:
- MakerWorld
- Printables
- Thingiverse
- Nexprint
See what's popular today, filter customizable models, inspect photos and comments, and download straight into the slicer.

CLOUD & LOCAL LAN PRINTING FOR CENTAURI CARBON 2
Connect to your Centauri Carbon 2 over the cloud or local Wi-Fi: monitor live print progress, watch the live printer camera, control temperatures and fans, and send sliced G-code straight from your phone.

DESKTOP-CLASS ON-DEVICE SLICING ENGINE
- Full slicing engine running natively on your device
- Fast native 3MF/STL parser with multi-part placement controls
- Supports (including multi-material support interfaces), brim/skirt/raft, seam, speed, and quality settings
- Background slicing with progress notifications
- Warm light and sleek dark themes
- Open STL/3MF files directly from your Files app

SlicePilot is an independent app. SlicePilot is not affiliated with, endorsed by, or sponsored by MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, Nexprint or Elegoo. All trademarks belong to their respective owners.

## What's new (1.10.5)
• Fixed supports, support interfaces, brim, and prime tower disappearing from the final G-code preview on large high-detail models (such as 500+ layer prints using adaptive LOD)
• Fixed vertical gaps in tree/normal support previews on medium-large prints while staying within memory limits
• Includes full-height adaptive LOD preview and 3D directional G-code shading

## What's new (1.10.4)
• Fixed G-code preview cutting off the upper half of large, high-detail models (like 500+ layer sculpted figurines): eliminated an odd-layer parser deadlock and added adaptive LOD with outer-wall preservation and segment coalescing so 100% of model height always renders smoothly within memory limits
• Includes v1.10.3's full 3D directional G-code shading, arc toolpath support, and dynamic layer-height rendering

## What's new (1.10.3)
• Fixed G-code preview rendering models flat after slicing: added true 3D directional shading with crisp contrast across top surfaces, curved walls, and layer cutaways
• Improved G-code parser for all layer-change and feature markers, dynamic layer heights/line widths, G2/G3 arc toolpaths, relative positioning, and centered framing without the start-gcode prime line
• Fixed layer slider state when opening a newly sliced preview

## What's new (1.10.2)
<!-- Production rollout (437 chars) covers 1.10.0–1.10.2 since production jumped from 1.9.13:
     • Multi-colour 3MF & AMS/Canvas support on Centauri Carbon 2: colour 3D preview, automatic spool matching, compact waste-chute prime tower strictly inside the build plate, and G-code preview
     • Customize MakerWorld parametric models (like name keychains) right in the app with inline 3D preview, 48+ font & colour pickers, and multi-colour AMS export
     • Fixed post-slice preview navigation, cloud re-auth, camera stability, and LAN uploads
-->
• Multi-colour 3MF & AMS/Canvas support on Centauri Carbon 2: colour 3D preview, automatic spool matching, compact waste-chute prime tower strictly inside the build plate, and G-code preview
• Customize MakerWorld parametric models (like name keychains) right in the app with inline 3D preview, 48+ font & colour pickers, and multi-colour AMS export
• Fixed post-slice preview navigation, cloud re-auth, camera stability, and LAN uploads

## What's new (1.10.1)
• Fixed slice completion sometimes staying on the slicing screen instead of opening the G-code preview & Print screen, and fixed tapping the "Slice complete" notification
• Multi-colour 3MF & AMS/Canvas support on Centauri Carbon 2 with smart prime tower placement inside the build plate
• Customize MakerWorld parametric models right in the app with inline 3D preview, font & colour pickers, and multi-colour AMS export

## What's new (1.10.0)
• Multi-colour 3MF & AMS/Canvas support on Centauri Carbon 2: colour 3D preview, automatic spool matching, multi-colour G-code preview, and smart prime tower placement inside the build plate
• Customize MakerWorld parametric models (like name keychains) right in the app with an inline 3D preview, font & colour pickers, and multi-colour AMS export
• Cloud re-auth, Agora camera, LAN upload, and stability fixes

## What's new (1.9.13)
<!-- Production rollout (471 chars) covers 1.9.11–1.9.13 since production jumped from 1.9.10:
     • Fixed slicing crash when selecting a different support material on Centauri Carbon 2 & Canvas
     • Improved single-nozzle multi-material supports: stays on the single-nozzle profile (no Y offset, proper flushing), defaults to Interface only to cut swaps & waste (with Full support + 0.00 mm Z distance options), and boosts purge for dissimilar materials like PLA/PETG
     • Fixed print notifications flipping to "Starting print…" and reappearing after dismissal when finished
-->
• Improved multi-material support slicing on Centauri Carbon 2 & Canvas: stays on the single-nozzle profile (no Y offset, proper flushing), defaults to Interface only to cut filament swaps & waste (with a Full support option and 0.00 mm Z distance), and boosts purge volume for dissimilar materials like PLA/PETG
• Includes the print-finished notification fix and dual-material support crash fix

## What's new (1.9.12)
• Fixed print notifications flipping to "Starting print…" and reappearing after dismissal when a print finishes
• Fixed slicing crash when selecting a different support material (dual-extruder / multi-spool supports on Centauri Carbon 2 & Canvas)

## What's new (1.9.11)
• Fixed slicing crash when selecting a different support material (dual-extruder / multi-spool supports on Centauri Carbon 2 & Canvas)
• Fixed filament colour list formatting and multi-spool preset/flush-matrix sizing so both different-material and same-material second-spool supports slice cleanly

## What's new (1.9.10)
• Print setup now opens immediately as soon as a model download finishes without pressing Back from Model detail or "See more"
• Fixed out-of-memory crashes during slicing, G-code preview, and remote model image loading (including huge 256 MB MakerWorld bitmaps)
• Fixed Android 15+ 6-hour foreground-service timeout crashes during long prints

## What's new (1.9.9)
<!-- Production rollout ships the 457-character summary below (Play caps notes at 500) covering 1.9.7–1.9.9 since production jumped from 1.9.6:
     • Streamlined Print setup: inline multi-part controls, quick Supports toggle, collapsible Advanced settings, and direct Slice
     • Archived G-code shelf in Files with per-slice thumbnails and model search source filters
     • Stabilized cloud & LAN Centauri Carbon 2 connections with auto-reconnect and reliable case light control
     • Fixed full-screen 3D preview bottom bar insets & Face/Rotate/Mirror/part selection, camera rotation insets, and dark-mode contrast
-->
• Full-screen 3D preview now renders directly in the main edge-to-edge window so the bottom control dock always clears the system navigation bar cleanly
• Fixed Face, Rotate, Mirror, and part selection updates on single-part and rotated models, prevented touches in dock gaps from orbiting the 3D view, and auto-scrolled the selected part into view

## What's new (1.9.8)
• Fixed the full-screen 3D preview tool dock and comments dialog sitting behind the bottom navigation bar on edge-to-edge Android 15/16 screens
• Includes all 1.9.7 updates: streamlined multi-part Print setup, collapsible Advanced settings, archived G-code shelf, stabilized cloud/LAN printer connections, and dark-mode contrast fixes

## What's new (1.9.7)
• Streamlined Print setup: multi-part controls in the 3D card, quick Supports toggle, collapsible Advanced settings, and direct Slice
• Archived G-code shelf in Files with per-slice thumbnails and model search source filter chips
• Stabilized cloud & LAN printer connections with automatic reconnect and reliable case light control
• Fixed full-screen camera rotation insets, dark-mode card titles, and cleaner background styling

## What's new (1.9.6)
• Sign in with Google: the Google login on your Elegoo account now works in the app, so you don't have to type a password
• When you sign in with Google, the ELEGOO Terms and Privacy Policy are confirmed in a dialog instead of leaving the button waiting for a tickbox
• Cloud sign-in failures now name the cause — wrong password, account not configured for this region, expired access token — instead of the server's raw message

## What's new (1.9.5)
<!-- The production rollout ships the 492-character variant below (Play caps notes at 500): a
     production user jumps straight from 1.8.3, so one note has to cover the whole 1.9 line.
     The text above/below is the internal-track wording for 1.9.5 alone.

     Everything since 1.8.3:
     • Home, Printer and Files are now separate tabs, one button style app-wide
     • Full-screen camera with pinch-zoom, a shutter and a photo gallery
     • Comments, photos and votes from MakerWorld, Printables, Thingiverse and Nexprint; in-app sign-in; pair a printer by PIN or QR
     • Multi-part 3MF: pick which pieces print, rotate or move one piece, re-arrange the set — preview and G-code now agree
     • Fixed camera and empty-G-code crashes; 16 KB native libraries for Android 16
-->
• Fixed: the G-code ignored the piece poses you set — a multi-part 3MF sliced wherever the file's author placed the pieces, ignoring rotations, moves and pieces you switched off
• Multi-part files now slice exactly the layout Print setup shows, and the preview matches it piece for piece

## What's new (1.9.4)
• Fixed: the slicing preview ignored the piece poses you set — a multi-part file snapped back to its own layout, and switched-off pieces reappeared, as soon as slicing started
• The slicing preview now matches Print setup and the G-code preview piece for piece, with the selected piece highlighted
• Skipped pieces now stay off the plate while slicing

## What's new (1.9.3)
• Fixed: "Model is larger than the build volume" ignored how the pieces are laid out, so a multi-part file that fits was refused
• Fixed: rotating, skipping or re-arranging a piece left the preview unchanged
• The full-screen viewer's rotate, mirror, move and face controls now act on the selected piece
• The selected piece is highlighted in the preview, and the move arrows follow the view
• Pieces are listed in a compact two-column grid with All / None actions

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

## Screenshot set (7) — v1.10.2
Built from live v1.10.2 captures on the wired Pixel 4a (1080×2340, demo Centauri Carbon 2 with 4 loaded AMS spools, MakerWorld #3049453 parametric keychain) and framed into `1080×1920` promotional slides + a `1024×500` feature graphic (`store_listing/feature_graphic.png`), uploaded in Play edit `18093862744728633418`:

1. `01_customize_3d.png` — MakerWorld parametric keychain customizer with live inline 3D preview & font chips (`Fredoka`, `Alex`)
2. `02_colour_picker.png` — One-tap 20-swatch filament colour picker & RGB sliders
3. `03_multicolour_ams.png` — Multi-colour 3MF Print setup with automatic Centauri Carbon 2 AMS/Canvas spool mapping
4. `04_gcode_preview.png` — Multi-colour G-code preview showing the compact prime tower cleanly inside the build plate + layer slider & estimates
5. `05_discover_models.png` — Browse 4 model communities (MakerWorld, Printables, Thingiverse, Nexprint)
6. `06_printer_control.png` — Live Centauri Carbon 2 printer dashboard, live camera, temperatures, fans, and loaded spools
7. `07_themes.png` — Warm light & sleek dark themes side by side

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
