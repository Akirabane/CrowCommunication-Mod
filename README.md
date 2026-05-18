# Crow Communication Mod

Un système de courrier RP par corbeau pour Minecraft Forge 1.20.1, avec QTE d'usurpation de sceau, interception en plein vol et retour des lettres non-livrées.

---

## 1. Commandes

| Commande | Effet |
|---|---|
| `/corbeau <pseudo>` | Convoque 1 corbeau et ouvre l'UI MCEF pour rédiger une lettre |
| `/corbeau-groupe <p1> <p2> ...` | Convoque jusqu'à 8 corbeaux simultanément (une lettre par destinataire) |
| `/corbeau-choice <msgId> <keep\|destroy>` | Interne : déclenchée par les boutons cliquables dans le chat à réception |

Tab-complétion active sur les deux commandes (suggère les joueurs en ligne, filtre l'expéditeur, filtre les pseudos déjà tapés en mode groupe).

---

## 2. Cycle d'un corbeau normal (sans usurpation)

1. **Convocation** : tu tapes `/corbeau Bob`. Si tout est OK (cooldown 2 min, papier en inventaire, ciel visible au-dessus de toi), un corbeau apparaît à 40 blocs et descend en INCOMING vers toi (~15 s).
2. **Arrivée près de toi** : le corbeau se met en WAITING devant toi, l'UI de composition s'ouvre côté client.
3. **Rédaction** : tu écris objet + message. Tu peux cocher *Usurper un sceau* (voir §4).
4. **Envoi** : à *Envoyer par corbeau*, le serveur calcule un délai de livraison proportionnel à la distance, le corbeau passe en OUTGOING, monte à la couche **Y=100**, croisière tranquille, descente sur le destinataire — **durée de vol = délai exact** (45 s = 45 s de trajet).
5. **Livraison en place** : à l'arrivée, **le même corbeau** se pose près du destinataire (pas de second corbeau spawné). Le destinataire reçoit la lettre dans le chat avec deux boutons : `[ Garder la lettre ]` ou `[ La détruire ]`.
   - *Garder* : un item papier nommé `✉ <objet>` rejoint son inventaire, signé du sceau coloré de l'expéditeur (12 couleurs, hash stable du pseudo).
   - *Détruire* : la lettre disparaît, le corbeau s'envole et poof.
6. **Annulation depuis la rédaction (Échap)** : le corbeau repart sans lettre dans une direction de repli, poof brièvement.

---

## 3. Cas AFK / hors-ligne destinataire

- **Destinataire en ligne mais ne clique pas dans les 15 s** : le **même corbeau** fait demi-tour en place (pas de poof), recalcule la durée selon la distance retour, refait la croisière inverse, se pose chez l'expéditeur original avec en-tête `↩ Lettre retournée`. Si l'expéditeur ignore encore 15 s, la lettre tombe au sol à ses pieds (délai de ramassage 3 s).
- **Destinataire hors-ligne au moment où la PENDING fire** : le système skip pendant que le corbeau porteur est en vol. Si le destinataire se reconnecte avant l'arrivée, le corbeau livrera en place. Sinon, à l'arrivée le corbeau poof.
- **Expéditeur hors-ligne pendant le retour AFK** : la lettre tombe au sol devant le destinataire AFK (drop visible, ramassage différé de 3 s).

---

## 4. QTE d'usurpation de sceau

### Activation
Dans l'UI de composition, coche **Usurper un sceau** et indique le pseudo à imiter. Au clic *Envoyer*, l'overlay QTE s'ouvre.

### Mécanique du QTE
Une barre horizontale avec une **zone dorée** ; un marqueur rouge oscille en triangle (gauche↔droite). Frappe **Espace** (ou clique *Frapper*) quand le marqueur passe pile sur la zone d'or. **3 manches** consécutives à réussir :

| Manche | Largeur de la zone | Vitesse (cycle aller-retour) |
|---|---|---|
| 1 | 14 % de la barre | 1.4 s |
| 2 | 9 % | 1.1 s |
| 3 | 5.5 % | 0.85 s |

- Touche hors zone → **échec immédiat** du QTE, sortie de l'overlay.
- 3 manches réussies → **QTE validé**, le client envoie le packet avec le pseudo cible.
- **Échap** ou bouton *Renoncer* → abandon = QTE échoué.

### Résolution serveur
À la réception du packet avec un `forgeName` :

1. **Pseudo vide ou = ton propre pseudo** → pas de tentative, lettre normale.
2. **Cooldown 30 min actif** (`§c§oTes mains tremblent encore...`) → tentative bloquée, lettre envoyée sous ton vrai pseudo, **pas** de consommation du cooldown.
3. **Sinon** → cooldown 30 min posé, tirage aléatoire 30 % :
   - **30 % succès** : `§6§oTon trait de plume imite à la perfection le sceau de <X>...` → la lettre arrive **signée du faux pseudo**. Le destinataire voit le nom usurpé, la couleur de sceau du faux pseudo, et l'item conservé porte le faux nom.
   - **70 % échec** : `§8§oTa tentative d'imiter le sceau de <X> a raté — la lettre part sous ton vrai nom.` → la lettre part normalement, le destinataire ne voit aucune trace de tentative.

### Cooldown
- **2 min** sur l'envoi de corbeau (cooldown classique, partagé avec `/corbeau-groupe`).
- **30 min** spécifique aux tentatives d'usurpation, posé dès que le tirage 30 % est effectué (succès **ou** échec) — donc une tentative ratée coûte aussi le cooldown.

### Routage interne (important)
Le `b.sender` interne au serveur garde **toujours le vrai pseudo** pour le routage AFK return. Seul le `displaySender` (utilisé en affichage) est falsifié. Donc :
- Un corbeau usurpé qui revient en AFK retournera bien au vrai expéditeur.
- L'expéditeur reçoit la lettre retournée avec le header `↩ Lettre retournée` standard, mais l'item gardé porte le faux nom.

---

## 5. Interception en plein vol

Tout corbeau **DELIVERY** ou **SUMMON OUTGOING porteur** est interceptable (vulnérable) — pas les SUMMON INCOMING/WAITING (invulnérables, RP).

- **Si tué par un tiers** : la lettre tombe au sol à l'emplacement de la mort. Le tueur reçoit un message `§e§oTu as intercepté une lettre de <X> à <Y>.` Le destinataire (ou expéditeur si SUMMON) est notifié. Si une usurpation avait réussi, le tueur et la victime voient **le faux pseudo**.
- La livraison est annulée (`cancelDeliveries` purge PENDING + QUEUE).
- Joueurs tiers dans un rayon de **32 blocs** reçoivent un ping audio + particules quand un DELIVERY passe ("on entend les battements d'ailes").

---

## 6. Distance & contraintes

- **MAX_DELIVERY_DISTANCE = 1500 blocs** : au-delà, le corbeau signale `§c§oTrop loin — <X> est hors de portée des corbeaux.` et le destinataire ne reçoit rien.
- **Pas d'auto-envoi** : on ne peut jamais s'envoyer une lettre à soi-même (filtré en commande, vérifié à l'envoi).
- **Ciel visible obligatoire** au-dessus de toi pour convoquer un corbeau.
- **1 papier consommé par destinataire** (sauf en créatif).
- **MCEF doit être initialisé** côté client (le serveur attend le `PacketMCEFReady` avant d'autoriser un envoi).
- **Délai de livraison** : 10 secondes + 1 minute par 1000 blocs de distance.
- **Météo** : 50 % de chances que la pluie double le délai de livraison.

---

## 7. Spawn du corbeau

Le mod cherche dans cet ordre dans les mods compatibles :

1. **Naturalist** — `sparrow` → `finch` → `robin` → `bluejay`
2. *Fallback* — Poulet vanilla

---

## 8. Dépendances

- **Forge** 1.20.1-47.4.20+
- **MCEF** 2.1.6+ (Minecraft Chromium Embedded Framework) — requis côté client pour l'interface de rédaction

---

## 9. Installation

1. Placer `crowcommunication-<version>.jar` dans le dossier `mods/`
2. Placer `mcef-forge-2.1.6-1.20.1.jar` dans le dossier `mods/`
3. Lancer Minecraft Forge 1.20.1

> **Note :** Au premier démarrage, MCEF télécharge Chromium automatiquement. L'interface de rédaction ne sera disponible qu'après ce téléchargement.

---

## 10. Build

**Prérequis :** JDK 17

```bash
JAVA_HOME="/path/to/jdk-17" ./gradlew build
```

Le JAR est généré dans `build/libs/`. Toujours utiliser `gradlew build` (et non `gradlew jar`) — `reobfJar` est nécessaire pour le remapping SRG des noms officiels.

---

## Auteur

**Akirabane** — [GitHub](https://github.com/Akirabane)

## Licence

CC BY-NC-ND 4.0
