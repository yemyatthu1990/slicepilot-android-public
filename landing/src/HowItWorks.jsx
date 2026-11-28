import React, { useRef, useState } from "react"
import { AnimatePresence, motion } from "framer-motion"
import { SectionHeading } from "./ui.jsx"

const STEPS = [
  {
    n: "01",
    title: "Customize parametric models in-app",
    text: "Open customizable MakerWorld models right inside SlicePilot. Edit names, sizes, and OpenSCAD parameters and pick from 48+ curated fonts with quick-pick chips.",
    start: 0,
    end: 10,
    accent: "#ffb14d",
  },
  {
    n: "02",
    title: "Pick filament colours & preview in 3D",
    text: "Choose colours with 20 one-tap swatches or custom RGB sliders, then refresh and orbit the live multi-colour 3D preview right on your phone.",
    start: 10,
    end: 28,
    accent: "#4fd8ff",
  },
  {
    n: "03",
    title: "Auto-map AMS spools & slice on-device",
    text: "Multi-colour 3MF projects match automatically against the spools loaded in your Centauri Carbon 2 AMS / Canvas, with compact prime tower placement inside the bed.",
    start: 28,
    end: 51,
    accent: "#ffd08a",
  },
  {
    n: "04",
    title: "Inspect G-code & print over cloud or LAN",
    text: "Scrub every layer with print time, filament weight, and length estimates, then send the sliced job straight to your Centauri Carbon 2 over cloud or local Wi-Fi.",
    start: 51,
    end: 56.4,
    accent: "#f59e2e",
  },
]

function stepIndexForTime(t) {
  for (let i = STEPS.length - 1; i >= 0; i--) {
    if (t >= STEPS[i].start) return i
  }
  return 0
}

export default function HowItWorks() {
  const videoRef = useRef(null)
  const [active, setActive] = useState(0)
  const [progress, setProgress] = useState(0)
  const [playing, setPlaying] = useState(true)

  const step = STEPS[active]

  const jumpTo = (index) => {
    setActive(index)
    setProgress(0)
    const v = videoRef.current
    if (!v) return
    v.currentTime = STEPS[index].start
    v.play().catch(() => {})
    setPlaying(true)
  }

  const handleTimeUpdate = () => {
    const v = videoRef.current
    if (!v) return
    const t = v.currentTime
    const idx = stepIndexForTime(t)
    const s = STEPS[idx]
    const span = Math.max(0.1, s.end - s.start)
    const pct = Math.max(0, Math.min(100, ((t - s.start) / span) * 100))
    if (idx !== active) setActive(idx)
    setProgress(pct)
  }

  const togglePlay = () => {
    const v = videoRef.current
    if (!v) return
    if (v.paused) {
      v.play().catch(() => {})
      setPlaying(true)
    } else {
      v.pause()
      setPlaying(false)
    }
  }

  return (
    <section id="how" className="relative py-24 sm:py-32">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_60%_50%_at_70%_40%,rgba(79,216,255,0.06),transparent_70%)]" />
      <div className="relative mx-auto max-w-6xl px-5">
        <SectionHeading
          eyebrow="See it in action"
          title="Customize to multi-colour G-code in one flow"
          sub="Live recording from SlicePilot v1.10.2 on Android — click any step to jump straight to that moment."
        />

        <div className="grid items-center gap-12 lg:grid-cols-[1fr_360px] lg:gap-20">
          {/* Interactive chapter list synced to the video */}
          <ol className="order-2 space-y-2 lg:order-1">
            {STEPS.map((s, i) => {
              const isActive = i === active
              return (
                <li key={s.n}>
                  <button
                    type="button"
                    onClick={() => jumpTo(i)}
                    className={`group w-full rounded-2xl border p-5 text-left transition-all duration-300 ${
                      isActive
                        ? "border-ink-600 bg-ink-800/80"
                        : "border-transparent opacity-55 hover:opacity-85"
                    }`}
                  >
                    <div className="flex items-baseline gap-4">
                      <span
                        className="font-mono text-sm transition-colors"
                        style={{ color: isActive ? s.accent : undefined }}
                      >
                        {s.n}
                      </span>
                      <div className="flex-1">
                        <div className="flex items-center justify-between gap-3">
                          <h3 className="font-display text-xl font-semibold text-cream-50">{s.title}</h3>
                          <span className="font-mono text-xs text-cream-500/70">
                            0:{String(Math.floor(s.start)).padStart(2, "0")}
                          </span>
                        </div>
                        <AnimatePresence initial={false}>
                          {isActive && (
                            <motion.p
                              initial={{ height: 0, opacity: 0 }}
                              animate={{ height: "auto", opacity: 1 }}
                              exit={{ height: 0, opacity: 0 }}
                              transition={{ duration: 0.35 }}
                              className="overflow-hidden text-sm leading-relaxed text-cream-500"
                            >
                              <span className="block pt-2">{s.text}</span>
                            </motion.p>
                          )}
                        </AnimatePresence>
                      </div>
                    </div>
                    <div className="mt-3 h-0.5 overflow-hidden rounded bg-ink-700">
                      {isActive && (
                        <div
                          className="h-full rounded transition-[width] duration-150 ease-linear"
                          style={{ background: s.accent, width: `${Math.max(4, progress)}%` }}
                        />
                      )}
                    </div>
                  </button>
                </li>
              )
            })}
          </ol>

          {/* Phone mockup running the real customize -> multi-colour slicing screen recording */}
          <div className="order-1 mx-auto w-[270px] sm:w-[300px] lg:order-2">
            <div className="relative">
              <motion.div
                className="absolute -inset-10 -z-10 rounded-full blur-3xl transition-colors duration-700"
                animate={{ backgroundColor: `${step.accent}22` }}
              />
              <div className="relative aspect-[9/19.5] w-full overflow-hidden rounded-[2rem] border border-ink-600 bg-black shadow-[0_25px_80px_-20px_rgba(0,0,0,0.9)]">
                <div className="pointer-events-none absolute left-1/2 top-2 z-10 h-5 w-24 -translate-x-1/2 rounded-full bg-black" />
                <video
                  ref={videoRef}
                  src="./videos/customize_multicolour_demo.mp4"
                  poster="./screenshots/demo_poster.jpg"
                  autoPlay
                  loop
                  muted
                  playsInline
                  onTimeUpdate={handleTimeUpdate}
                  onPlay={() => setPlaying(true)}
                  onPause={() => setPlaying(false)}
                  onClick={togglePlay}
                  className="h-full w-full cursor-pointer object-cover"
                />
              </div>
              <div className="mt-4 flex items-center justify-between px-2 font-mono text-xs text-cream-500">
                <span>Live Android capture · v1.10.2</span>
                <button
                  type="button"
                  onClick={togglePlay}
                  className="rounded-full border border-ink-700 bg-ink-900 px-3 py-1 text-cream-300 transition-colors hover:border-ember-500/40 hover:text-ember-400"
                >
                  {playing ? "Pause" : "Play"}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
