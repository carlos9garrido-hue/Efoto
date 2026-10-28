// Prueba de paridad: el shader de app/src/main/assets/shaders se ejecuta en WebGL2 (Chromium
// headless, mismo GLSL ES 3.00 que Android) y se compara con el motor C++ (Developer::pixel).
//
// Uso: node parity.js <dump_cpu> <directorio de trabajo>
// Requiere: playwright (con Chromium) y python3.
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const [dumpCpu, work] = process.argv.slice(2);
if (!dumpCpu || !work) { console.error('uso: node parity.js <dump_cpu> <workdir>'); process.exit(2); }
fs.mkdirSync(work, { recursive: true });

const W = 128, H = 128;
const SHADERS = path.resolve(__dirname, '../../app/src/main/assets/shaders');
const vert = fs.readFileSync(path.join(SHADERS, 'develop.vert'), 'utf8');
const frag = fs.readFileSync(path.join(SHADERS, 'develop.frag'), 'utf8');

const inFile = path.join(work, 'in.rgb16');
execFileSync('python3', [path.join(__dirname, 'gen_image.py'), inFile]);

// Orden de los 39 parámetros (igual que Params en develop.h)
const names = ['exposure', 'contrast', 'highlights', 'shadows', 'whites', 'blacks', 'temperature', 'tint', 'vibrance', 'saturation'];
for (let i = 0; i < 5; i++) names.push(`curve${i}`);
const bands = ['rojo', 'naranja', 'amarillo', 'verde', 'aqua', 'azul', 'purpura', 'magenta'];
for (const b of bands) for (const k of ['tono', 'sat', 'lum']) names.push(`${b}-${k}`);
const N = names.length;  // 39

const amp = (i) => (i === 0 ? 1.5 : i >= 10 && i < 15 ? 0.2 : 0.7);
const cases = [{ name: 'neutro', p: new Array(N).fill(0) }];
names.forEach((n, i) => {
  for (const sign of [1, -1]) {
    const p = new Array(N).fill(0);
    p[i] = sign * amp(i);
    cases.push({ name: `${n} ${sign > 0 ? '+' : '-'}`, p });
  }
});
const maxAll = names.map((_, i) => (i === 0 ? 2 : i >= 10 && i < 15 ? 0.25 : 1));
cases.push({ name: 'todo al máximo', p: maxAll });
cases.push({ name: 'todo al mínimo', p: maxAll.map((v) => -v) });
// Combinaciones pseudoaleatorias reproducibles
let seed = 42;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296) * 2 - 1;
for (let k = 0; k < 12; k++) cases.push({ name: `aleatorio ${k + 1}`, p: names.map((_, i) => rnd() * maxAll[i]) });

(async () => {
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.setContent(`<canvas id="c" width="${W}" height="${H}"></canvas>`);

  await page.evaluate(({ vert, frag, W, H }) => {
    const gl = document.getElementById('c').getContext('webgl2', { antialias: false, alpha: false, preserveDrawingBuffer: true });
    if (!gl) throw new Error('WebGL2 no disponible');
    const sh = (type, src) => {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, vert));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, frag));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
    gl.useProgram(prog);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.uniform1i(gl.getUniformLocation(prog, 'uTex'), 0);
    window.__gl = { gl, prog, W, H };
  }, { vert, frag, W, H });

  let worst = 0, failed = 0;
  console.log(`${'caso'.padEnd(18)} ${'máx'.padStart(4)} ${'media'.padStart(7)} ${'>1'.padStart(6)}`);
  for (const c of cases) {
    const rgb8 = path.join(work, 'cpu.rgb8'), half = path.join(work, 'in.half');
    execFileSync(dumpCpu, [inFile, W, H, rgb8, half, ...c.p].map(String));
    const cpu = fs.readFileSync(rgb8);
    const halfB64 = fs.readFileSync(half).toString('base64');

    const gpu = await page.evaluate(({ halfB64, p }) => {
      const { gl, prog, W, H } = window.__gl;
      const bin = Uint8Array.from(atob(halfB64), (ch) => ch.charCodeAt(0));
      const data = new Uint16Array(bin.buffer);
      // Mismos bits half-float que sube la app (internalformat RGBA16F, tipo HALF_FLOAT).
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, W, H, 0, gl.RGBA, gl.HALF_FLOAT, data);
      gl.uniform1fv(gl.getUniformLocation(prog, 'uParams'), new Float32Array(p));
      gl.viewport(0, 0, W, H);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Uint8Array(W * H * 4);
      gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      // readPixels es de abajo a arriba: se voltea y se descarta alfa.
      const out = new Uint8Array(W * H * 3);
      for (let y = 0; y < H; y++)
        for (let x = 0; x < W; x++)
          for (let k = 0; k < 3; k++) out[(y * W + x) * 3 + k] = px[((H - 1 - y) * W + x) * 4 + k];
      return Array.from(out);
    }, { halfB64, p: c.p });

    let max = 0, sum = 0, over = 0;
    for (let i = 0; i < cpu.length; i++) {
      const d = Math.abs(cpu[i] - gpu[i]);
      if (d > max) max = d;
      if (d > 1) over++;
      sum += d;
    }
    worst = Math.max(worst, max);
    // Tolerancia: ±1 nivel de 8 bits (redondeo, half-float y pow/exp de la GPU).
    const ok = max <= 1;
    if (!ok) failed++;
    console.log(`${c.name.padEnd(18)} ${String(max).padStart(4)} ${(sum / cpu.length).toFixed(3).padStart(7)} ${String(over).padStart(6)}  ${ok ? 'ok' : 'FALLA'}`);
  }
  await browser.close();
  console.log(`\nDiferencia máxima: ${worst} niveles de 8 bits; ${failed} casos fuera de tolerancia`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
