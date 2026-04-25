// WebXR field-mapping client.
//
// Responsibilities (kept narrow on purpose — the server does the math):
//   1. Open an immersive-ar WebXR session for 6DOF tracking.
//   2. Read the phone magnetometer at ~30 Hz via the Generic Sensor API.
//   3. Pair each reading with the current viewer pose, rotate into world
//      frame, and buffer it for upload.
//   4. POST batches every ~1.5s; GET the reconstructed field every ~2s.
//   5. Render: glowing field-line tangle, optional sample dots, "go here"
//      markers + a directional arrow toward the closest one.
//
// Design notes:
//   * The Magnetometer Sensor reading is in DEVICE coordinates (X right,
//     Y up out the top of the phone, Z out of the screen toward the user).
//     The XR viewer space uses the same axes for a phone in immersive-ar
//     mode (viewer +X right, +Y up, -Z forward), so the viewer pose's
//     orientation quaternion rotates a device-frame vector into world.
//   * Field lines are rendered as colored polylines using LineSegments so
//     every line update can be a single buffer upload.
//   * Suggestion markers + arrow compute distance to the camera each
//     frame so they pulse / point reactively without server help.

import * as THREE from 'three';

// -------- DOM refs -------------------------------------------------------

const statusEl     = document.getElementById('status');
const statusText   = document.getElementById('statusText');
const backBtn      = document.getElementById('backBtn');
const sampleCountEl = document.getElementById('sampleCount');
const lineCountEl  = document.getElementById('lineCount');
const bMagEl       = document.getElementById('bMag');
const bAvgEl       = document.getElementById('bAvg');
const genCountEl   = document.getElementById('genCount');
const biasToggle   = document.getElementById('biasToggle');
const dotsToggle   = document.getElementById('dotsToggle');
const targetsToggle = document.getElementById('targetsToggle');
const resetBtn     = document.getElementById('resetSession');
const overlayRoot  = document.getElementById('xrOverlay');
const hintEl       = document.getElementById('hint');

const gate         = document.getElementById('startGate');
const startBtn     = document.getElementById('startBtn');
const gateBack     = document.getElementById('gateBack');
const gateError    = document.getElementById('gateError');
const gateErrTitle = document.getElementById('gateErrorTitle');
const gateErrBody  = document.getElementById('gateErrorBody');
const gateRetry    = document.getElementById('gateRetry');

// -------- Server logging helper -----------------------------------------

function dbg(msg, level) {
  const t = new Date().toISOString().slice(11, 23);
  const line = `[fxr] ${t} ${level ? '[' + level + '] ' : ''}${msg}`;
  try { console.log(line); } catch (e) {}
  try {
    fetch('/log', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: line,
      keepalive: true,
    }).catch(() => {});
  } catch (e) {}
}

// -------- Session bootstrap ---------------------------------------------

const params = new URLSearchParams(location.search);
const SESSION_KEY = 'fieldmapper.sessionId';

async function ensureSessionId() {
  let id = params.get('session') || localStorage.getItem(SESSION_KEY);
  if (id) {
    localStorage.setItem(SESSION_KEY, id);
    return id;
  }
  const r = await fetch('/api/session', { method: 'POST' });
  if (!r.ok) throw new Error('session create failed: HTTP ' + r.status);
  const j = await r.json();
  localStorage.setItem(SESSION_KEY, j.id);
  return j.id;
}

// -------- UI helpers ----------------------------------------------------

function setStatus(text, kind) {
  statusText.textContent = text;
  statusEl.classList.remove('ready', 'error', 'warn');
  if (kind) statusEl.classList.add(kind);
}
function showGateError(title, body) {
  gateErrTitle.textContent = title;
  gateErrBody.textContent = body;
  gateError.hidden = false;
  // Mirror to /log so the cause shows up in the server stderr — saves the
  // user from squinting at the phone or re-typing the on-screen text.
  dbg('GATE ERROR: ' + title + ' — ' + body, 'err');
}
function hideGate() { gate.style.display = 'none'; }
function showGate() { gate.style.display = ''; }

