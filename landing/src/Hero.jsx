import React, { Suspense, lazy } from "react"
import { motion } from "framer-motion"
import { PlayButton } from "./ui.jsx"

const Scene3D = lazy(() => import("./Scene3D.jsx"))

const fade = (delay = 0) => ({
  initial: { opacity: 0, y: 28 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.8, delay, ease: [0.22, 1, 0.36, 1] },
})

export default function Hero() {
  return (
    <section id="top" className="relative min-h-[100svh] overflow-hidden">
      {/* three.js print simulation, layered behind the copy */}
      <div className="absolute inset-0">
        <Suspense fallback={null}>
          <Scene3D />
        </Suspense>
      </div>
      <div className="grid-bg absolute inset-0" />
      {/* left scrim for text legibility */}
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-r from-ink-950 via-ink-950/70 to-transparent md:via-ink-950/40" />
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-40 bg-gradient-to-t from-ink-950 to-transparent" />

      <div className="relative mx-auto flex min-h-[100svh] max-w-6xl flex-col justify-center px-5 pt-24 pb-20">
        <motion.div {...fade(0.1)} className="max-w-2xl">
          <span className="inline-flex items-center gap-2 rounded-full border border-ember-500/30 bg-ember-500/10 px-4 py-1.5 font-mono text-xs text-ember-300">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-ember-400" />
            Now on Google Play — v1.6
          </span>
        </motion.div>

        <motion.h1
          {...fade(0.2)}
          className="mt-6 font-display text-4xl font-extrabold leading-[1.05] tracking-tight sm:text-6xl md:text-7xl"
        >
          Your printer's
          <br />
          <span className="text-gradient">co-pilot.</span>
        </motion.h1>

        <motion.p {...fade(0.35)} className="mt-6 max-w-xl text-lg leading-relaxed text-cream-500 sm:text-xl">
          SlicePilot puts a <span className="text-cream-100 font-medium">desktop-class 3D printing slicer</span> in
          your pocket. Find models from four communities, slice on-device, and send the job straight to your
          Centauri Carbon 2 — no desktop required.
        </motion.p>

        <motion.div {...fade(0.5)} className="mt-9 flex flex-wrap items-center gap-5">
          <PlayButton />
          <a
            href="#how"
            className="group inline-flex items-center gap-2 text-sm font-medium text-cream-300 transition-colors hover:text-ember-400"
          >
            See how it works
            <svg viewBox="0 0 24 24" className="h-4 w-4 transition-transform group-hover:translate-y-0.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M12 5v14m0 0l-6-6m6 6l6-6" />
            </svg>
          </a>
        </motion.div>

        <motion.dl {...fade(0.65)} className="mt-14 flex flex-wrap gap-x-10 gap-y-4">
          {[
            ["4", "model communities"],
            ["100%", "on-device slicing"],
            ["STL + 3MF", "project support"],
          ].map(([n, l]) => (
            <div key={l}>
              <dt className="font-display text-2xl font-bold text-ember-400">{n}</dt>
              <dd className="text-sm text-cream-500">{l}</dd>
            </div>
          ))}
        </motion.dl>
      </div>
    </section>
  )
}
