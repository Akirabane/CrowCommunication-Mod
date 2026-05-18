window.pmod = {
  send(action, ...args) { console.log("PMOD::" + [action, ...args].join("|")); },
  sendMessage(target, subject, body, forgeName) {
    // Quatre champs séparés par :: — préserve les | dans le corps
    console.log("PMOD::sendMsg::" + target + "::" + subject + "::" + body + "::" + (forgeName || ""));
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

  forgeCheck.addEventListener("change", () => {
    forgeField.classList.toggle("hidden", !forgeCheck.checked);
    if (forgeCheck.checked) setTimeout(() => forgeName.focus(), 80);
  });

  function dispatchLetter(forged) {
    pmod.sendMessage("", subjectEl.value.trim(), bodyEl.value.trim(), forged);
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
      // Lance le QTE — si réussi → envoi avec le pseudo, sinon → envoi normal (sous vrai nom)
      runQTE(target, (success) => {
        dispatchLetter(success ? target : "");
      });
      return;
    }
    dispatchLetter("");
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
  const zoneWidthsPct  = [14, 9, 5.5];
  // Vitesse en ms par cycle aller-retour : se réduit (= plus rapide)
  const cyclePeriodMs  = [1400, 1100, 850];
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
    round++;
    feedback.textContent = round >= TOTAL_ROUNDS ? "Trois traits parfaits…" : "Bien joué — continue.";
    feedback.classList.add("good");
    cancelAnimationFrame(rafId);
    if (round >= TOTAL_ROUNDS) return finish(true);
    setTimeout(() => {
      feedback.classList.remove("good");
      startRound();
    }, 350);
  }

  function fail() {
    if (finished) return;
    cancelAnimationFrame(rafId);
    feedback.textContent = "Ta plume a tremblé.";
    feedback.classList.add("bad");
    setTimeout(() => finish(false), 600);
  }

  function abort() {
    if (finished) return;
    cancelAnimationFrame(rafId);
    finish(false);
  }

  function finish(success) {
    finished = true;
    overlay.classList.add("hidden");
    document.removeEventListener("keydown", onKey);
    hitBtn.removeEventListener("click", hit);
    abortBtn.removeEventListener("click", abort);
    onDone(success);
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
