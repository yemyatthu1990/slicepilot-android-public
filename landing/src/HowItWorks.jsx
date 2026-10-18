import React, { useEffect, useState } from "react"
import { AnimatePresence, motion } from "framer-motion"
import { PhoneMockup, SectionHeading } from "./ui.jsx"

const STEPS = [
  {
    n: "01",
    title: "Find a model",
    text: "Browse what's trending on MakerWorld and Printables, or search all four communities at once — right inside the app. No browser detour, no file manager.",
    shot: "./screenshots/03_printables.png",
    accent: "#ffb14d",
  },
  {
    n: "02",
    title: "Search across everything",
    text: "One query hits MakerWorld, Printables, Thingiverse and Nexprint together. Results land in a single grid so you can compare and pick.",
    shot: "./screenshots/07_search_dark.png",
    accent: "#4fd8ff",
  },
  {
    n: "03",
    title: "Pick a profile, download & slice",
    text: "Every model shows its files, sizes and print profiles. Tap Download & slice and the desktop-class engine takes over — with a progress notification while you keep using your phone.",
    shot: "./screenshots/09_model_detail_dark.png",
    accent: "#ffd08a",
  },
  {
    n: "04",
    title: "Preview & cloud print",
    text: "Review the G-code layer by layer, check print time and material estimates, then send the finished job over the cloud to your Centauri Carbon 2. Press start.",
    shot: "./screenshots/print_finished.png",
    accent: "#f59e2e",
  },
]

const DUR = 5200

export default function HowItWorks() {
  const [active, setActive] = useState(0)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (paused) return
    const t = setInterval(() => setActive((a) => (a + 1) % STEPS.length), DUR)
    return () => clearInterval(t)
  }, [paused, active])

  const step = STEPS[active]

  return (
    <section id="how" className="relative py-24 sm:py-32">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_60%_50%_at_70%_40%,rgba(79,216,255,0.06),transparent_70%)]" />
      <div className="relative mx-auto max-w-6xl px-5">
        <SectionHeading
          eyebrow="How it works"
          title="From idea to first layer in four steps"
          sub="The whole pipeline — discover, slice, preview, print — without ever touching a desktop slicer."
        />

        <div
          className="grid items-center gap-12 lg:grid-cols-[1fr_420px] lg:gap-20"
          onMouseEnter={() => setPaused(true)}
          onMouseLeave={() => setPaused(false)}
        >
          {/* step list */}
          <ol className="order-2 space-y-2 lg:order-1">
            {STEPS.map((s, i) => {
              const isActive = i === active
              return (
                <li key={s.n}>
                  <button
                    onClick={() => setActive(i)}
                    className={`group w-full rounded-2xl border p-5 text-left transition-all duration-300 ${
                      isActive
                        ? "border-ink-600 bg-ink-800/80"
                        : "border-transparent opacity-50 hover:opacity-80"
                    }`}
                  >
                    <div className="flex items-baseline gap-4">
                      <span
                        className="font-mono text-sm transition-colors"
                        style={{ color: isActive ? s.accent : undefined }}
                      >
                        {s.n}
                      </span>
                      <div>
                        <h3 className="font-display text-xl font-semibold text-cream-50">{s.title}</h3>
                        <AnimatePresence initial={false}>
                          {isActive && (
                            <motion.p
                              initial={{ height: 0, opacity: 0 }}
                              animate={{ height: "auto", opacity: 1 }}
                              exit={{ height: 0, opacity: 0 }}
                              transition={{ duration: 0.4 }}
                              className="overflow-hidden text-sm leading-relaxed text-cream-500"
                            >
                              <span className="block pt-2">{s.text}</span>
                            </motion.p>
                          )}
                        </AnimatePresence>
                      </div>
                    </div>
                    {/* auto-advance progress bar */}
                    <div className="mt-3 h-0.5 overflow-hidden rounded bg-ink-700">
                      {isActive && (
                        <motion.div
                          key={`${active}-${paused}`}
                          className="h-full rounded"
                          style={{ background: s.accent }}
                          initial={{ width: paused ? "35%" : "0%" }}
                          animate={{ width: "100%" }}
                          transition={{
                            duration: paused ? 0 : DUR / 1000,
                            ease: "linear",
                          }}
                        />
                      )}
                    </div>
                  </button>
                </li>
              )
            })}
          </ol>

          {/* phone with crossfading screenshots */}
          <div className="order-1 mx-auto w-[270px] sm:w-[300px] lg:order-2">
            <div className="relative">
              <motion.div
                className="absolute -inset-10 -z-10 rounded-full blur-3xl transition-colors duration-700"
                animate={{ backgroundColor: `${step.accent}1f` }}
              />
              <AnimatePresence mode="popLayout">
                <motion.div
                  key={step.shot}
                  initial={{ opacity: 0, y: 24, scale: 0.96 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: -24, scale: 0.96 }}
                  transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
                >
                  <PhoneMockup src={step.shot} alt={step.title} />
                </motion.div>
              </AnimatePresence>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
