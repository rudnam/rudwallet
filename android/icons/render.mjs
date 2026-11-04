// Draws every raster icon from the two SVGs in web/public, so the web app,
// the Play Store listing and the Android app all show the same mark.
// Run it with android/icons.sh (it needs a browser, so it runs in Docker).
import { readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';

const root = process.argv[2];
const square = readFileSync(`${root}/web/public/icon.svg`, 'utf8'); // full bleed: platforms mask it
const rounded = readFileSync(`${root}/web/public/favicon.svg`, 'utf8'); // already shaped
const res = `${root}/android/app/src/main/res`;
// Android 8+'s adaptive icon: the wallet without its tile, on the 108dp canvas with the
// tile's 512 units in the middle 72dp (the part every launcher mask shows). The tile's
// colour is drawable/ic_launcher_gradient. A vector can't draw the leather's grain or
// soft shadows, so this layer is a bitmap.
const foreground = square
  .replace(/<rect id="tile"[^>]*\/>/, '')
  .replace('viewBox="0 0 512 512"', 'viewBox="-128 -128 768 768"');
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
  jobs.push([`${res}/mipmap-${d}/ic_launcher_foreground.png`, foreground, 108 * x, 1]);
}
jobs.push([`${root}/web/public/favicon-48.tmp.png`, rounded, 48, 1]);

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

// favicon.ico: one 48px PNG inside an ICO header (every browser reads PNG-in-ICO).
const png = readFileSync(`${root}/web/public/favicon-48.tmp.png`);
const ico = Buffer.alloc(22);
ico.writeUInt16LE(1, 2); // type: icon
ico.writeUInt16LE(1, 4); // one image
ico.writeUInt8(48, 6); // width
ico.writeUInt8(48, 7); // height
ico.writeUInt16LE(1, 10); // colour planes
ico.writeUInt16LE(32, 12); // bits per pixel
ico.writeUInt32LE(png.length, 14);
ico.writeUInt32LE(22, 18); // where the PNG starts
writeFileSync(`${root}/web/public/favicon.ico`, Buffer.concat([ico, png]));
unlinkSync(`${root}/web/public/favicon-48.tmp.png`);
console.log('web/public/favicon.ico 48px');
