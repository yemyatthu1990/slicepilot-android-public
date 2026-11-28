import React, { useState } from "react"
import { AnimatePresence, motion } from "framer-motion"
import { SectionHeading } from "./ui.jsx"
import { FAQS, GUIDES } from "./seoData.js"

export default function FAQ() {
  const [open, setOpen] = useState(0)

  return (
    <section id="faq" className="relative py-24 sm:py-32">
      <div className="mx-auto max-w-4xl px-5">
        <SectionHeading
          eyebrow="FAQ"
          title="Common questions"
          sub="Quick, straight answers about on-device slicing, multi-colour AMS support, and Centauri Carbon 2 compatibility."
        />

        <div className="space-y-3">
          {FAQS.map((item, i) => {
            const isOpen = open === i
            return (
              <div
                key={item.q}
                className={`rounded-2xl border transition-colors ${
                  isOpen ? "border-ink-600 bg-ink-900/90" : "border-ink-800 bg-ink-900/40 hover:border-ink-700"
                }`}
              >
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? -1 : i)}
                  aria-expanded={isOpen}
                  className="flex w-full items-center justify-between gap-4 p-5 text-left"
                >
                  <h3 className="font-display text-base font-semibold text-cream-50 sm:text-lg">{item.q}</h3>
                  <span
                    className={`shrink-0 font-mono text-lg text-ember-400 transition-transform duration-200 ${
                      isOpen ? "rotate-45" : ""
                    }`}
                    aria-hidden="true"
                  >
                    +
                  </span>
                </button>
                <AnimatePresence initial={false}>
                  {isOpen && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: "auto", opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{ duration: 0.25 }}
                      className="overflow-hidden"
                    >
                      <p className="px-5 pb-5 text-sm leading-relaxed text-cream-500 sm:text-base">{item.a}</p>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            )
          })}
        </div>

        {/* Deep-dive guide cards for search visitors & crawlers */}
        <div className="mt-14">
          <p className="mb-4 text-center font-mono text-xs uppercase tracking-[0.28em] text-ember-400">
            Deep-dive guides
          </p>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {GUIDES.map((g) => (
              <a
                key={g.slug}
                href={`/${g.slug}/`}
                className="group rounded-xl border border-ink-800 bg-ink-900/60 p-4 transition-colors hover:border-ember-500/40 hover:bg-ink-900"
              >
                <span className="block font-display text-sm font-semibold text-cream-100 group-hover:text-ember-400">
                  {g.shortTitle} →
                </span>
                <span className="mt-1 block text-xs leading-relaxed text-cream-500 line-clamp-2">
                  {g.description}
                </span>
              </a>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