// -------- Quaternion utilities ------------------------------------------

// Rotate vector v=(x,y,z) by quaternion q=(x,y,z,w). Standard q*v*q^-1.
function quatRotate(qx, qy, qz, qw, vx, vy, vz, out) {
  const tx = 2 * (qy * vz - qz * vy);
  const ty = 2 * (qz * vx - qx * vz);
  const tz = 2 * (qx * vy - qy * vx);
  out[0] = vx + qw * tx + (qy * tz - qz * ty);
  out[1] = vy + qw * ty + (qz * tx - qx * tz);
  out[2] = vz + qw * tz + (qx * ty - qy * tx);
  return out;
}

// -------- Magnetometer wrapper ------------------------------------------

class MagSampler {
  constructor() {
    this.sensor = null;
    this.latest = null;       // { x, y, z, t }
    this.alpha = 0.25;        // EMA smoothing
    this.smoothed = null;
    this.error = null;
  }
  async start(frequency) {
    if (typeof Magnetometer === 'undefined') {
      this.error = 'Magnetometer API not exposed';
      throw new Error(this.error);
    }
    // The sensor needs a permission grant. On Android Chrome the user is
    // prompted on the first reading; if denied we surface a clear error.
    if (navigator.permissions && navigator.permissions.query) {
      try {
        const status = await navigator.permissions.query({ name: 'magnetometer' });
        if (status.state === 'denied') {
          this.error = 'magnetometer permission denied';
          throw new Error(this.error);
        }
      } catch (_) { /* not all browsers expose this name; fall through */ }
    }
    this.sensor = new Magnetometer({
      frequency: frequency || 30,
      referenceFrame: 'device',  // device-fixed axes; we rotate to world ourselves
    });
    this.sensor.addEventListener('reading', () => {
      const x = this.sensor.x, y = this.sensor.y, z = this.sensor.z;
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return;
      const t = performance.now() / 1000;
      if (this.smoothed === null) {
        this.smoothed = [x, y, z];
      } else {
        const a = this.alpha;
        this.smoothed[0] = (1 - a) * this.smoothed[0] + a * x;
        this.smoothed[1] = (1 - a) * this.smoothed[1] + a * y;
        this.smoothed[2] = (1 - a) * this.smoothed[2] + a * z;
      }
      this.latest = { x: this.smoothed[0], y: this.smoothed[1], z: this.smoothed[2], t };
    });
    this.sensor.addEventListener('error', e => {
      const name = (e.error && e.error.name) || 'SensorError';
      this.error = `${name}: ${(e.error && e.error.message) || ''}`.trim();
      dbg('mag sensor error: ' + this.error, 'err');
      // NotReadableError mid-session almost always means another foreground
      // app grabbed the magnetometer (compass / AR / level apps are the
      // usual suspects). Surface that on the in-AR hint so the user can
      // act without leaving immersive mode.
      if (name === 'NotReadableError' && hintEl) {
        hintEl.textContent =
          'Magnetometer disconnected — another app may have grabbed it. ' +
          'Close compass / AR / level apps and reload the page.';
      }
    });
    this.sensor.start();

    // Sensor.start() returns synchronously and any failure to open the
    // platform handle fires asynchronously via 'error'. Wait briefly for
    // either a first reading or that error so we can surface a real
    // failure to the caller (which renders a useful gate error) instead
    // of letting the user enter an XR session that will silently never
    // produce any data.
    await new Promise((resolve, reject) => {
      let done = false;
      const sensor = this.sensor;
      const onR = () => { if (done) return; done = true; clearTimeout(timer); resolve(); };
      const onE = (e) => {
        if (done) return; done = true; clearTimeout(timer);
        const name = (e.error && e.error.name) || 'SensorError';
        const msg  = (e.error && e.error.message) || '';
        try { sensor.stop(); } catch (_) {}
        this.sensor = null;
        reject(new Error(`${name}: ${msg}`.trim()));
      };
      const timer = setTimeout(() => {
        // Neither reading nor error within 2 s — uncommon but not fatal.
        // Just resolve and let the in-session 'error' handler take over
        // if something does go wrong later.
        if (done) return; done = true; resolve();
      }, 2000);
      sensor.addEventListener('reading', onR, { once: true });
      sensor.addEventListener('error',   onE, { once: true });
    });
  }
  stop() {
    if (this.sensor) {
      try { this.sensor.stop(); } catch (e) {}
      this.sensor = null;
    }
  }
}

