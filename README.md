# Crow Communication Mod

Un système de courrier RP par corbeau pour Minecraft Forge 1.20.1.

## Fonctionnement

La commande `/corbeau <pseudo>` invoque un oiseau messager qui descend vers toi. Une interface de rédaction s'ouvre — tu écris ton objet et ton message, puis le corbeau s'envole livrer ta lettre. Le destinataire voit l'oiseau arriver et peut choisir de garder ou de détruire la lettre.

### Commandes

| Commande | Description |
|---|---|
| `/corbeau <pseudo>` | Envoie une lettre à un joueur |
| `/corbeau-groupe <pseudo1> <pseudo2> ...` | Envoie à plusieurs joueurs (max 8) |

### Mécanique

- **Coût** : 1 papier par destinataire (ignoré en mode créatif)
- **Cooldown** : 2 minutes entre deux envois
- **Délai de livraison** : 10 secondes + 1 minute par 1000 blocs de distance
- **Météo** : 50% de chances que la pluie double le délai de livraison
- **Distance max** : 1500 blocs (au-delà, la lettre est perdue)
- **Timeout** : si le destinataire ne répond pas en 15 secondes, le corbeau rapporte la lettre à l'expéditeur
- **Interception** : le corbeau de livraison peut être abattu à l'arc par un joueur tiers — la lettre tombe au sol

### Garder une lettre

Cliquer `[ Garder la lettre ]` dans le chat ajoute un item papier à l'inventaire avec le contenu de la lettre dans son infobulle.

### Corbeaux multiples (`/corbeau-groupe`)

Un envoi vers plusieurs joueurs fait s'envoler un éventail de corbeaux simultanément. Chaque destinataire reçoit un corbeau individuel.

## Dépendances

- **Forge** 1.20.1-47.4.10+
- **MCEF** 2.1.6+ (Minecraft Chromium Embedded Framework) — requis côté client pour l'interface de rédaction

### Oiseaux compatibles (optionnels)

Le mod utilise le premier oiseau trouvé parmi les mods installés :

1. **Naturalist** — Moineau, Pinson, Rouge-gorge, Geai bleu
2. **Alex's Mobs** — Corbeau
3. *Fallback* — Poulet vanilla

## Installation

1. Placer `crowcommunication-1.0.0.jar` dans le dossier `mods/`
2. Placer `mcef-forge-2.1.6-1.20.1.jar` dans le dossier `mods/`
3. Lancer Minecraft Forge 1.20.1

> **Note :** Au premier démarrage, MCEF télécharge Chromium automatiquement. L'interface de rédaction ne sera disponible qu'après ce téléchargement.

## Build

**Prérequis :** JDK 17

```bash
JAVA_HOME="/path/to/jdk-17" ./gradlew build
```

Le JAR est généré dans `build/libs/`.

## Auteur

**Akirabane** — [GitHub](https://github.com/Akirabane)

## Licence

MIT
