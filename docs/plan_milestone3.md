# Plan d'implémentation — Milestone 3 : Détection de rapprochements (Conjunctions)
**Date de rédaction** : 2026-03-21  

---

## Objectif

Détecter automatiquement les rapprochements critiques entre satellites (ou avec des débris) en calculant la distance 3D minimale entre deux objets sur une fenêtre temporelle donnée, puis exposer ces alertes via une API REST et les visualiser côté frontend.

---

## Choix d'architecture : BDD + Job + Notification IHM dès M3

On fait les choses bien du premier coup. Pas de détour par une API on-demand temporaire.

**Infrastructure déjà en place :**
- `spring-boot-starter-data-jpa`  déjà dans le `pom.xml`
- H2 en mémoire (profil `dev`)  déjà configuré
- PostgreSQL (profil `prod`)  déjà configuré
- `FetchCelesTrackTLEJob` déjà existant → patron à reproduire pour le job conjunctions

**Ce qu'on met en place en M3 :**

```
[BDD]
  Table conjunction_alert
    - id, name_sat1, name_sat2, tca, distance_km,
      lat1, lon1, alt1, lat2, lon2, alt2, detected_at, acknowledged

[Job]
  ConjunctionScanJob (@Scheduled toutes les heures)
    → itère sur les paires de satellites du catalogue
    → appelle ConjunctionService.analyze()
    → persiste les nouveaux ConjunctionAlert via ConjunctionAlertRepository

[API REST]
  GET  /api/v1/conjunction/alerts          → liste paginée des alertes persistées
  GET  /api/v1/conjunction/alerts/unread   → alertes non acquittées (badge IHM)
  PUT  /api/v1/conjunction/alerts/{id}/ack → marquer comme lue
  POST /api/v1/conjunction/analyze         → analyse on-demand (debug / tests)

[Notification IHM]
  Polling léger côté Angular (toutes les 30 s sur /alerts/unread)
  Badge de notification dans la navbar + panneau latéral des alertes
  Clic sur une alerte → ouvre la page conjunction avec les 2 ground tracks
```

**Pourquoi le polling plutôt que SSE/WebSocket pour l'instant ?**  
SSE ou WebSocket nécessitent une gestion de session et de reconnexion côté Angular. Le polling à 30 s est suffisant pour des alertes de conjunction (les TCA sont calculés sur des fenêtres de 24 h) et évite une complexité prématurée. Le passage au SSE peut être fait en M5 sans rien casser.

---

## Remise en question du plan initial

Le milestone 3 du journal indique simplement "module de calcul distance 3D entre satellites".  
Voici les **questions critiques** qui ont guidé ce plan :

1. **"Distance 3D" dans quel repère ?**  
   La distance doit être calculée en repère ECI (TEME ou GCRF) sur les vecteurs de position directement issus du propagateur SGP4, **pas** en coordonnées géodésiques. Utiliser lat/lon/alt pour ça serait une erreur physique.