// -------- Sample buffer + uploader --------------------------------------

class SampleUploader {
  constructor(sessionId) {
    this.sessionId = sessionId;
    this.buffer = [];
    this.lastFlushAt = 0;
    this.maxBatch = 80;
    this.flushIntervalMs = 1500;
    this.totalSent = 0;
    this.inFlight = false;
  }
  push(t, p, b) {
    this.buffer.push({
      t,
      p: [p[0], p[1], p[2]],
      b: [b[0], b[1], b[2]],
    });
    if (this.buffer.length >= this.maxBatch) this.flush();
  }
  maybeFlush(nowMs) {
    if (this.buffer.length === 0) return;
    if (this.inFlight) return;
    if (nowMs - this.lastFlushAt < this.flushIntervalMs) return;
    this.flush();
  }
  async flush() {
    if (this.inFlight || this.buffer.length === 0) return;
    const batch = this.buffer.splice(0, this.buffer.length);
    this.inFlight = true;
    this.lastFlushAt = performance.now();
    try {
      const r = await fetch(`/api/session/${encodeURIComponent(this.sessionId)}/samples`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samples: batch }),
        keepalive: true,
      });
      if (!r.ok) {
        dbg(`upload HTTP ${r.status}; re-queueing ${batch.length}`, 'warn');
        this.buffer.unshift(...batch);
      } else {
        const j = await r.json().catch(() => null);
        this.totalSent += batch.length;
        if (j) dbg(`uploaded ${batch.length}, server total ${j.totalSamples}`);
      }
    } catch (e) {
      dbg(`upload failed: ${e.message || e}; re-queueing ${batch.length}`, 'warn');
      this.buffer.unshift(...batch);
    } finally {
      this.inFlight = false;
    }
  }
}

// -------- Field representation poller -----------------------------------

class FieldPoller {
  constructor(sessionId, onUpdate) {
    this.sessionId = sessionId;
    this.onUpdate = onUpdate;
    this.intervalMs = 2000;
    this.lastGen = -1;
    this.timer = null;
    this.inFlight = false;
  }
  start() {
    this.timer = setInterval(() => this.tick(), this.intervalMs);
    this.tick();
  }
  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
  async tick() {
    if (this.inFlight) return;
    this.inFlight = true;
    try {
      const url = `/api/session/${encodeURIComponent(this.sessionId)}/field?since=${this.lastGen}`;
      const r = await fetch(url);
      if (!r.ok) return;
      const j = await r.json();
      if (j.unchanged) return;
      if (typeof j.generation === 'number') this.lastGen = j.generation;
      this.onUpdate(j);
    } catch (e) {
      // Silent; will retry on next tick.
    } finally {
      this.inFlight = false;
    }
  }
}

// -------- Three.js scene assembly ---------------------------------------

