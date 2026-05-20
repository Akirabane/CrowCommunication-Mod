let _seq = 0;
window.pmod = {
  send(action, ...args) { console.log("PMOD::" + [action, ...args, ++_seq].join("|")); },
  sendMessage(target, subject, body, forgeName, qteRounds) {
    // Cinq champs séparés par :: — préserve les | dans le corps. qteRounds = 0..3.
    console.log("PMOD::sendMsg::" + target + "::" + subject + "::" + body + "::" + (forgeName || "") + "::" + (qteRounds | 0));
  },
  setState() {}
};

function $(id) { return document.getElementById(id); }

function getRecipientsFromUrl() {
  try {
    const params = new URLSearchParams(window.location.search);
    return params.get("to") || "";
  } catch (e) { return ""; }
}

function getForgeCooldownFromUrl() {
  try {
    const params = new URLSearchParams(window.location.search);
    const v = parseInt(params.get("forgeCd") || "0", 10);
    return isFinite(v) && v > 0 ? v : 0;
  } catch (e) { return 0; }
}

function formatCooldown(sec) {
  if (sec >= 60) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return s > 0 ? `${m} min ${s} s` : `${m} min`;
  }
  return `${sec} s`;
}

function init() {
  const subjectEl = $("subject");
  const bodyEl    = $("body");
  const counter   = $("count");
  const errorEl   = $("error");
  const sendBtn   = $("sendBtn");
  const cancelBtn = $("cancelBtn");
  const dismissBtn= $("dismissBtn");
  const recipEl   = $("recipients");
  const forgeCheck= $("forgeCheck");
  const forgeField= $("forgeField");
  const forgeName = $("forgeName");

  const recipients = getRecipientsFromUrl();
  recipEl.textContent = recipients || "(destinataire défini par la commande)";

  function showError(msg) {
    errorEl.textContent = msg;
    errorEl.classList.remove("hidden");
    setTimeout(() => errorEl.classList.add("hidden"), 3500);
  }

  // Cooldown forge transmis par le serveur — si actif, on désactive le toggle et on l'explique
  const forgeCooldownSec = getForgeCooldownFromUrl();
  if (forgeCooldownSec > 0) {
    forgeCheck.disabled = true;
    const hint = document.querySelector(".forge-hint");
    if (hint) {
      hint.textContent = `Tes mains tremblent encore — réessaie dans ${formatCooldown(forgeCooldownSec)}.`;
      hint.style.color = "#8a1c1c";
      hint.style.opacity = "1";
    }
    const block = document.querySelector(".forge-block");
    if (block) block.style.opacity = "0.6";
  }

  forgeCheck.addEventListener("change", () => {
    forgeField.classList.toggle("hidden", !forgeCheck.checked);
    if (forgeCheck.checked) setTimeout(() => forgeName.focus(), 80);
  });

  function dispatchLetter(forged, qteRounds) {
    pmod.sendMessage("", subjectEl.value.trim(), bodyEl.value.trim(), forged, qteRounds || 0);
    sendBtn.disabled = true;
    sendBtn.textContent = "Envoyé...";
  }

  function trySend() {
    const s = subjectEl.value.trim();
    const b = bodyEl.value.trim();
    if (!s) return showError("Précise un objet à ta lettre.");
    if (!b) return showError("Écris au moins quelques mots dans le message.");

    if (forgeCheck.checked) {
      const target = forgeName.value.trim();
      if (!target) return showError("Indique le pseudo à usurper.");
      // Lance le QTE — on transmet toujours le pseudo cible + le nombre de manches réussies (0..3),
      // le serveur décide du nom final (vrai/anagramme/cible) selon le score.
      runQTE(target, (roundsPassed) => {
        dispatchLetter(target, roundsPassed);
      });
      return;
    }
    dispatchLetter("", 0);
  }

  function cancel() { pmod.send("close"); }

  sendBtn.addEventListener("click",   trySend);
  cancelBtn.addEventListener("click", cancel);
  dismissBtn.addEventListener("click", cancel);

  bodyEl.addEventListener("input", () => counter.textContent = bodyEl.value.length);

  subjectEl.addEventListener("keydown", e => {
    if (e.key === "Enter") { e.preventDefault(); trySend(); }
  });

  setTimeout(() => subjectEl.focus(), 150);
  setTimeout(() => subjectEl.focus(), 600);
}

