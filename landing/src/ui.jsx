import React from "react"

export const PLAY_URL = "https://play.google.com/store/apps/details?id=world.yemyat.slicepilot"

export function PlayButton({ className = "" }) {
  return (
    <a
      href={PLAY_URL}
      target="_blank"
      rel="noreferrer"
      className={`group inline-flex items-center gap-3 rounded-xl bg-cream-50 px-5 py-3 text-ink-950 font-semibold transition-all hover:scale-[1.03] hover:shadow-[0_0_40px_rgba(245,158,46,0.35)] ${className}`}
    >
      <svg viewBox="0 0 24 24" className="h-6 w-6" fill="currentColor" aria-hidden>
        <path d="M3.6 1.8 13.7 12 3.6 22.2c-.4-.2-.6-.6-.6-1.1V2.9c0-.5.2-.9.6-1.1Zm11.4 11.5 2.9 3-9.6 5.5 6.7-8.5Zm0-2.6L8.3 2.2l9.6 5.5-2.9 3Zm4.3-.9 2.6 1.5c.9.5.9 1.4 0 1.9l-2.6 1.5-3.2-2.9 3.2-2Z" />
      </svg>
      <span className="text-left leading-tight">
        <span className="block text-[10px] font-medium uppercase tracking-widest opacity-60">Get it on</span>
        <span className="block font-display text-base">Google Play</span>
      </span>
    </a>
  )
}

export function PhoneMockup({ src, alt, className = "" }) {
  return (
    <div
      className={`relative aspect-[9/19.5] w-full overflow-hidden rounded-[2rem] border border-ink-600 bg-black shadow-[0_25px_80px_-20px_rgba(0,0,0,0.9)] ${className}`}
    >
      <div className="absolute left-1/2 top-2 z-10 h-5 w-24 -translate-x-1/2 rounded-full bg-black" />
      <img src={src} alt={alt} loading="lazy" className="h-full w-full object-cover object-top" />
    </div>
  )
}

export function SectionHeading({ eyebrow, title, sub }) {
  return (
    <div className="mx-auto mb-14 max-w-2xl text-center">
      <p className="mb-3 font-mono text-xs uppercase tracking-[0.3em] text-ember-400">{eyebrow}</p>
      <h2 className="font-display text-3xl font-bold text-cream-50 sm:text-4xl md:text-5xl">{title}</h2>
      {sub && <p className="mt-4 text-cream-500 text-base sm:text-lg">{sub}</p>}
    </div>
  )
}