class FieldVisualization {
  constructor(scene) {
    this.scene = scene;

    this.linesGroup = new THREE.Group();
    this.linesGroup.name = 'field-lines';
    scene.add(this.linesGroup);

    // Sample dots — one Points geometry, rebuilt when samples grow.
    this.dotsGeom = new THREE.BufferGeometry();
    this.dotsGeom.setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(0), 3));
    const dotsMat = new THREE.PointsMaterial({
      color: 0x6cf,
      size: 0.025,
      sizeAttenuation: true,
      transparent: true,
      opacity: 0.55,
      depthWrite: false,
    });
    this.dots = new THREE.Points(this.dotsGeom, dotsMat);
    scene.add(this.dots);

    this.targetsGroup = new THREE.Group();
    this.targetsGroup.name = 'targets';
    scene.add(this.targetsGroup);

    // Arrow that points from in-front-of-the-camera toward the closest
    // suggestion target. Hidden when there are no suggestions.
    this.arrowGroup = new THREE.Group();
    const arrowMat = new THREE.MeshBasicMaterial({ color: 0xffd84d, depthTest: false, transparent: true, opacity: 0.95 });
    const arrowShaft = new THREE.Mesh(new THREE.CylinderGeometry(0.012, 0.012, 0.18, 12), arrowMat);
    arrowShaft.position.y = 0.09;
    const arrowHead = new THREE.Mesh(new THREE.ConeGeometry(0.035, 0.07, 14), arrowMat);
    arrowHead.position.y = 0.21;
    this.arrowGroup.add(arrowShaft);
    this.arrowGroup.add(arrowHead);
    this.arrowGroup.renderOrder = 999;
    this.arrowGroup.visible = false;
    scene.add(this.arrowGroup);

    this.suggestions = [];      // Vector3[]
    this.localSampleCount = 0;  // how many sample positions are in dotsGeom
    this.dotsBuffer = [];       // Float32Array source we keep growing
  }

  // The list of dots is owned client-side: every time we send a sample,
  // we also push it to the local list so dots show up immediately, even
  // before the server's recomputation finishes.
  appendDot(x, y, z) {
    this.dotsBuffer.push(x, y, z);
    this.localSampleCount++;
    // Every N additions, push a fresh attribute. Doing it on every sample
    // would thrash the GPU; doing it every ~30 samples is plenty smooth.
    if (this.localSampleCount % 30 === 0) {
      this._uploadDots();
    }
  }
  _uploadDots() {
    const arr = new Float32Array(this.dotsBuffer);
    this.dotsGeom.setAttribute('position', new THREE.BufferAttribute(arr, 3));
    this.dotsGeom.attributes.position.needsUpdate = true;
    this.dotsGeom.setDrawRange(0, this.localSampleCount);
    this.dotsGeom.computeBoundingSphere();
  }
  clearDots() {
    this.dotsBuffer = [];
    this.localSampleCount = 0;
    this.dotsGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(0), 3));
  }

  setLines(serverLines) {
    // Wipe existing line meshes
    while (this.linesGroup.children.length > 0) {
      const m = this.linesGroup.children.pop();
      if (m.geometry) m.geometry.dispose();
      if (m.material) m.material.dispose();
    }
    if (!serverLines || serverLines.length === 0) return;

    for (const ln of serverLines) {
      const flat = ln.points;       // [x0,y0,z0, x1,y1,z1, ...]
      const n = flat.length / 3;
      if (n < 2) continue;
      const positions = new Float32Array(flat);
      const colors = new Float32Array(n * 3);
      const strengths = ln.strengths || [];
      const confs = ln.confidences || [];
      for (let i = 0; i < n; i++) {
        // Color ramp: cool blue (low) → cyan → green → yellow → red (high).
        // Confidence dims the brightness.
        const s = clamp01(strengths[i] || 0.5);
        const c = clamp01(confs[i] || 0.5);
        const dim = 0.35 + 0.65 * c;
        const [r, g, b] = ramp(s);
        colors[i * 3 + 0] = r * dim;
        colors[i * 3 + 1] = g * dim;
        colors[i * 3 + 2] = b * dim;
      }
      const geom = new THREE.BufferGeometry();
      geom.setAttribute('position', new THREE.BufferAttribute(positions, 3));
      geom.setAttribute('color',    new THREE.BufferAttribute(colors,    3));
      const mat = new THREE.LineBasicMaterial({
        vertexColors: true,
        transparent: true,
        opacity: 0.92,
        depthWrite: false,
      });
      const line = new THREE.Line(geom, mat);
      line.frustumCulled = false;
      this.linesGroup.add(line);
    }
  }

  setSuggestions(serverSuggestions) {
    while (this.targetsGroup.children.length > 0) {
      const m = this.targetsGroup.children.pop();
      if (m.geometry) m.geometry.dispose();
      if (m.material) m.material.dispose();
    }
    this.suggestions = [];
    if (!serverSuggestions || serverSuggestions.length === 0) {
      this.arrowGroup.visible = false;
      return;
    }
    for (let i = 0; i < serverSuggestions.length; i++) {
      const t = serverSuggestions[i];
      this.suggestions.push(new THREE.Vector3(t[0], t[1], t[2]));
      const isClosest = i === 0;
      const mat = new THREE.MeshBasicMaterial({
        color: isClosest ? 0xffd84d : 0x88a2c8,
        transparent: true,
        opacity: isClosest ? 0.85 : 0.55,
        depthWrite: false,
      });
      const ring = new THREE.Mesh(
        new THREE.RingGeometry(isClosest ? 0.16 : 0.10, isClosest ? 0.20 : 0.13, 24),
        mat
      );
      ring.rotation.x = -Math.PI / 2;
      ring.position.set(t[0], t[1], t[2]);
      ring.userData.pulse = isClosest;
      ring.renderOrder = 50;
      this.targetsGroup.add(ring);
    }
  }

  setVisibility({ dots, targets, arrow }) {
    if (typeof dots === 'boolean')    this.dots.visible = dots;
    if (typeof targets === 'boolean') this.targetsGroup.visible = targets;
    if (typeof arrow === 'boolean')   this.arrowGroup.visible = arrow;
  }

  // Per-frame: animate target pulses, position arrow toward closest target.
  tick(camera) {
    const tNow = performance.now() / 1000;
    for (const m of this.targetsGroup.children) {
      if (m.userData && m.userData.pulse) {
        const s = 1 + 0.18 * Math.sin(tNow * 4);
        m.scale.set(s, s, s);
      }
    }
    if (this.suggestions.length === 0 || !this.targetsGroup.visible) {
      this.arrowGroup.visible = false;
      return;
    }
    // Closest target = first one (server already sorted by distance, but
    // the camera moves between updates, so re-sort cheaply here too).
    const cp = camera.getWorldPosition(new THREE.Vector3());
    let best = this.suggestions[0];
    let bestD = best.distanceToSquared(cp);
    for (let i = 1; i < this.suggestions.length; i++) {
      const d = this.suggestions[i].distanceToSquared(cp);
      if (d < bestD) { best = this.suggestions[i]; bestD = d; }
    }
    // Park the arrow ~40 cm in front of the camera, oriented toward `best`.
    const fwd = new THREE.Vector3(0, 0, -1).applyQuaternion(camera.quaternion).normalize();
    const anchor = cp.clone().addScaledVector(fwd, 0.4);
    this.arrowGroup.position.copy(anchor);
    // Orient: shaft +Y → points from anchor toward best.
    const dir = best.clone().sub(anchor);
    if (dir.lengthSq() < 1e-6) {
      this.arrowGroup.visible = false;
      return;
    }
    dir.normalize();
    const up = new THREE.Vector3(0, 1, 0);
    const q = new THREE.Quaternion().setFromUnitVectors(up, dir);
    this.arrowGroup.quaternion.copy(q);
    this.arrowGroup.visible = true;
  }
}

