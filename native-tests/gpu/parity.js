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

const W = 128, H = 96;  // fuente (la imagen final puede tener otro tamaño)
const SHADERS = path.resolve(__dirname, '../../app/src/main/assets/shaders');
const vert = fs.readFileSync(path.join(SHADERS, 'develop.vert'), 'utf8');
const frag = fs.readFileSync(path.join(SHADERS, 'develop.frag'), 'utf8');

// PARITY_IMAGE=<archivo.rgb16 de 128x96> usa otra imagen de entrada (por ejemplo, una suave).
const inFile = process.env.PARITY_IMAGE || path.join(work, 'in.rgb16');
if (!process.env.PARITY_IMAGE) execFileSync('python3', [path.join(__dirname, 'gen_image.py'), inFile]);

// Orden de los 61 parámetros (igual que Params en develop.h)
const names = ['exposure', 'contrast', 'highlights', 'shadows', 'whites', 'blacks', 'temperature', 'tint', 'vibrance', 'saturation'];
for (let i = 0; i < 5; i++) names.push(`curve${i}`);
const bands = ['rojo', 'naranja', 'amarillo', 'verde', 'aqua', 'azul', 'purpura', 'magenta'];
for (const b of bands) for (const k of ['tono', 'sat', 'lum']) names.push(`${b}-${k}`);
names.push('angle', 'rotate90', 'cropL', 'cropT', 'cropR', 'cropB');  // geometría (39-44)
names.push('clarity', 'texture', 'dehaze', 'sharpen', 'nrLuma', 'nrColor');  // detalle (45-50)
names.push('vigAmount', 'vigMid', 'grain');  // efectos (51-53)
names.push('shHue', 'shSat', 'midHue', 'midSat', 'hiHue', 'hiSat', 'balance');  // gradación (54-60)
const N = names.length;  // 61
const HUE = new Set([54, 56, 58]);
const POSITIVE = new Set([48, 49, 50, 53, 55, 57, 59]);  // solo tienen efecto con valores > 0