2. **Deux satellites propagés indépendamment ou ensemble ?**  
   On propage chaque satellite indépendamment avec son propre TLE, puis on compare leurs vecteurs de position à chaque pas de temps. C'est l'approche standard pour une analyse de conjonction simplifiée (pas de corrélation d'erreur = pas de TCA/Pc au sens complet, mais suffisant pour M3).

3. **Fenêtre de temps et pas ?**  
   Paramétrable par l'utilisateur. Valeur par défaut : 24 h à pas de 60 s → 1 440 points par satellite. C'est raisonnable en mémoire et en CPU sans pagination.

4. **Seuil de rapprochement critique ?**  
   Paramétrable, défaut **5 km**. En dessous, on considère le rapprochement "critique". Le TCA (Time of Closest Approach) est l'instant du minimum local.

5. **Faut-il une base de données pour stocker les alertes ?**  
   Non pour M3. Le calcul est déclenché à la demande (on-demand) et le résultat est retourné directement dans la réponse JSON. La persistance des alertes viendra en M4/M5 quand le volume le justifiera.

6. **Front : afficher comment les conjunctions ?**  
   Sur la carte Leaflet 2D existante : les deux ground tracks colorées différemment + un marqueur d'alerte sur le point de TCA. Simple, cohérent avec l'existant.

---

## Architecture cible

```
[Angular]
  ConjunctionFormComponent  →  saisie des 2 TLEs + fenêtre + seuil
  ConjunctionMapComponent   →  2 polylines + marqueur TCA (Leaflet)
  ConjunctionResultPanel    →  tableau des TCAs trouvés (distance, instant)

[Spring Boot]
  POST /api/v1/conjunction/analyze
    ← ConjunctionRequest  (tle1Sat1, tle2Sat1, tle1Sat2, tle2Sat2, durationHours, stepSeconds, thresholdKm)
    → ConjunctionReport   (List<ConjunctionEvent>)

  ConjunctionService
    - propagate les deux satellites sur la fenêtre
    - calcule distance ECI à chaque pas
    - détecte les minima locaux < seuil
    - raffine le TCA par interpolation parabolique (±1 step)
    - retourne la liste des ConjunctionEvent triés par distance croissante
```

---

## DTOs et entités backend

```java
// ── Entité JPA persistée ──────────────────────────────────────────────────────
@Entity
@Table(name = "conjunction_alert")
class ConjunctionAlert {
    @Id @GeneratedValue Long id;
    String   nameSat1, nameSat2;
    Instant  tca;               // Time of Closest Approach
    double   distanceKm;
    // Position sat1 au TCA
    double   lat1, lon1, alt1;
    // Position sat2 au TCA
    double   lat2, lon2, alt2;
    Instant  detectedAt;        // quand le job l'a détectée
    boolean  acknowledged;      // lue / acquittée par l'utilisateur
}

// ── Requête on-demand (debug / tests) ────────────────────────────────────────
record ConjunctionRequest(
    String nameSat1, String tle1Sat1, String tle2Sat1,
    String nameSat2, String tle1Sat2, String tle2Sat2,
    double durationHours,   // défaut : 24.0
    int    stepSeconds,     // défaut : 60
    double thresholdKm      // défaut : 5.0
) {}

// ── Résultat de calcul (non persisté, retourné par le service) ────────────────
record ConjunctionEvent(
    Instant tca,
    double  distanceKm,
    SatellitePosition sat1,
    SatellitePosition sat2
) {}

record ConjunctionReport(
    String   nameSat1,
    String   nameSat2,
    double   thresholdKm,
    Instant  windowStart,
    Instant  windowEnd,
    List<ConjunctionEvent> events
) {}
```

---

## Plan d'action

### Étape 3.0a — Backend : `GET /api/v1/orbit/positions` (snapshot catalogue à la demande)
- Endpoint : `GET /api/v1/orbit/positions?catalog=stations`
- Pour chaque satellite en mémoire : `PropagationService.propagate(tle, Instant.now())`  
  → SGP4 extrapole depuis l'époque du TLE (émis il y a < 6 h grâce au `FetchCelesTrackTLEJob`)  
  → position fiable sur 24–48 h d'extrapolation
- Calcul en `parallelStream` → retourne `List<SatellitePosition>` en < 50 ms pour 500 sats
- **Pas de persistance** : les positions sont recalculées à chaque requête à partir des TLEs en mémoire
- Factoriser dans `PropagationService.snapshotCatalog()` → réutilisé par le `ConjunctionScanJob` (3.3)

> **Pourquoi pas un job qui précalcule les positions ?**  
> Un `@Scheduled` qui tourne toutes les 60 s pour écrire en BDD des positions périmées 60 s plus tard ajoute de la complexité sans valeur. Le calcul est instantané, le pull simple suffit.  
> **Exception future (M5)** : le catalogue de débris (~27 000 objets) sera trop lourd pour un calcul synchrone — là un job de précalcul toutes les 5 min avec cache en mémoire deviendra pertinent.

### Étape 3.0b — Frontend : deux modes de visualisation complémentaires

**Vue principale — carte live (mode `snapshot`)**
- Page `/map` (ou accueil) : N marqueurs, un par satellite, position `now`
- Pull automatique toutes les **60 s** via `interval(60_000)` + `switchMap` → `GET /positions`
- `L.layerGroup()` pour remplacer tous les marqueurs d'un coup sans flasher la carte
- Marqueur cliquable → popup avec nom, lat/lon/alt → bouton **"Voir le ground track"**

**Vue détail — ground track (mode `track`, existant depuis M2)**
- Accessible en cliquant sur un marqueur → navigue vers `/orbit?name=<satellite>`
- Calcul à la demande (`GET /groundtrack?name=ISS&duration=90&step=60`) — statique, non rafraîchi
- Le formulaire TLE manuel reste disponible pour les satellites hors catalogue

> Ces deux modes coexistent sans conflit : la vue live répond à *"où sont tous les satellites maintenant ?"*,  
> le ground track répond à *"où va ce satellite dans les 90 prochaines minutes ?"*  
> Le ground track **n'est pas supprimé** — il devient la vue de détail naturelle de la carte live.

### Étape 3.1 — Backend : `ConjunctionService` (logique pure)
- Méthode principale : `ConjunctionReport analyze(ConjunctionRequest req)`
- Boucle temporelle sur `[now, now + durationHours]` par pas de `stepSeconds`
- À chaque instant : propager les 2 TLEs → positions ECI (x/y/z) → distance euclidienne
- Détection de minima locaux : `d[i-1] > d[i] < d[i+1]` ET `d[i] < thresholdKm`
- Raffinement du TCA par interpolation parabolique sur 3 points
- Retourne la liste des `ConjunctionEvent` triés par distance croissante
- **Aucune dépendance JPA** dans ce service — logique pure, testable unitairement

### Étape 3.2 — Backend : entité `ConjunctionAlert` + `ConjunctionAlertRepository`
- Entité JPA `ConjunctionAlert` avec tous les champs (voir DTOs ci-dessus)
- `ConjunctionAlertRepository extends JpaRepository<ConjunctionAlert, Long>`
- Méthode custom : `findByAcknowledgedFalseOrderByTcaAsc()` → pour le badge IHM
- Méthode de déduplication : `existsByNameSat1AndNameSat2AndTcaBetween()` → éviter de rejouer la même alerte à chaque scan

### Étape 3.3 — Backend : `ConjunctionScanJob`
- `@Scheduled(fixedDelayString = "${conjunction.scan.delay-ms:3600000}")` → toutes les heures
- `@ConditionalOnProperty(name = "conjunction.scan.enabled", matchIfMissing = true)` → désactivable en test
- Itère sur toutes les paires de satellites du catalogue `TleService` (uniquement les catalogues configurés)
- Pour chaque paire : appelle `ConjunctionService.analyze()` avec les paramètres par défaut
- Pour chaque `ConjunctionEvent` retourné : vérifie si l'alerte existe déjà (déduplication ± 5 min autour du TCA), sinon persiste en `ConjunctionAlert`
- Log des nouvelles alertes avec niveau `WARN`

### Étape 3.4 — Backend : `ConjunctionController`
- `POST /api/v1/conjunction/analyze` → `ConjunctionReport` (on-demand, debug/tests)
- `GET  /api/v1/conjunction/alerts` → `Page<ConjunctionAlert>` (liste paginée, triée par TCA desc)
- `GET  /api/v1/conjunction/alerts/unread` → `List<ConjunctionAlert>` (non acquittées, pour le badge)
- `PUT  /api/v1/conjunction/alerts/{id}/ack` → marquer une alerte comme lue
- Validation `@Valid` sur `ConjunctionRequest` : durée 1–72 h, pas 10–300 s, seuil 0.1–200 km
- Gestion des erreurs : TLE invalide → 400, propagation impossible → 422

### Étape 3.5 — Backend : résolution par nom de catalogue
- Endpoint additionnel : `POST /api/v1/conjunction/analyze-by-name`
- Paramètres : `nameSat1`, `nameSat2` (résolus via `TleService`)
- Permet de tester le service sans saisir les TLEs manuellement

### Étape 3.6 — Tests backend
- `ConjunctionServiceTest` : ISS vs CSS → événements trouvés avec seuil 500 km
- Test cas limite : même TLE × 2 → distance = 0 km → TCA à t=0
- Test déduplication : appel double du job sur la même fenêtre → 0 nouvelle alerte
- `ConjunctionControllerTest` : MockMvc, 400 sur TLE invalide, 200 + JSON valide, GET alerts

### Étape 3.7 — Frontend : `ConjunctionService` (Angular)
- `analyze(req): Observable<ConjunctionReport>` → `POST /analyze`
- `getAlerts(): Observable<ConjunctionAlert[]>` → `GET /alerts`
- `getUnreadAlerts(): Observable<ConjunctionAlert[]>` → `GET /alerts/unread`
- `acknowledge(id): Observable<void>` → `PUT /alerts/{id}/ack`
- Interfaces TypeScript : `ConjunctionRequest`, `ConjunctionEvent`, `ConjunctionReport`, `ConjunctionAlert`

### Étape 3.8 — Frontend : badge de notification dans la navbar
- Polling sur `GET /alerts/unread` toutes les 30 s via `interval(30_000)` + `switchMap`
- Badge rouge avec le nombre d'alertes non lues sur l'icône cloche de la navbar
- Clic sur le badge → ouvre un panneau latéral (drawer) avec la liste des alertes

### Étape 3.9 — Frontend : `ConjunctionResultPanel`
- Liste des `ConjunctionAlert` : TCA (formaté UTC), distance (km), noms des satellites
- Indicateur couleur : rouge < 1 km / orange 1–5 km / jaune 5–50 km
- Bouton "Voir sur carte" → navigue vers `/conjunction` avec les paramètres pré-remplis
- Bouton "Acquitter" → appelle `acknowledge(id)` et retire l'alerte du badge
- Si liste vide → "Aucun rapprochement critique détecté"

### Étape 3.10 — Frontend : `ConjunctionFormComponent` + `ConjunctionMapComponent`
- Formulaire : 2 blocs TLE (nom + tle1 + tle2) ou mode "catalogue" (saisie du nom)
- Paramètres avancés (accordéon) : durée, pas, seuil
- `ConjunctionMapComponent` : 2 polylines colorées + marqueur ⚠ sur le TCA (réutilise `MapComponent`)
- Popup du marqueur : instant TCA + distance + noms des satellites

### Étape 3.11 — Page `ConjunctionPageComponent`
- Route `/conjunction` dans `app.routes.ts`
- Orchestration : formulaire → service → panel résultat + carte
- Peut être pré-alimenté depuis le drawer (clic sur alerte existante)

---

## Ordre d'exécution recommandé

```
3.0a → 3.0b                            (carte live avec tous les satellites — fondation visuelle)
  ↓
3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6    (backend conjunctions complet + testé)
                                  ↓
              3.7 → 3.8 → 3.9 → 3.10 → 3.11  (frontend conjunctions)
```

> **Pourquoi 3.0a/3.0b en premier ?**  
> La carte live avec tous les satellites donne une base visuelle immédiatement utile, valide le pipeline de propagation à grande échelle, et fournit le `snapshotCatalog()` dont le `ConjunctionScanJob` (3.3) a besoin.

---

## Critère de validation du Milestone 3

> 1. Le `ConjunctionScanJob` tourne toutes les heures et persiste les rapprochements critiques en base.  
> 2. Le badge de notification dans la navbar affiche le nombre d'alertes non lues (rafraîchi toutes les 30 s).  
> 3. Cliquer sur une alerte ouvre la page `/conjunction` avec les deux ground tracks et le marqueur ⚠ sur le TCA.  
> 4. Acquitter une alerte la retire du badge.

---

## Question : quand passe-t-on à la visualisation 3D ?

### Réponse claire : **Milestone 5, pas le 6**

Le plan initial prévoyait CesiumJS en M6 (version vitrine). Je recommande d'**avancer la 3D en M5**, pour la raison suivante :

| Milestone | Contenu initial | Recommandation |
|-----------|----------------|----------------|
| M3 | Détection de rapprochements | **Ce plan** — `ConjunctionService` + `ConjunctionScanJob` + BDD + notifications IHM |
| M4 | Analyse d'évolution orbitale | Inchangé — paramètres orbitaux (a, e, i, RAAN…), détection de dérive, historique en BDD |
| M5 | Surveillance des débris | **Fusion M5 + 3D** : heatmap orbitale + premier globe CesiumJS |
| M6 | Version vitrine | Consolidation 3D, remplacement polling → SSE, animations temps réel, export |

**Pourquoi avancer la 3D ?**
- La heatmap de débris (M5) est beaucoup plus lisible en 3D qu'en 2D
- Les conjunctions (M3) seront aussi plus explicites sur un globe
- CesiumJS s'intègre très bien dans Angular en tant que composant autonome, sans remettre en cause l'existant Leaflet
- M6 devient alors une phase de **polish** (temps réel, animations orbitales, export) plutôt qu'une phase de démarrage

**Stack 3D retenue** : **CesiumJS** (ion ou open-source) via `@cesium/engine` ou le wrapper Angular `angular-cesium` (à évaluer selon la maturité Angular 21).  
Alternative légère envisageable : **Three.js** avec un globe custom, mais CesiumJS reste le standard pour les applications spatiales.

---

## Notes techniques

- L'interpolation parabolique du TCA évite de descendre le pas à < 1 s (coûteux) : on trouve le minimum analytique entre 3 points consécutifs.
- `ConjunctionScanJob` suit exactement le même patron que `FetchCelesTrackTLEJob` déjà en place (`@ConditionalOnProperty` pour désactiver en test, `@Scheduled` avec délai configurable).
- La déduplication en base (`existsByNameSat1AndNameSat2AndTcaBetween`) est essentielle : le job relance une analyse sur une fenêtre glissante, il ne faut pas créer une nouvelle alerte à chaque heure pour le même TCA.
- `ConjunctionService.analyze()` est **sans état et sans dépendance JPA** → testable unitairement, réutilisable par le job ou par le controller.
- Les coordonnées ECI déjà calculées par `PropagationService` (champs x/y/z de `SatellitePosition`) sont réutilisables directement pour le calcul de distance — pas besoin de repropager.
- En M5 : le polling à 30 s pourra être remplacé par SSE (`SseEmitter`) sans toucher au backend de persistance.
- En prod (PostgreSQL) : ajouter un index composite `(name_sat1, name_sat2, tca)` pour que la déduplication reste O(log n).