function clamp01(v) { return Math.max(0, Math.min(1, v)); }
function ramp(t) {
  // Five-stop cool→hot ramp with linear interpolation.
  const stops = [
    [0.00, [0.10, 0.30, 0.95]],
    [0.25, [0.10, 0.85, 0.95]],
    [0.50, [0.20, 0.95, 0.30]],
    [0.75, [0.95, 0.85, 0.15]],
    [1.00, [0.95, 0.20, 0.20]],
  ];
  for (let i = 0; i < stops.length - 1; i++) {
    const [a, ca] = stops[i];
    const [b, cb] = stops[i + 1];
    if (t <= b) {
      const u = (t - a) / Math.max(1e-6, b - a);
      return [
        ca[0] + (cb[0] - ca[0]) * u,
        ca[1] + (cb[1] - ca[1]) * u,
        ca[2] + (cb[2] - ca[2]) * u,
      ];
    }
  }
  return stops[stops.length - 1][1];
}

// -------- Main session runner -------------------------------------------

async function run(sessionId) {
  const mag = new MagSampler();
  try {
    await mag.start(30);
  } catch (e) {
    const msg = String(e.message || e);
    if (msg.includes('NotReadableError')) {
      showGateError(
        'Magnetometer in use',
        'Chrome could not open the magnetometer (NotReadableError). The most ' +
        'common cause on Android is that another foreground app is holding the ' +
        'sensor — compass, AR, level, or navigation apps. Swipe them out of ' +
        'recents, then tap Retry. (See the home page for a more detailed sensor ' +
        'diagnostic.)'
      );
    } else {
      showGateError('Magnetometer unavailable',
        msg + '. Field Mapper needs raw magnetic-field readings.');
    }
    return;
  }

  let session;
  try {
    session = await navigator.xr.requestSession('immersive-ar', {
      requiredFeatures: ['local-floor'],
      optionalFeatures: ['dom-overlay', 'hit-test'],
      domOverlay: { root: overlayRoot },
    });
  } catch (e) {
    showGateError('Could not start XR',
      'The browser refused to start an immersive AR session: ' + (e.message || e.name));
    mag.stop();
    return;
  }

  // WebGL2 context tied to the XR session so the camera feed composites
  // beneath our scene.
  const canvas = document.createElement('canvas');
  canvas.id = 'xrCanvas';
  canvas.style.cssText = 'position:fixed; inset:0; width:100%; height:100%; z-index:0;';
  document.body.appendChild(canvas);
  const gl = canvas.getContext('webgl2', { alpha: true, antialias: true, xrCompatible: true });
  if (!gl) {
    session.end();
    mag.stop();
    showGateError('WebGL2 unavailable', 'This phone did not give us a WebGL2 context.');
    return;
  }
  const renderer = new THREE.WebGLRenderer({
    canvas, context: gl, alpha: true, antialias: true,
  });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.xr.enabled = true;
  await renderer.xr.setSession(session);

  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 0.7));

  // Placeholder; three.js swaps in the XR camera each frame.
  const camera = new THREE.PerspectiveCamera(70, 1, 0.05, 200);

  const viz = new FieldVisualization(scene);
  const uploader = new SampleUploader(sessionId);
  const poller = new FieldPoller(sessionId, j => onFieldUpdate(j, viz));

  // --- sample collection cadence ---
  // Drop a new sample if we've moved >= MIN_DIST since the last one OR
  // MAX_GAP has elapsed (so a stationary user still gets a baseline).
  const MIN_DIST = 0.04; // m
  const MAX_GAP_MS = 350;
  let lastSampleAt = 0;
  let lastSamplePos = null;

  const tmpWorld = [0, 0, 0];

  const refSpace = await session.requestReferenceSpace('local-floor');

  let live = true;
  session.addEventListener('end', () => {
    live = false;
    poller.stop();
    mag.stop();
    uploader.flush();
    renderer.setAnimationLoop(null);
    try { renderer.dispose(); } catch (e) {}
    canvas.remove();
    setStatus('Session ended', 'error');
    showGate();
    startBtn.disabled = false;
    startBtn.textContent = 'Start AR';
  });

  poller.start();

  let frame = 0;
  let lastUiAt = 0;
  let lastUserPos = null;
  let pendingMagWarn = false;

  renderer.setAnimationLoop((tNow, xrFrame) => {
    if (!xrFrame || !live) return;
    frame++;

    const pose = xrFrame.getViewerPose(refSpace);
    const nowMs = performance.now();

    if (pose) {
      const t = pose.transform;
      const px = t.position.x, py = t.position.y, pz = t.position.z;
      const qx = t.orientation.x, qy = t.orientation.y, qz = t.orientation.z, qw = t.orientation.w;
      lastUserPos = [px, py, pz];

      const reading = mag.latest;
      if (reading) {
        // Cadence gate
        const moved = lastSamplePos
          ? Math.hypot(px - lastSamplePos[0], py - lastSamplePos[1], pz - lastSamplePos[2])
          : Infinity;
        const elapsed = nowMs - lastSampleAt;
        if (moved >= MIN_DIST || elapsed >= MAX_GAP_MS) {
          quatRotate(qx, qy, qz, qw, reading.x, reading.y, reading.z, tmpWorld);
          // Sanity check: the magnitude of Earth's field is ~25-65 µT.
          // Anything wildly outside that is probably a glitched reading.
          const mag2 = tmpWorld[0]*tmpWorld[0] + tmpWorld[1]*tmpWorld[1] + tmpWorld[2]*tmpWorld[2];
          if (mag2 > 0.0001 && mag2 < 1e6) {
            uploader.push(reading.t, [px, py, pz], tmpWorld);
            viz.appendDot(px, py, pz);
            lastSamplePos = [px, py, pz];
            lastSampleAt = nowMs;
          }
        }
      } else if (!pendingMagWarn && nowMs > 3000) {
        pendingMagWarn = true;
        dbg('no magnetometer reading after 3s', 'warn');
      }
    }

    uploader.maybeFlush(nowMs);

    // Per-frame visualization animation
    const xrCam = renderer.xr.getCamera();
    viz.tick(xrCam);

    if (nowMs - lastUiAt > 200) {
      lastUiAt = nowMs;
      const r = mag.latest;
      if (r) {
        const m = Math.hypot(r.x, r.y, r.z);
        bMagEl.textContent = `${m.toFixed(1)} µT`;
      }
    }

    renderer.render(scene, camera);
  });

  hideGate();
  setStatus('Mapping — walk slowly', 'ready');

  // --- toggles wiring ---
  biasToggle.addEventListener('change', async () => {
    const bias = biasToggle.checked ? 'earth' : 'none';
    try {
      await fetch(`/api/session/${encodeURIComponent(sessionId)}/config`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bias }),
      });
      // Force a poll soon so the user sees the new representation.
      poller.lastGen = -1;
      poller.tick();
    } catch (e) { dbg('bias toggle failed: ' + (e.message || e), 'warn'); }
  });
  dotsToggle.addEventListener('change', () => {
    viz.setVisibility({ dots: dotsToggle.checked });
  });
  targetsToggle.addEventListener('change', () => {
    viz.setVisibility({ targets: targetsToggle.checked });
  });
  resetBtn.addEventListener('click', async () => {
    if (!confirm('Discard all collected samples?')) return;
    try {
      await fetch(`/api/session/${encodeURIComponent(sessionId)}/reset`, { method: 'POST' });
      viz.clearDots();
      viz.setLines([]);
      viz.setSuggestions([]);
      poller.lastGen = -1;
      poller.tick();
      hintEl.textContent = 'Map cleared. Walk around to start a new map.';
    } catch (e) { dbg('reset failed: ' + (e.message || e), 'warn'); }
  });
}

