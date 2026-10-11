// Draws every raster icon from the two SVGs in web/public, so the web app,
// the Play Store listing and the Android app all show the same mark.
// Run it with android/icons.sh (it needs a browser, so it runs in Docker).
import { readFileSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';

const root = process.argv[2];
const square = readFileSync(`${root}/web/public/icon.svg`, 'utf8'); // full bleed: platforms mask it
const rounded = readFileSync(`${root}/web/public/favicon.svg`, 'utf8'); // already shaped
const res = `${root}/android/app/src/main/res`;
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

// [file, svg, canvas px, share of the canvas the icon fills]
const jobs = [
  [`${root}/web/public/icon-192.png`, square, 192, 1],
  [`${root}/web/public/icon-512.png`, square, 512, 1],
  [`${root}/web/public/favicon-32.png`, rounded, 32, 1],
  [`${root}/android/store_icon.png`, square, 512, 1],
];
for (const [d, x] of Object.entries(densities)) {
  // Launchers before Android 8 show this as is; later ones use the adaptive icon.
  jobs.push([`${res}/mipmap-${d}/ic_launcher.png`, rounded, 48 * x, 0.92]);
}

const browser = await chromium.launch();
const page = await browser.newPage();
for (const [file, svg, size, share] of jobs) {
  const px = Math.round(size * share);
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(`<style>html,body{margin:0;background:transparent}body{display:grid;place-items:center;width:${size}px;height:${size}px}</style>
<img width="${px}" height="${px}" src="data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}">`);
  await page.locator('img').evaluate((img) => img.decode());
  writeFileSync(file, await page.screenshot({ omitBackground: true }));
  console.log(`${file.slice(root.length + 1)} ${size}px`);
}
await browser.close();
