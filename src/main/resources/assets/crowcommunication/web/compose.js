window.pmod = {
  send(action, ...args) { console.log("PMOD::" + [action, ...args].join("|")); },
  sendMessage(target, subject, body) {
    console.log("PMOD::sendMsg::" + target + "::" + subject + "::" + body);
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

  const recipients = getRecipientsFromUrl();
  recipEl.textContent = recipients || "(destinataire défini par la commande)";

  function showError(msg) {
    errorEl.textContent = msg;
    errorEl.classList.remove("hidden");
    setTimeout(() => errorEl.classList.add("hidden"), 3500);
  }

  function trySend() {
    const s = subjectEl.value.trim();
    const b = bodyEl.value.trim();
    if (!s) return showError("Précise un objet à ta lettre.");
    if (!b) return showError("Écris au moins quelques mots dans le message.");
    // target ignoré côté serveur — la commande a renseigné les destinataires
    pmod.sendMessage("", s, b);
    sendBtn.disabled = true;
    sendBtn.textContent = "Envoyé...";
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

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}

pmod.send("ready");
