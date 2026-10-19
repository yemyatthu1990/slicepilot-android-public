import React from "react"
import { motion } from "framer-motion"
import { PhoneMockup, SectionHeading } from "./ui.jsx"

const SHOTS = [
  { src: "./screenshots/03_printables.png", label: "Printables" },
  { src: "./screenshots/05_nexprint.png", label: "Nexprint" },
  { src: "./screenshots/07_search_dark.png", label: "Cross-site search" },
  { src: "./screenshots/09_model_detail_dark.png", label: "Model detail" },
  { src: "./screenshots/slice_settings.png", label: "Slice settings" },
  { src: "./screenshots/slicing.png", label: "Slicing" },
  { src: "./screenshots/print_preview.png", label: "Preview & print setup" },
  { src: "./screenshots/print_finished.png", label: "Cloud print sent" },
]

export default function Screenshots() {
  return (
    <section id="screenshots" className="py-24 sm:py-32">
      <SectionHeading
        eyebrow="Screenshots"
        title="Made for the phone in your hand"
        sub="Light and dark themes, built for one thumb, and never hidden behind the gesture bar."
      />
      <motion.div
        initial={{ opacity: 0 }}
        whileInView={{ opacity: 1 }}
        viewport={{ once: true }}
        transition={{ duration: 0.6 }}
        className="rail flex snap-x snap-mandatory gap-5 overflow-x-auto px-[max(1.25rem,calc((100vw-72rem)/2))] pb-6"
      >
        {SHOTS.map((s, i) => (
          <motion.figure
            key={s.src}
            initial={{ opacity: 0, y: 40, rotate: i % 2 ? 1.5 : -1.5 }}
            whileInView={{ opacity: 1, y: 0, rotate: 0 }}
            viewport={{ once: true, margin: "-40px" }}
            transition={{ duration: 0.6, delay: (i % 4) * 0.08, ease: [0.22, 1, 0.36, 1] }}
            className="w-[240px] shrink-0 snap-center sm:w-[270px]"
          >
            <div className="transition-transform duration-300 hover:-translate-y-2">
              <PhoneMockup src={s.src} alt={`SlicePilot — ${s.label}`} />
            </div>
            <figcaption className="mt-4 text-center font-mono text-xs uppercase tracking-widest text-cream-500">
              {s.label}
            </figcaption>
          </motion.figure>
        ))}
      </motion.div>
    </section>
  )
}
