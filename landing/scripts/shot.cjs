const { chromium } = require("playwright-core")

;(async () => {
  const browser = await chromium.launch({
    executablePath: "/usr/bin/chromium",
    args: ["--no-sandbox", "--use-angle=swiftshader", "--use-gl=angle", "--enable-unsafe-swiftshader"],
  })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
  await page.goto("http://localhost:4173/", { waitUntil: "networkidle" })
  await page.waitForTimeout(6000) // let the print animation run into mid-cycle

  const metrics = await page.evaluate(() => {
    const ids = ["top", "features", "how", "screenshots"]
    const out = { page: document.body.scrollHeight, sections: {} }
    for (const id of ids) {
      const el = document.getElementById(id)
      if (el) out.sections[id] = { top: Math.round(el.offsetTop), h: Math.round(el.offsetHeight) }
    }
    return out
  })
  console.log(JSON.stringify(metrics, null, 2))

  await page.screenshot({ path: "/tmp/opencode/pw_hero.png" })
  for (const [id, shot] of [
    ["features", "pw_features"],
    ["how", "pw_how"],
    ["screenshots", "pw_shots"],
  ]) {
    await page.evaluate((id) => {
      document.documentElement.style.scrollBehavior = "auto"
      document.getElementById(id).scrollIntoView()
    }, id)
    await page.waitForTimeout(2500) // allow whileInView reveals
    await page.screenshot({ path: `/tmp/opencode/${shot}.png` })
  }
  // CTA + footer
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await page.waitForTimeout(1500)
  await page.screenshot({ path: "/tmp/opencode/pw_footer.png" })

  // mobile
  const m = await browser.newPage({ viewport: { width: 390, height: 844 } })
  await m.goto("http://localhost:4173/", { waitUntil: "networkidle" })
  await m.waitForTimeout(6000)
  await m.screenshot({ path: "/tmp/opencode/pw_mobile.png" })

  await browser.close()
  console.log("done")
})()
