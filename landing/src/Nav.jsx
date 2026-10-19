import React, { useEffect, useState } from "react"
import { motion } from "framer-motion"
import { PlayButton } from "./ui.jsx"

const LINKS = [
  { href: "#features", label: "Features" },
  { href: "#screenshots", label: "Screenshots" },
  { href: "#how", label: "How it works" },
]

export default function Nav() {
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24)
    onScroll()
    window.addEventListener("scroll", onScroll, { passive: true })
    return () => window.removeEventListener("scroll", onScroll)
  }, [])

  return (
    <motion.header
      initial={{ y: -60, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      transition={{ duration: 0.6, ease: "easeOut" }}
      className={`fixed inset-x-0 top-0 z-50 transition-all duration-300 ${
        scrolled ? "border-b border-ink-700 bg-ink-950/80 backdrop-blur-xl" : ""
      }`}
    >
      <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-5">
        <a href="#top" className="flex items-center gap-2.5">
          <img src="./icon-512.png" alt="SlicePilot" className="h-8 w-8 rounded-lg" />
          <span className="font-display text-lg font-bold tracking-tight text-cream-50">
            Slice<span className="text-ember-400">Pilot</span>
          </span>
        </a>
        <div className="hidden items-center gap-8 md:flex">
          {LINKS.map((l) => (
            <a
              key={l.href}
              href={l.href}
              className="text-sm text-cream-500 transition-colors hover:text-cream-50"
            >
              {l.label}
            </a>
          ))}
        </div>
        <PlayButton className="px-4! py-2! text-sm!" />
      </nav>
    </motion.header>
  )
}
