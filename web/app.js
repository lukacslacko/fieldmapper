// Home-page logic: probe WebXR + Magnetometer support, expose the session
// ID, offer a Reset button. The AR page itself is in field-xr.{html,js}.

const SESSION_KEY = 'fieldmapper.sessionId';

const startLink     = document.getElementById('startLink');
const xrSupportNote = document.getElementById('xrSupportNote');
const sessionIdEl   = document.getElementById('sessionId');
const resetBtn      = document.getElementById('resetBtn');

// --- session bootstrap ----------------------------------------------------

async function ensureSession() {
  let id = localStorage.getItem(SESSION_KEY);
  if (!id) {
    try {
      const r = await fetch('/api/session', { method: 'POST' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const j = await r.json();
      id = j.id;
      localStorage.setItem(SESSION_KEY, id);
    } catch (e) {
      sessionIdEl.textContent = '(server unreachable)';
      sessionIdEl.classList.add('error');
      return null;
    }
  }
  sessionIdEl.textContent = id;
  startLink.href = 'field-xr.html?session=' + encodeURIComponent(id);
  return id;
}

// --- support probe --------------------------------------------------------

// Recommended Chrome flag to enable Magnetometer — confirmed on stable
// Chrome 147 / Android 10. The narrower `#enable-generic-sensor-extra-classes`
// is documented but missing from at least some shipping Android builds, so
// the experimental-features superset is the safer recommendation.
const FLAG_URL = 'chrome://flags/#enable-experimental-web-platform-features';

async function probeSupport() {
  const issues = [];
  const actions = [];   // [{ text, url }] — surfaced as tap-to-copy buttons
  const facts = [];

  // Secure context check first — the Magnetometer (and most sensors) are
  // gated on this, so an HTTP-over-LAN visit will silently hide the API.
  const secure = window.isSecureContext === true;
  facts.push(['secure context',  secure ? 'yes' : 'NO']);
  facts.push(['origin',          location.origin]);
  if (!secure) {
    issues.push('Page is not loaded in a secure context. The Magnetometer Sensor API and WebXR both require HTTPS (or http://localhost). Use --https on the server, or front it with cloudflared / ngrok / adb reverse.');
  }

  // WebXR
  facts.push(['navigator.xr',    navigator.xr ? 'present' : 'missing']);
  if (!navigator.xr) {
    issues.push('WebXR is not exposed (navigator.xr missing). Use Chrome for Android with ARCore installed.');
  } else {
    try {
      const ok = await navigator.xr.isSessionSupported('immersive-ar');
      facts.push(['immersive-ar', ok ? 'supported' : 'NOT supported']);
      if (!ok) issues.push('Immersive AR is not supported on this browser. On Android you need ARCore installed from the Play Store.');
    } catch (e) {
      facts.push(['immersive-ar', 'query failed: ' + (e.message || e.name)]);
      issues.push('Could not query immersive-ar support: ' + (e.message || e.name));
    }
  }

  // Magnetometer
  const hasMag        = typeof Magnetometer !== 'undefined';
  const hasGyro       = typeof Gyroscope !== 'undefined';
  const hasAccel      = typeof Accelerometer !== 'undefined';
  const hasGravity    = typeof GravitySensor !== 'undefined';
  facts.push(['Magnetometer global',  hasMag      ? 'present' : 'MISSING']);
  facts.push(['Gyroscope global',     hasGyro     ? 'present' : 'missing']);
  facts.push(['Accelerometer global', hasAccel    ? 'present' : 'missing']);
  facts.push(['GravitySensor global', hasGravity  ? 'present' : 'missing']);
  facts.push(['Sensor base class',    typeof Sensor !== 'undefined' ? 'present' : 'missing']);
  facts.push(['AbsoluteOrientationSensor', typeof AbsoluteOrientationSensor !== 'undefined' ? 'present' : 'missing']);
  if (!hasMag) {
    if (hasGyro || hasAccel) {
      // Other Generic Sensor classes are present, so the API isn't disabled
      // wholesale — Magnetometer specifically is gated. On Android Chrome
      // it sits behind a runtime feature; the most reliably-present way to
      // toggle it is via the umbrella experimental-features flag.
      issues.push(
        'The Magnetometer constructor is hidden but other Generic Sensor classes ' +
        '(Accelerometer / Gyroscope) are exposed. On Android Chrome you need to ' +
        'enable the experimental-web-platform-features flag, set it to Enabled, ' +
        'tap Relaunch, then reload this page.'
      );
      actions.push({ text: 'Open this Chrome flag and set to Enabled', url: FLAG_URL });
    } else if (!secure) {
      issues.push(
        'The Magnetometer Sensor API is not exposed because the page is not a secure context. ' +
        'Reach this page over HTTPS (or http://localhost via `adb reverse tcp:8080 tcp:8080`).'
      );
    } else {
      issues.push(
        'The Magnetometer Sensor API is not exposed. Likely causes, in order: ' +
        '(1) the experimental-web-platform-features flag is OFF — turn it on and relaunch Chrome; ' +
        '(2) the page is not a secure context (need HTTPS or localhost); ' +
        '(3) the device has no magnetometer (rare on phones).'
      );
      actions.push({ text: 'Open this Chrome flag and set to Enabled', url: FLAG_URL });
    }
  }

  // Permissions-Policy header check — `featurePolicy` is deprecated but
  // `document.permissionsPolicy` is still spotty; both report whether the
  // current document is allowed to use the feature.
  let policyAllowed = null;
  try {
    if (document.featurePolicy && document.featurePolicy.allowsFeature) {
      policyAllowed = document.featurePolicy.allowsFeature('magnetometer');
    } else if (document.permissionsPolicy && document.permissionsPolicy.allowsFeature) {
      policyAllowed = document.permissionsPolicy.allowsFeature('magnetometer');
    }
  } catch (e) { /* ignore */ }
  if (policyAllowed != null) {
    facts.push(['magnetometer permissions-policy', policyAllowed ? 'allowed' : 'BLOCKED']);
    if (!policyAllowed) {
      issues.push('Permissions-Policy is blocking the magnetometer for this document.');
    }
  }

  // Try a Permissions API query (separate from the constructor; some
  // Chromes expose the perm name even when the constructor is hidden).
  if (navigator.permissions && navigator.permissions.query) {
    try {
      const s = await navigator.permissions.query({ name: 'magnetometer' });
      facts.push(['permissions.query(magnetometer)', s.state]);
    } catch (e) {
      facts.push(['permissions.query(magnetometer)', 'query failed: ' + (e.message || e.name)]);
    }
  }

  // UA hint — Chrome version helps me triage when something regresses.
  facts.push(['userAgent', navigator.userAgent.slice(0, 110)]);

  // Live-test each Generic Sensor class we can construct. Differentiating
  // "Magnetometer fails but Accelerometer works" (→ no mag hardware) from
  // "all sensors fail" (→ Chrome / OS-wide block) is the key signal here.
  async function tryRead(label, ctor, opts) {
    if (typeof ctor === 'undefined') {
      facts.push([label + ' test', 'class not exposed']);
      return null;
    }
    let s;
    try { s = new ctor(opts); }
    catch (e) {
      const msg = 'construction threw ' + (e.name || '') + ': ' + (e.message || e);
      facts.push([label + ' test', msg]);
      return msg;
    }
    const result = await new Promise(resolve => {
      let done = false;
      const finish = (m) => { if (!done) { done = true; try { s.stop(); } catch(_){} resolve(m); } };
      s.addEventListener('reading', () => {
        const fmt = (v) => (typeof v === 'number' ? v.toFixed(2) : '?');
        let r;
        if ('x' in s && 'y' in s && 'z' in s) r = `${fmt(s.x)},${fmt(s.y)},${fmt(s.z)}`;
        else r = JSON.stringify(s);
        finish('reading=' + r);
      });
      s.addEventListener('error', e => {
        finish('error=' + ((e.error && e.error.name) || 'SensorError') + ': ' + ((e.error && e.error.message) || ''));
      });
      try { s.start(); }
      catch (e) { finish('start threw ' + (e.name || '') + ': ' + (e.message || e)); }
      setTimeout(() => finish('timeout (no reading after 4 s)'), 4000);
    });
    facts.push([label + ' test', result]);
    return result;
  }

  // Run sequentially so we don't have multiple sensors fighting for the
  // platform handle at the same instant.
  const accelResult  = await tryRead('Accelerometer',  typeof Accelerometer !== 'undefined' ? Accelerometer : undefined,  { frequency: 1 });
  const gyroResult   = await tryRead('Gyroscope',      typeof Gyroscope     !== 'undefined' ? Gyroscope     : undefined,  { frequency: 1 });
  const magResult    = await tryRead('Magnetometer',   typeof Magnetometer  !== 'undefined' ? Magnetometer  : undefined,  { frequency: 1, referenceFrame: 'device' });
  const magResult2   = await tryRead('Magnetometer (no opts)', typeof Magnetometer !== 'undefined' ? Magnetometer : undefined, {});
  const ucMagResult  = await tryRead('UncalibratedMagnetometer', typeof UncalibratedMagnetometer !== 'undefined' ? UncalibratedMagnetometer : undefined, { frequency: 1 });
  const orientResult = await tryRead('AbsoluteOrientationSensor', typeof AbsoluteOrientationSensor !== 'undefined' ? AbsoluteOrientationSensor : undefined, { frequency: 1 });

  // Synthesise a focused "what failed" summary issue.
  // UncalibratedMagnetometer is optional (not exposed on many builds), so
  // its absence shouldn't suppress the warning when the calibrated tests
  // both fail — only require both Magnetometer attempts to fail to declare
  // the magnetometer broken.
  const allMagFailed = isFail(magResult) && isFail(magResult2);
  const notReadable  = [magResult, magResult2, ucMagResult].some(r => typeof r === 'string' && r.includes('NotReadableError'));
  if (hasMag && allMagFailed) {
    if (isOk(accelResult) || isOk(gyroResult)) {
      if (notReadable) {
        // NotReadableError typically means the sensor handle couldn't be
        // opened. On Android the most common cause (confirmed in testing)
        // is that another foreground app is holding the magnetometer —
        // compass apps, AR apps, level apps, even some games. The "no
        // hardware" path is rarer than this, so lead with the easy fix.
        issues.push(
          'The Magnetometer constructor is exposed and permission is granted, ' +
          'but every attempt to start it fails with NotReadableError. ' +
          'The most common cause on Android is that another app is currently ' +
          'holding the magnetometer — close compass, AR, level, or navigation apps ' +
          '(swipe them out of the recents tray) and reload this page. ' +
          'If that does not help, the phone may have no usable magnetometer; ' +
          'verify with "Sensor Box" / "AndroSensor" from the Play Store.'
        );
      } else {
        issues.push(
          'Other sensors work but every magnetometer attempt fails. ' +
          'Likely causes: (1) another app is holding the magnetometer (close ' +
          'compass / AR / level apps and reload), (2) the phone has no usable ' +
          'magnetometer hardware. Verify with "Sensor Box" / "AndroSensor".'
        );
      }
    } else {
      issues.push(
        'No motion sensor would start. This is an OS-wide sensor block — check Android Settings > Privacy > Sensors and whether Battery Saver / Power Saving is on.'
      );
    }
  }
  function isOk(s)   { return typeof s === 'string' && s.startsWith('reading='); }
  function isFail(s) { return typeof s === 'string' && !s.startsWith('reading='); }

  renderSupportNote(issues, actions, facts);
  postDiagnosticsToServer(issues, facts);
}

// Mirror the diagnostic table to the server's /log endpoint so the user
// can copy-paste it from a terminal instead of squinting at the phone.
function postDiagnosticsToServer(issues, facts) {
  const w = Math.max(...facts.map(([k]) => k.length)) + 2;
  const pad = (s) => (s + ' '.repeat(w)).slice(0, w);
  const factLines = facts.map(([k, v]) => '  ' + pad(k) + String(v));
  const issueLines = issues.length === 0
    ? ['  (none)']
    : issues.map((s, i) => '  ' + (i + 1) + '. ' + s);
  const block = [
    '================ FIELDMAPPER PHONE DIAGNOSTICS ================',
    'time:    ' + new Date().toISOString(),
    'origin:  ' + location.origin,
    'href:    ' + location.href,
    '',
    'Issues:',
    ...issueLines,
    '',
    'Facts:',
    ...factLines,
    '===============================================================',
  ].join('\n');
  try {
    fetch('/log', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: block,
      keepalive: true,
    }).catch(() => {});
  } catch (e) { /* best effort */ }
}

function renderSupportNote(issues, actions, facts) {
  const factTable = facts
    .map(([k, v]) => '<div class="fact-row"><span class="fact-k">' + escapeHtml(k) + '</span><span class="fact-v">' + escapeHtml(String(v)) + '</span></div>')
    .join('');

  if (issues.length === 0) {
    xrSupportNote.classList.add('ok');
    xrSupportNote.innerHTML = '<b>Looks good — Android Chrome with ARCore detected.</b><div class="facts">' + factTable + '</div>';
    return;
  }

  // Tap-to-copy buttons for chrome:// URLs. Web pages can't navigate to
  // chrome://, so copy-to-clipboard + paste-into-omnibox is the best we
  // can do.
  const actionsHtml = (actions || []).length === 0 ? '' :
    '<div class="actions"><b>Suggested fixes:</b>' +
      actions.map((a, i) =>
        '<button class="copy-flag" type="button" data-url="' + escapeHtml(a.url) + '" data-index="' + i + '">' +
          '<span class="copy-text">' + escapeHtml(a.text) + '</span>' +
          '<code class="copy-url">' + escapeHtml(a.url) + '</code>' +
          '<span class="copy-hint">tap to copy</span>' +
        '</button>'
      ).join('') +
    '</div>';

  xrSupportNote.classList.add('warn');
  xrSupportNote.innerHTML =
    '<b>Detected ' + issues.length + ' issue' + (issues.length === 1 ? '' : 's') + ':</b><br>' +
    issues.map(s => '• ' + escapeHtml(s)).join('<br>') +
    actionsHtml +
    '<div class="facts">' + factTable + '</div>';

  // Delegated click handler for the copy buttons.
  xrSupportNote.querySelectorAll('.copy-flag').forEach(btn => {
    btn.addEventListener('click', async () => {
      const url = btn.dataset.url;
      const hint = btn.querySelector('.copy-hint');
      try {
        await navigator.clipboard.writeText(url);
        hint.textContent = 'copied! paste into omnibox';
        btn.classList.add('copied');
        setTimeout(() => {
          hint.textContent = 'tap to copy';
          btn.classList.remove('copied');
        }, 2500);
      } catch (e) {
        // Fallback: select the URL so the user can long-press copy.
        const r = document.createRange();
        r.selectNodeContents(btn.querySelector('.copy-url'));
        const sel = window.getSelection();
        sel.removeAllRanges(); sel.addRange(r);
        hint.textContent = 'long-press to copy';
      }
    });
  });

  startLink.classList.add('disabled');
  startLink.addEventListener('click', e => {
    if (!confirm('Start anyway? Detected issues:\n\n' + issues.join('\n'))) e.preventDefault();
  });
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[c]);
}

// --- reset ---------------------------------------------------------------

resetBtn.addEventListener('click', async () => {
  const id = localStorage.getItem(SESSION_KEY);
  if (!id) return;
  if (!confirm('Discard all collected samples for this session?')) return;
  resetBtn.disabled = true;
  resetBtn.textContent = 'Resetting…';
  try {
    await fetch('/api/session/' + encodeURIComponent(id) + '/reset', { method: 'POST' });
    resetBtn.textContent = 'Done';
    setTimeout(() => { resetBtn.disabled = false; resetBtn.textContent = 'Reset session'; }, 1200);
  } catch (e) {
    resetBtn.textContent = 'Reset failed';
    setTimeout(() => { resetBtn.disabled = false; resetBtn.textContent = 'Reset session'; }, 2000);
  }
});

(async () => {
  await ensureSession();
  await probeSupport();
})();
