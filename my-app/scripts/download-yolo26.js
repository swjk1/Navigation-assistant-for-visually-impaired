/**
 * Download YOLO26n INT8 TFLite into the Expo Android module assets folder.
 * Source: Ultralytics yolo-flutter-app release assets.
 */
const fs = require('fs');
const path = require('path');
const https = require('https');

const OUT_DIR = path.join(
  __dirname,
  '..',
  'modules',
  'indoor-perception',
  'android',
  'src',
  'main',
  'assets'
);
const OUT_FILE = path.join(OUT_DIR, 'yolo26n_int8.tflite');
const URL =
  process.env.YOLO26_URL ||
  'https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.2.0/yolo26n_int8.tflite';

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(dest);
    https
      .get(url, (res) => {
        // `statusCode` is optional on IncomingMessage (absent only on a destroyed socket);
        // 0 makes both branches below fall through to the error path rather than crashing.
        const status = res.statusCode ?? 0;
        if (status >= 300 && status < 400 && res.headers.location) {
          file.close();
          fs.unlinkSync(dest);
          return download(res.headers.location, dest).then(resolve).catch(reject);
        }
        if (status !== 200) {
          file.close();
          fs.unlinkSync(dest);
          reject(new Error(`HTTP ${res.statusCode} for ${url}`));
          return;
        }
        res.pipe(file);
        file.on('finish', () => file.close(() => resolve(dest)));
      })
      .on('error', (err) => {
        try {
          fs.unlinkSync(dest);
        } catch (_) {}
        reject(err);
      });
  });
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  if (fs.existsSync(OUT_FILE) && fs.statSync(OUT_FILE).size > 1_000_000) {
    console.log(`Already present: ${OUT_FILE} (${fs.statSync(OUT_FILE).size} bytes)`);
    return;
  }
  console.log(`Downloading YOLO26n → ${OUT_FILE}`);
  console.log(`URL: ${URL}`);
  await download(URL, OUT_FILE);
  console.log(`Done: ${fs.statSync(OUT_FILE).size} bytes`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