// Apply a fresh /field response to the visualization + the HUD.
function onFieldUpdate(j, viz) {
  if (j.lines)       viz.setLines(j.lines);
  if (j.suggestions) viz.setSuggestions(j.suggestions);
  if (typeof j.sampleCount === 'number') sampleCountEl.textContent = j.sampleCount;
  if (typeof j.generation  === 'number') genCountEl.textContent    = j.generation;
  if (Array.isArray(j.lines)) lineCountEl.textContent = j.lines.length;
  if (j.averageField) {
    const a = j.averageField;
    const m = Math.hypot(a[0], a[1], a[2]);
    bAvgEl.textContent = `${m.toFixed(1)} µT`;
  }
  if (j.bias === 'earth') biasToggle.checked = true;
  if (j.bias === 'none')  biasToggle.checked = false;
  if (j.pending) {
    hintEl.textContent = `Collecting samples (${j.sampleCount || 0}) — server will start tracing once it has enough.`;
  } else if ((j.lines || []).length > 0) {
    hintEl.textContent = `${(j.lines || []).length} field lines from ${j.sampleCount} samples · compute ${j.computeMs} ms`;
  }
}

// -------- Entry / wire-up ------------------------------------------------

async function onStart() {
  startBtn.disabled = true;
  startBtn.textContent = 'Starting…';
  gateError.hidden = true;

  if (!navigator.xr) {
    showGateError('WebXR unavailable',
      'navigator.xr is missing. Use Chrome for Android with ARCore installed.');
    startBtn.disabled = false; startBtn.textContent = 'Start AR';
    return;
  }
  let supported = false;
  try { supported = await navigator.xr.isSessionSupported('immersive-ar'); }
  catch (e) { supported = false; }
  if (!supported) {
    showGateError('No immersive AR',
      'Chrome reports no immersive-ar support. On Android you need ARCore from the Play Store.');
    startBtn.disabled = false; startBtn.textContent = 'Start AR';
    return;
  }

  let sessionId;
  try { sessionId = await ensureSessionId(); }
  catch (e) {
    showGateError('Server unreachable',
      'Could not create a mapping session: ' + (e.message || e));
    startBtn.disabled = false; startBtn.textContent = 'Start AR';
    return;
  }
  dbg(`session ${sessionId}`);

  try { await run(sessionId); }
  catch (e) {
    dbg('run() failed: ' + (e.message || e), 'err');
    showGateError('Could not start', e.message || String(e));
    startBtn.disabled = false; startBtn.textContent = 'Start AR';
  }
}

