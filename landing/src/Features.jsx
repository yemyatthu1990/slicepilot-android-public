import React from "react"
import { motion } from "framer-motion"
import { SectionHeading } from "./ui.jsx"

const Icon = ({ d }) => (
  <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d={d} />
  </svg>
)

const FEATURES = [
  {
    icon: "M12 16v-8m0 8l-3.5-3.5M12 16l3.5-3.5M7 20H5a3 3 0 0 1-2.5-4.7 5 5 0 1 1 1.7-8A6 6 0 0 1 19.6 8.5 4.5 4.5 0 0 1 19 17.5L17 18",
    title: "Cloud model browser",
    text: "Search and browse MakerWorld, Printables, Thingiverse and Nexprint at once. See daily hot models, pick the exact print profile, download straight into the slicer.",
  },
  {
    icon: "M7 8V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v3M7 8H5a2 2 0 0 0-2 2v5a2 2 0 0 0 2 2h2m10-9h2a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2m-10 0v3a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2v-3H7Zm2-4h6",
    title: "Cloud printing",
    text: "Sign in, pick your Centauri Carbon 2, and send the finished G-code job from your phone. No SD cards, no desktop in the middle.",
  },
  {
    icon: "M9 3v2m6-2v2M9 19v2m6-2v2M3 9h2m-2 6h2m14-6h2m-2 6h2M6 6h12v12H6zM9.5 9.5h5v5h-5z",
    title: "Desktop-class engine",
    text: "A full slicing engine ported from a desktop slicer, running natively on your device. Supports, adhesion, seams, speed and quality controls — the real thing.",
  },
  {
    icon: "M12 3 3 7.5 12 12l9-4.5L12 3ZM3 12.5 12 17l9-4.5M3 17.5 12 22l9-4.5",
    title: "3MF project support",
    text: "Open 3MF projects and their embedded printer, filament and process settings are respected. STL works too, straight from your Files app.",
  },
  {
    icon: "M4 6h16M4 10h16M4 14h10m-6 4h6m4-4v6m0 0l-2.5-2.5M18 20l2.5-2.5",
    title: "Fast G-code preview",
    text: "Preview huge models in seconds with a fast native parser, progress and time-left estimates, layer-by-layer reveal, print time and material estimates.",
  },
  {
    icon: "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0",
    title: "Slices in the background",
    text: "Slice with a progress notification and keep using your phone. Slicing continues when the app is backgrounded — cancel any time.",
  },
]

const COMMUNITIES = ["MakerWorld", "Printables", "Thingiverse", "Nexprint"]

export default function Features() {
  return (
    <section id="features" className="relative py-24 sm:py-32">
      {/* community marquee */}
      <div className="mb-20 overflow-hidden border-y border-ink-800 py-5 [mask-image:linear-gradient(90deg,transparent,black_15%,black_85%,transparent)]">
        <div className="animate-marquee flex w-max items-center gap-12">
          {[...COMMUNITIES, ...COMMUNITIES, ...COMMUNITIES, ...COMMUNITIES].map((c, i) => (
            <React.Fragment key={i}>
              <span className="font-display text-xl font-semibold tracking-wide text-cream-500/60">{c}</span>
              <span className="text-ember-500/50">✦</span>
            </React.Fragment>
          ))}
        </div>
      </div>

      <div className="mx-auto max-w-6xl px-5">
        <SectionHeading
          eyebrow="Features"
          title="Everything a slicer does. In your pocket."
          sub="No remote desktop, no middle-man computer. The slicer itself runs on your phone."
        />

        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f, i) => (
            <motion.div
              key={f.title}
              initial={{ opacity: 0, y: 32 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.6, delay: (i % 3) * 0.1, ease: [0.22, 1, 0.36, 1] }}
              className="group relative rounded-2xl border border-ink-700 bg-ink-900/70 p-6 transition-colors hover:border-ember-500/40"
            >
              <div className="mb-4 inline-flex rounded-xl bg-ember-500/10 p-3 text-ember-400 transition-colors group-hover:bg-ember-500/20">
                <Icon d={f.icon} />
              </div>
              <h3 className="font-display text-lg font-semibold text-cream-50">{f.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-cream-500">{f.text}</p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  )
}