/* ═══════════════ Audio QTE (WebAudio) ═══════════════ */
let _audioCtx = null;
function audio() {
  if (_audioCtx) return _audioCtx;
  try { _audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { _audioCtx = null; }
  return _audioCtx;
}
function blip(freq, dur, type, vol) {
  const ctx = audio();
  if (!ctx) return;
  const t0 = ctx.currentTime;
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = type || "sine";
  osc.frequency.setValueAtTime(freq, t0);
  gain.gain.setValueAtTime(vol || 0.18, t0);
  gain.gain.exponentialRampToValueAtTime(0.001, t0 + dur);
  osc.connect(gain).connect(ctx.destination);
  osc.start(t0);
  osc.stop(t0 + dur);
}
function sndHit()  { blip(880, 0.12, "triangle", 0.22); setTimeout(() => blip(1320, 0.08, "triangle", 0.16), 30); }
function sndMiss() { blip(180, 0.22, "sawtooth", 0.20); }
function sndQTEWin()  { blip(660, 0.10, "triangle", 0.20); setTimeout(() => blip(990, 0.12, "triangle", 0.20), 90); setTimeout(() => blip(1320, 0.18, "triangle", 0.22), 200); }
function sndQTEFail() { blip(220, 0.28, "sawtooth", 0.22); setTimeout(() => blip(140, 0.30, "sawtooth", 0.20), 120); }

function flashZone(zone, kind) {
  const original = zone.style.boxShadow;
  zone.style.transition = "box-shadow .25s ease-out, background .25s";
  if (kind === "good") {
    zone.style.boxShadow = "0 0 28px 4px rgba(255,220,80,0.95)";
  } else {
    zone.style.boxShadow = "0 0 24px 4px rgba(160,30,30,0.85)";
  }
  setTimeout(() => { zone.style.boxShadow = original; }, 280);
}

/* ═══════════════ QTE : barre de timing, 3 manches ═══════════════ */
function runQTE(target, onDone) {
  const overlay  = $("qteOverlay");
  const bar      = $("qteBar");
  const zone     = $("qteZone");
  const marker   = $("qteMarker");
  const feedback = $("qteFeedback");
  const roundEl  = $("qteRound");
  const hitBtn   = $("qteHit");
  const abortBtn = $("qteAbort");

  // Largeur en % de la barre : la zone-cible rétrécit à chaque manche
  const zoneWidthsPct  = [14, 10, 7.5];
  // Vitesse en ms par cycle aller-retour : se réduit (= plus rapide)
  const cyclePeriodMs  = [1400, 1150, 950];
  const TOTAL_ROUNDS = 3;

  let round = 0;
  let rafId = null;
  let cycleStart = 0;
  let finished = false;

  overlay.classList.remove("hidden");
  feedback.textContent = "Concentre-toi…";
  feedback.classList.remove("good", "bad");

  function placeZone() {
    const w = zoneWidthsPct[round];
    // Position aléatoire de la zone-cible, en évitant les bords (5%..95%-w)
    const left = 5 + Math.random() * (90 - w);
    zone.style.left  = left + "%";
    zone.style.width = w + "%";
  }

  function startRound() {
    roundEl.textContent = `Manche ${round + 1} / ${TOTAL_ROUNDS}`;
    placeZone();
    cycleStart = performance.now();
    feedback.textContent = "Frappe sur le doré…";
    feedback.classList.remove("good", "bad");
    loop();
  }

  function loop() {
    if (finished) return;
    const now = performance.now();
    const phase = ((now - cycleStart) % cyclePeriodMs[round]) / cyclePeriodMs[round];
    // Triangle wave 0→1→0
    const pos = phase < 0.5 ? phase * 2 : 2 - phase * 2;
    marker.style.left = (pos * 100) + "%";
    rafId = requestAnimationFrame(loop);
  }

  function markerCenterPct() {
    return parseFloat(marker.style.left) || 0;
  }
  function zoneRangePct() {
    const l = parseFloat(zone.style.left);
    const w = parseFloat(zone.style.width);
    return [l, l + w];
  }

  function hit() {
    if (finished) return;
    const m = markerCenterPct();
    const [a, z] = zoneRangePct();
    const inside = m >= a && m <= z;
    if (!inside) return fail();
    sndHit();
    flashZone(zone, "good");
    round++;
    feedback.textContent = round >= TOTAL_ROUNDS ? "Trois traits parfaits…" : "Bien joué — continue.";
    feedback.classList.add("good");
    cancelAnimationFrame(rafId);
    if (round >= TOTAL_ROUNDS) { sndQTEWin(); return finish(round); }
    setTimeout(() => {
      feedback.classList.remove("good");
      startRound();
    }, 350);
  }

  function fail() {
    if (finished) return;
    sndMiss();
    flashZone(zone, "bad");
    cancelAnimationFrame(rafId);
    feedback.textContent = "Ta plume a tremblé.";
    feedback.classList.add("bad");
    sndQTEFail();
    // À l'échec, on transmet le nombre de manches DÉJÀ réussies avant cette manche ratée.
    setTimeout(() => finish(round), 600);
  }

  function abort() {
    if (finished) return;
    cancelAnimationFrame(rafId);
    finish(round);
  }

  function finish(roundsPassed) {
    finished = true;
    overlay.classList.add("hidden");
    document.removeEventListener("keydown", onKey);
    hitBtn.removeEventListener("click", hit);
    abortBtn.removeEventListener("click", abort);
    onDone(roundsPassed | 0);
  }

  function onKey(e) {
    if (e.key === " " || e.code === "Space") { e.preventDefault(); hit(); }
    else if (e.key === "Escape") { abort(); }
  }

  document.addEventListener("keydown", onKey);
  hitBtn.addEventListener("click", hit);
  abortBtn.addEventListener("click", abort);

  startRound();
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}

pmod.send("ready");