backBtn.addEventListener('click',  () => history.back());
gateBack.addEventListener('click', () => history.back());
gateRetry.addEventListener('click', onStart);
startBtn.addEventListener('click',  onStart);

setStatus('Tap Start AR to begin');
dbg(`field-xr.html loaded: UA=${navigator.userAgent.slice(0, 120)}`);

// Early support probe so the gate can show a useful pre-fail message.
(async function probe() {
  if (!navigator.xr) {
    showGateError('WebXR not available',
      'navigator.xr is missing. Use Chrome for Android with ARCore.');
    startBtn.disabled = true;
    return;
  }
  try {
    const ok = await navigator.xr.isSessionSupported('immersive-ar');
    if (!ok) {
      showGateError('No immersive AR',
        'Chrome reports no immersive-ar support. Install ARCore from Play.');
      startBtn.disabled = true;
    }
  } catch (e) { /* onStart will surface the real error */ }
  if (typeof Magnetometer === 'undefined') {
    const hasOther = typeof Gyroscope !== 'undefined' || typeof Accelerometer !== 'undefined';
    const body = hasOther
      ? 'Other Generic Sensor classes are exposed, so the API works in general — Magnetometer specifically is gated. ' +
        'On the phone, open chrome://flags/#enable-experimental-web-platform-features, set it to Enabled, tap Relaunch, then reload this page. (See the home page for tap-to-copy and a more detailed diagnostic.)'
      : (window.isSecureContext === false
          ? 'The page is not a secure context, so the Magnetometer is hidden. Reach the server over HTTPS (or via adb reverse on http://localhost).'
          : 'This browser does not expose the Magnetometer Sensor API. On Android Chrome, enable chrome://flags/#enable-experimental-web-platform-features and relaunch. (See the home page for tap-to-copy and a more detailed diagnostic.)');
    showGateError('No Magnetometer API', body);
    startBtn.disabled = true;
  }
})();
