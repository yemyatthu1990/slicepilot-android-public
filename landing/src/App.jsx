import React from "react"
import { motion } from "framer-motion"
import Nav from "./Nav.jsx"
import Hero from "./Hero.jsx"
import Features from "./Features.jsx"
import HowItWorks from "./HowItWorks.jsx"
import Screenshots from "./Screenshots.jsx"
import { PlayButton } from "./ui.jsx"

function CTA() {
  return (
    <section className="relative overflow-hidden py-24 sm:py-32">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_60%_60%_at_50%_100%,rgba(245,158,46,0.12),transparent_70%)]" />
      <motion.div
        initial={{ opacity: 0, y: 40 }}
        whileInView={{ opacity: 1, y: 0 }}
        viewport={{ once: true }}
        transition={{ duration: 0.8, ease: [0.22, 1, 0.36, 1] }}
        className="relative mx-auto max-w-3xl px-5 text-center"
      >
        <p className="mb-3 font-mono text-xs uppercase tracking-[0.3em] text-ember-400">Ready when you are</p>
        <h2 className="font-display text-3xl font-bold leading-tight text-cream-50 sm:text-5xl">
          Fire up the slicer.
          <br />
          <span className="text-gradient">Hit print from anywhere.</span>
        </h2>
        <p className="mx-auto mt-5 max-w-xl text-cream-500 sm:text-lg">
          Free on Android. Works with the Centauri Carbon 2 over the cloud.
        </p>
        <div className="mt-9 flex justify-center">
          <PlayButton className="px-7! py-4!" />
        </div>
      </motion.div>
    </section>
  )
}

function Footer() {
  return (
    <footer className="border-t border-ink-800 py-12">
      <div className="mx-auto flex max-w-6xl flex-col items-center gap-6 px-5 text-center">
        <div className="flex items-center gap-2.5">
          <img src="./icon-512.png" alt="SlicePilot" className="h-7 w-7 rounded-lg" />
          <span className="font-display font-bold text-cream-50">
            Slice<span className="text-ember-400">Pilot</span>
          </span>
        </div>
        <nav className="flex gap-6 text-sm text-cream-500">
          <a className="transition-colors hover:text-cream-100" href="http://yemyat.world/apps/slicepilot/privacy.html">
            Privacy policy
          </a>
          <a className="transition-colors hover:text-cream-100" href="mailto:thu.yemyat@gmail.com">
            Contact
          </a>
          <a className="transition-colors hover:text-cream-100" href="https://play.google.com/store/apps/details?id=world.yemyat.slicepilot">
            Google Play
          </a>
        </nav>
        <p className="max-w-2xl text-xs leading-relaxed text-cream-500/60">
          SlicePilot is an independent app. SlicePilot is not affiliated with, endorsed by, or sponsored by
          MakerWorld, Bambu Lab, Printables, Prusa Research, Thingiverse, Nexprint, Elegoo, or YE3D. All
          trademarks belong to their respective owners.
        </p>
        <p className="text-xs text-cream-500/40">© {new Date().getFullYear()} Yemyat Thu</p>
      </div>
    </footer>
  )
}

export default function App() {
  return (
    <>
      <Nav />
      <main>
        <Hero />
        <Features />
        <HowItWorks />
        <Screenshots />
        <CTA />
      </main>
      <Footer />
    </>
  )
}
