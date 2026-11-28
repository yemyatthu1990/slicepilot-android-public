import React from "react"
import { motion } from "framer-motion"
import { SectionHeading } from "./ui.jsx"

const SHOTS = [
  { src: "./screenshots/v1.10.2/01_customize_3d.webp", label: "Parametric 3D customizer" },
  { src: "./screenshots/v1.10.2/02_colour_picker.webp", label: "Filament colour picker" },
  { src: "./screenshots/v1.10.2/03_multicolour_ams.webp", label: "Multi-colour AMS setup" },
  { src: "./screenshots/v1.10.2/04_gcode_preview.webp", label: "Multi-colour G-code preview" },
  { src: "./screenshots/v1.10.2/05_discover_models.webp", label: "4 model communities" },
  { src: "./screenshots/v1.10.2/06_printer_control.webp", label: "Live printer & camera" },
  { src: "./screenshots/v1.10.2/07_themes.webp", label: "Light & dark themes" },
]

export default function Screenshots() {
  return (
    <section id="screenshots" className="py-24 sm:py-32">
      <SectionHeading
        eyebrow="Screenshots"
        title="Made for the phone in your hand"
        sub="Customize, map AMS spools, preview multi-colour G-code, and control your printer — in warm light or sleek dark theme."
      />
      <motion.div
        initial={{ opacity: 0 }}
        whileInView={{ opacity: 1 }}
        viewport={{ once: true }}
        transition={{ duration: 0.6 }}
        className="rail flex snap-x snap-mandatory gap-6 overflow-x-auto px-[max(1.25rem,calc((100vw-72rem)/2))] pb-6"
      >
        {SHOTS.map((s, i) => (
          <motion.figure
            key={s.src}
            initial={{ opacity: 0, y: 40, rotate: i % 2 ? 1.2 : -1.2 }}
            whileInView={{ opacity: 1, y: 0, rotate: 0 }}
            viewport={{ once: true, margin: "-40px" }}
            transition={{ duration: 0.6, delay: (i % 4) * 0.08, ease: [0.22, 1, 0.36, 1] }}
            className="w-[260px] shrink-0 snap-center sm:w-[295px]"
          >
            <div className="overflow-hidden rounded-[1.75rem] border border-ink-700 bg-ink-900 shadow-[0_25px_70px_-20px_rgba(0,0,0,0.85)] transition-transform duration-300 hover:-translate-y-2 hover:border-ember-500/40">
              <img
                src={s.src}
                alt={`SlicePilot — ${s.label}`}
                loading="lazy"
                className="aspect-[9/16] w-full object-cover"
              />
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
