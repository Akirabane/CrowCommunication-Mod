let _seq = 0;
window.pmod = {
  send(action, ...args) { console.log("PMOD::" + [action, ...args, ++_seq].join("|")); },
  setState() {}
};

function $(id) { return document.getElementById(id); }

function getParam(name) {
  try {
    return new URLSearchParams(window.location.search).get(name) || "";
  } catch (e) { return ""; }
}

function init() {
  const sender  = getParam("from");
  const subject = getParam("subject");
  const body    = getParam("body");

  $("fromLine").textContent   = "De : " + (sender || "inconnu");
  $("subjectView").textContent = subject || "(sans objet)";
  $("bodyView").textContent   = body    || "";

  const forgeCheck = $("forgeCheck");
  const forgeField = $("forgeField");
  const forgeName  = $("forgeName");
  const resealBtn  = $("resealBtn");
  const errorEl    = $("error");

  forgeCheck.addEventListener("change", () => {
    forgeField.classList.toggle("hidden", !forgeCheck.checked);
    resealBtn.classList.toggle("hidden",  !forgeCheck.checked);
    if (forgeCheck.checked) setTimeout(() => forgeName.focus(), 80);
  });

  function showError(msg) {
    errorEl.textContent = msg;
    errorEl.classList.remove("hidden");
    setTimeout(() => errorEl.classList.add("hidden"), 3500);
  }

  resealBtn.addEventListener("click", () => {
    const target = forgeName.value.trim();
    if (!target) return showError("Indique le pseudo à usurper.");
    runQTE(target, (success) => {
      if (success) {
        pmod.send("reseal", target);
      } else {
        pmod.send("close");
      }
    });
  });

  $("cancelBtn").addEventListener("click", () => pmod.send("close"));
  $("dismissBtn").addEventListener("click", () => pmod.send("close"));
}

/* ═══════════════ Audio QTE ═══════════════ */
let _audioCtx = null;
function audio() {
  if (_audioCtx) return _audioCtx;
  try { _audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { _audioCtx = null; }
  return _audioCtx;
}
function blip(freq, dur, type, vol) {
  const ctx = audio(); if (!ctx) return;
  const t0 = ctx.currentTime;
  const osc = ctx.createOscillator(); const gain = ctx.createGain();
  osc.type = type || "sine"; osc.frequency.setValueAtTime(freq, t0);
  gain.gain.setValueAtTime(vol || 0.18, t0);
  gain.gain.exponentialRampToValueAtTime(0.001, t0 + dur);
  osc.connect(gain).connect(ctx.destination); osc.start(t0); osc.stop(t0 + dur);
}
function sndHit()     { blip(880, 0.12, "triangle", 0.22); setTimeout(() => blip(1320, 0.08, "triangle", 0.16), 30); }
function sndMiss()    { blip(180, 0.22, "sawtooth", 0.20); }
function sndQTEWin()  { blip(660, 0.10, "triangle", 0.20); setTimeout(() => blip(990, 0.12, "triangle", 0.20), 90); setTimeout(() => blip(1320, 0.18, "triangle", 0.22), 200); }
function sndQTEFail() { blip(220, 0.28, "sawtooth", 0.22); setTimeout(() => blip(140, 0.30, "sawtooth", 0.20), 120); }

function flashZone(zone, kind) {
  zone.style.transition = "box-shadow .25s ease-out";
  zone.style.boxShadow  = kind === "good"
    ? "0 0 28px 4px rgba(255,220,80,0.95)"
    : "0 0 24px 4px rgba(160,30,30,0.85)";
  setTimeout(() => { zone.style.boxShadow = ""; }, 280);
}

/* ═══════════════ QTE 3 manches ═══════════════ */
function runQTE(target, onDone) {
  const overlay  = $("qteOverlay"), bar = $("qteBar"), zone = $("qteZone");
  const marker   = $("qteMarker"), feedback = $("qteFeedback"), roundEl = $("qteRound");
  const hitBtn   = $("qteHit"), abortBtn = $("qteAbort");
  const zoneWidthsPct = [14, 9, 5.5], cyclePeriodMs = [1400, 1100, 850];
  const TOTAL_ROUNDS = 3;
  let round = 0, rafId = null, cycleStart = 0, finished = false;

  overlay.classList.remove("hidden");
  feedback.textContent = "Concentre-toi…"; feedback.classList.remove("good", "bad");

  function placeZone() {
    const w = zoneWidthsPct[round];
    zone.style.left = (5 + Math.random() * (90 - w)) + "%";
    zone.style.width = w + "%";
  }
  function startRound() {
    roundEl.textContent = `Manche ${round + 1} / ${TOTAL_ROUNDS}`;
    placeZone(); cycleStart = performance.now();
    feedback.textContent = "Frappe sur le doré…"; feedback.classList.remove("good", "bad");
    loop();
  }
  function loop() {
    if (finished) return;
    const phase = ((performance.now() - cycleStart) % cyclePeriodMs[round]) / cyclePeriodMs[round];
    const pos   = phase < 0.5 ? phase * 2 : 2 - phase * 2;
    marker.style.left = (pos * 100) + "%";
    rafId = requestAnimationFrame(loop);
  }
  function hit() {
    if (finished) return;
    const m = parseFloat(marker.style.left) || 0;
    const l = parseFloat(zone.style.left), w = parseFloat(zone.style.width);
    if (m < l || m > l + w) { sndMiss(); flashZone(zone, "bad"); cancelAnimationFrame(rafId); feedback.textContent = "Ta plume a tremblé."; feedback.classList.add("bad"); sndQTEFail(); return setTimeout(() => finish(false), 600); }
    sndHit(); flashZone(zone, "good"); round++;
    feedback.textContent = round >= TOTAL_ROUNDS ? "Trois traits parfaits…" : "Bien joué — continue.";
    feedback.classList.add("good"); cancelAnimationFrame(rafId);
    if (round >= TOTAL_ROUNDS) { sndQTEWin(); return finish(true); }
    setTimeout(() => { feedback.classList.remove("good"); startRound(); }, 350);
  }
  function abort() { if (finished) return; cancelAnimationFrame(rafId); finish(false); }
  function finish(success) {
    finished = true; overlay.classList.add("hidden");
    document.removeEventListener("keydown", onKey);
    hitBtn.removeEventListener("click", hit);
    abortBtn.removeEventListener("click", abort);
    onDone(success);
  }
  function onKey(e) {
    if (e.key === " " || e.code === "Space") { e.preventDefault(); hit(); }
    else if (e.key === "Escape") abort();
  }
  document.addEventListener("keydown", onKey);
  hitBtn.addEventListener("click", hit);
  abortBtn.addEventListener("click", abort);
  startRound();
}

if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
else init();

pmod.send("ready");