const amp = (i) => (i === 0 ? 1.5 : i >= 10 && i < 15 ? 0.2 : i === 39 ? 20 : i === 40 ? 1 : i >= 41 && i <= 44 ? 0.3 : HUE.has(i) ? 200 : 0.7);
const cases = [{ name: 'neutro', p: new Array(N).fill(0) }];
names.forEach((n, i) => {
  for (const sign of [1, -1]) {
    const p = new Array(N).fill(0);
    p[i] = sign * amp(i);
    cases.push({ name: `${n} ${sign > 0 ? '+' : '-'}`, p });
  }
});
const maxAll = names.map((_, i) => (i === 0 ? 2 : i >= 10 && i < 15 ? 0.25 : i === 39 ? 30 : i === 40 ? 3 : i >= 41 && i <= 44 ? 0.4 : HUE.has(i) ? 300 : 1));
cases.push({ name: 'todo al máximo', p: maxAll });
cases.push({ name: 'todo al mínimo', p: maxAll.map((v, i) => (POSITIVE.has(i) || HUE.has(i) ? v : -v)) });
// Geometría con nombre propio (giros, enderezado con recorte, combinada con tono y color)
const geo = (o) => { const p = new Array(N).fill(0); Object.entries(o).forEach(([k, v]) => { p[names.indexOf(k)] = v; }); return p; };
cases.push({ name: 'giro 90', p: geo({ rotate90: 1 }) });
cases.push({ name: 'giro 180', p: geo({ rotate90: 2 }) });
cases.push({ name: 'giro 270', p: geo({ rotate90: 3 }) });
cases.push({ name: 'enderezar +12 recorte', p: geo({ angle: 12, cropL: 0.12, cropR: 0.12, cropT: 0.15, cropB: 0.15 }) });
cases.push({ name: 'enderezar -25 recorte', p: geo({ angle: -25, cropL: 0.2, cropR: 0.1, cropT: 0.1, cropB: 0.25 }) });
cases.push({ name: 'giro 90 + enderezar', p: geo({ rotate90: 1, angle: 7, cropL: 0.1, cropT: 0.1, cropR: 0.1, cropB: 0.1 }) });
// Efectos con nombre propio, combinados entre sí y con la geometría
cases.push({ name: 'detalle completo', p: geo({ clarity: 0.6, texture: 0.5, dehaze: 0.5, sharpen: 0.7, nrLuma: 0.5, nrColor: 0.6 }) });
cases.push({ name: 'detalle negativo', p: geo({ clarity: -0.7, texture: -0.6, dehaze: -0.8 }) });
cases.push({ name: 'gradación 3 zonas', p: geo({ shHue: 220, shSat: 0.7, midHue: 40, midSat: 0.4, hiHue: 50, hiSat: 0.8, balance: 0.3 }) });
cases.push({ name: 'viñeta + grano + giro', p: geo({ vigAmount: -0.8, vigMid: 0.3, grain: 0.7, rotate90: 1, angle: 5, cropL: 0.1, cropR: 0.1 }) });
cases.push({ name: 'viñeta clara', p: geo({ vigAmount: 0.8, vigMid: -0.5, exposure: -0.5 }) });
cases.push({ name: 'efectos + enderezar', p: geo({ clarity: 0.5, dehaze: 0.4, sharpen: 0.5, vigAmount: -0.5, grain: 0.4, shSat: 0.5, shHue: 200, angle: 12, cropL: 0.12, cropR: 0.12, cropT: 0.15, cropB: 0.15 }) });
cases.push({ name: 'giro 270 + tono/HSL', p: geo({ rotate90: 3, angle: -15, cropB: 0.2, exposure: 0.5, contrast: 0.3, 'rojo-sat': -0.6, 'azul-tono': 0.5, curve2: 0.15 }) });
// Combinaciones pseudoaleatorias reproducibles
let seed = 42;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296) * 2 - 1;
for (let k = 0; k < 30; k++) cases.push({ name: `aleatorio ${k + 1}`, p: names.map((_, i) => (POSITIVE.has(i) || HUE.has(i) ? (rnd() * 0.5 + 0.5) * maxAll[i] : rnd() * maxAll[i])) });

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
    for (const [unit, name] of [[1, 'uAuxA'], [2, 'uAuxB']]) {
      gl.activeTexture(gl.TEXTURE0 + unit);
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.uniform1i(gl.getUniformLocation(prog, name), unit);
    }
    gl.activeTexture(gl.TEXTURE0);
    window.__gl = { gl, prog, W, H };
  }, { vert, frag, W, H });

  let worst = 0, failed = 0;
  console.log(`${'caso'.padEnd(22)} ${'máx'.padStart(4)} ${'media'.padStart(7)} ${'>1'.padStart(6)}`);
  for (const c of cases) {
    const rgb8 = path.join(work, 'cpu.rgb8'), half = path.join(work, 'in.half');
    const auxa = path.join(work, 'aux.a'), auxb = path.join(work, 'aux.b');
    const dims = execFileSync(dumpCpu, [inFile, W, H, rgb8, half, auxa, auxb, ...c.p].map(String)).toString().trim().split(/\s+/).map(Number);
    const [OW, OH] = dims;
    const cpu = fs.readFileSync(rgb8);
    const halfB64 = fs.readFileSync(half).toString('base64');
    const auxaB64 = fs.readFileSync(auxa).toString('base64'), auxbB64 = fs.readFileSync(auxb).toString('base64');

    const gpu = await page.evaluate(({ halfB64, auxaB64, auxbB64, p, OW, OH }) => {
      const { gl, prog, W, H } = window.__gl;
      const canvas = document.getElementById('c');
      canvas.width = OW; canvas.height = OH;  // lienzo = tamaño de la imagen final
      const bin = Uint8Array.from(atob(halfB64), (ch) => ch.charCodeAt(0));
      const data = new Uint16Array(bin.buffer);
      // Mismos bits half-float que sube la app (internalformat RGBA16F, tipo HALF_FLOAT).
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, W, H, 0, gl.RGBA, gl.HALF_FLOAT, data);
      const u16 = (b64) => new Uint16Array(Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0)).buffer);
      gl.activeTexture(gl.TEXTURE1);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, W, H, 0, gl.RGBA, gl.HALF_FLOAT, u16(auxaB64));
      gl.activeTexture(gl.TEXTURE2);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG16F, W, H, 0, gl.RG, gl.HALF_FLOAT, u16(auxbB64));
      gl.activeTexture(gl.TEXTURE0);
      gl.uniform4f(gl.getUniformLocation(prog, 'uOut'), OW, OH, OW, OH);
      gl.uniform1fv(gl.getUniformLocation(prog, 'uParams'), new Float32Array(p));
      // Igual que la app: cos/sin del enderezado se calculan fuera del shader.
      const rad = (p[39] * Math.PI) / 180;
      gl.uniform2f(gl.getUniformLocation(prog, 'uRot'), Math.cos(rad), Math.sin(rad));
      gl.viewport(0, 0, OW, OH);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const px = new Uint8Array(OW * OH * 4);
      gl.readPixels(0, 0, OW, OH, gl.RGBA, gl.UNSIGNED_BYTE, px);
      // readPixels es de abajo a arriba: se voltea y se descarta alfa.
      const out = new Uint8Array(OW * OH * 3);
      for (let y = 0; y < OH; y++)
        for (let x = 0; x < OW; x++)
          for (let k = 0; k < 3; k++) out[(y * OW + x) * 3 + k] = px[((OH - 1 - y) * OW + x) * 4 + k];
      return Array.from(out);
    }, { halfB64, auxaB64, auxbB64, p: c.p, OW, OH });

    if (cpu.length !== gpu.length) { console.log(`${c.name.padEnd(22)} tamaño distinto: CPU ${cpu.length} vs GPU ${gpu.length}`); failed++; continue; }
    let max = 0, sum = 0, over = 0;
    for (let i = 0; i < cpu.length; i++) {
      const d = Math.abs(cpu[i] - gpu[i]);
      if (d > max) max = d;
      if (d > 1) over++;
      sum += d;
    }
    if (process.env.DEBUG_CASE === c.name) {  // muestra los píxeles con más diferencia
      const rows = [];
      for (let i = 0; i < cpu.length; i += 3) {
        const d = Math.max(Math.abs(cpu[i] - gpu[i]), Math.abs(cpu[i + 1] - gpu[i + 1]), Math.abs(cpu[i + 2] - gpu[i + 2]));
        rows.push({ d, x: (i / 3) % OW, y: Math.floor(i / 3 / OW), cpu: [cpu[i], cpu[i + 1], cpu[i + 2]], gpu: [gpu[i], gpu[i + 1], gpu[i + 2]] });
      }
      rows.sort((a, b) => b.d - a.d);
      console.log('peores píxeles:', JSON.stringify(rows.slice(0, 6)));
    }
    worst = Math.max(worst, max);
    // Tolerancia: ±1 nivel de 8 bits (redondeo, half-float y pow/exp de la GPU).
    const ok = max <= 1;
    if (!ok) failed++;
    console.log(`${c.name.padEnd(22)} ${String(max).padStart(4)} ${(sum / cpu.length).toFixed(3).padStart(7)} ${String(over).padStart(6)}  ${ok ? 'ok' : 'FALLA'}`);
  }
  await browser.close();
  console.log(`\nDiferencia máxima: ${worst} niveles de 8 bits; ${failed} casos fuera de tolerancia`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
