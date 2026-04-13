# Plan d'implémentation — Milestone 5 : Surveillance des débris + Globe 3D + Assistant RAG v1
**Date de rédaction** : 2026-04-13

---

## Objectif global

Ajouter trois features majeures sur la base solide de M4 :

| Feature | Périmètre |
|---------|-----------|
| **A — Débris spatiaux + Heatmap** | Charger le catalogue `debris` CelesTrak, calculer une heatmap de densité orbitale et l'afficher en overlay sur la carte Leaflet |
| **B — Globe 3D CesiumJS** | Nouvelle page `/globe` — satellites actifs, débris colorés par altitude, trajectoires de conjunction en 3D |
| **C — Assistant RAG v1 Spring AI** | Indexation PgVector des `textSummary` satellites, recherche vectorielle, réponse streamée (SSE) via Ollama (dev) / OpenAI (prod) |

---

## Analyse critique des prérequis

### Ce qui est déjà en place

- **Spring AI 2.0.0-M4 BOM** déjà importé dans `pom.xml` — aucun conflit de version à craindre
- **`SatelliteSummary.textSummary`** (champ texte en langage naturel) — prêt pour l'embedding RAG
- **`OrbitalElementsExtractor`** — extraction Keplérienne statique sans SGP4, réutilisable pour la heatmap
- **`TleService.findByCatalog()`** — permet de filtrer par catalogue dès l'ajout de `debris`
- **`TleCatalogRefreshedEvent`** — pattern Event/Listener extensible (un `@EventListener` supplémentaire pour l'ingestion RAG sans toucher au fetch job)
- **`snapshotCatalog()` en `parallelStream`** — existant dans `PropagationService`, utilisable pour les positions 3D
- **`GET /api/v1/orbit/positions?catalog=`** — endpoint existant, réutilisable par `GlobeComponent`

### Ce qui manque / points de vigilance 

1. **Volume explosion avec `debris`** : sans garde-fous, `OrbitalHistoryJob` persistera ~2 000 lignes/fetch et `ConjunctionScanJob` tentera O(n²) paires → **les garde-fous (étape 5.11) doivent être mis en place AVANT d'activer le catalogue `debris`**.
2. **Migration PgVector** : `CREATE EXTENSION vector` nécessite les droits `SUPERUSER` ou `CREATE EXTENSION` (PostgreSQL 15+) sur le user `orbitwatch` — vérifier avant le déploiement.
3. **Dimension vecteur figée** : Ollama `nomic-embed-text` produit des vecteurs de dimension **768**, OpenAI `text-embedding-ada-002` produit **1536**. La dimension est figée dans la migration V5 — **décider dès maintenant** : utiliser **768** (Ollama) pour dev et adapter la prod (même modèle nomic via OpenAI-compatible endpoint), ou accepter de recréer la table en V6 si l'on passe à OpenAI.
4. **CesiumJS dans Angular 21** : module CJS volumineux (~10 Mo) avec Esbuild — risque de warnings `allowedCommonJsDependencies` et de lenteur de build.
5. **SSE avec Spring MVC** : le projet utilise `spring-boot-starter-webmvc` (pas WebFlux) — utiliser `SseEmitter` (Spring MVC) au lieu de `Flux<String>` (WebFlux).

---

## Architecture cible

```
[Angular]
  MapLiveComponent (/map)
    ├── Overlay heatmap Leaflet (toggle)         ← DebrisHeatmapService
    └── Bouton "Voir en 3D" → /globe

  GlobePageComponent (/globe)
    └── GlobeComponent (CesiumJS)
          ├── Satellites actifs   → GET /orbit/positions?catalog=stations (polling 60s)
          ├── Débris              → GET /orbit/positions?catalog=debris (chargement unique)
          └── Conjunctions 3D     → GET /conjunction/alerts

  ChatPageComponent (/chat)
    └── ChatComponent
          ← POST /api/v1/ai/chat (SSE stream)

[Spring Boot]
  FetchTleJob (existant, catalogs: stations, active, debris)
    └── publie TleCatalogRefreshedEvent
          ├── OrbitalHistoryJob (existant, filtre: skip debris)  ← GARDE-FOU 5.11
          └── OrbitWatchIngestionService (nouveau, filtre: only stations + active)

  HeatmapController
    └── GET /api/v1/orbit/heatmap?catalog=debris&altMin=&altMax=
          └── OrbitalElementsExtractor (existant, sans SGP4)

  ChatController
    └── POST /api/v1/ai/chat  (SSE, SseEmitter)
          └── OrbitWatchRagService
                ├── VectorStore.similaritySearch() (PgVector)
                └── ChatClient.prompt().stream() (Ollama / OpenAI)

  BDD PostgreSQL
    └── vector_store (migration V5 — extension vector + table + index HNSW)

  ConjunctionScanJob (existant, garde-fou: catalogs = stations, active SEULEMENT)  ← GARDE-FOU 5.11
```

---

## Plan d'action détaillé

---

### Étape 5.1 — activation PgVector
**Objectif** : Activer l'extension `vector` sur PostgreSQL et créer la table `vector_store` pour Spring AI.


**Points de vigilance** :
- Choisir la dimension **768** dès M5 pour éviter une migration destructive en M6 (OpenAI `text-embedding-3-small` supporte aussi 768 via paramètre `dimensions`).

---

### Étape 5.2 — Dépendances Maven Spring AI (starters Ollama + PgVector)
**Objectif** : Activer les starters Spring AI (le BOM 2.0.0-M4 est déjà dans `pom.xml`).

**Points de vigilance** :
- Vérifier la disponibilité des artefacts `spring-ai-ollama-spring-boot-starter` et `spring-ai-pgvector-store-spring-boot-starter` sur Maven Central sous le GAV `2.0.0-M4`.
- `mvn dependency:tree` après ajout — surveiller les conflits transitifs avec `smile-core 3.1.1`.
- Des `--add-opens` supplémentaires peuvent être nécessaires dans `maven-surefire-plugin` pour les modules Reactor/Netty.

**Critère de validation** : `mvn test` vert après ajout des starters (PgVector désactivé en test via `application-test.properties`).

---

### Étape 5.3 — Feature A : Catalogue débris + endpoint heatmap (Backend)
**Objectif** : Charger le catalogue `debris` et calculer une heatmap de densité orbitale par cellule (lat/alt).

**Prérequis impératif** : l'étape 5.11 (garde-fous volume) doit être appliquée simultanément ou avant.

Logique :
- Récupère les TLEs via `TleService.findByCatalog(catalog)`
- Pour chaque TLE, appelle `OrbitalElementsExtractor.extract()` (statique, sans propagation SGP4)
- Filtre par `altMin`/`altMax` sur `altitudePerigeeKm`
- Agrège en cellules `(latBand = round(inclinationDeg/5)*5, altBand = round(altPeri/50)*50)`
- Retourne la liste triée par `count DESC`

**Points de vigilance** :
- Utiliser `OrbitalElementsExtractor` et **non** `snapshotCatalog()` pour éviter la propagation SGP4 sur ~2 000 objets.
- Inclure un cache simple (Spring `@Cacheable` ou variable `volatile` avec TTL) pour éviter de recalculer à chaque requête frontend.
- La latitude orbitale d'un débris n'est pas fixe — utiliser l'**inclinaison** comme proxy de la bande de latitude atteinte (un débris à inclinaison 82° couvre les latitudes −82° à +82°).

**Critère de validation** :
- `HeatmapControllerTest` (MockMvc, `TleService` mocké) :
  - `GET /orbit/heatmap` → 200 + JSON array non vide
  - `GET /orbit/heatmap?altMin=500&altMax=600` → seules les cellules dans la bande altitude
  - Catalogue inconnu → 200 + liste vide (pas de 404)

---

### Étape 5.4 — Feature A : Overlay heatmap Leaflet (Frontend)
**Objectif** : Afficher la densité des débris sur la carte 2D existante avec un toggle.

`MapLiveComponent` (modifier) :
- Ajouter un bouton toggle "Heatmap débris" dans le panneau de contrôle
- Quand activé : appeler `DebrisHeatmapService.getHeatmap()`, mapper `HeatmapCell[]` en `[latBandDeg, lng=0, count][]` et créer `L.heatLayer(points, { radius: 25, max: 50 })` sur la carte
- Quand désactivé : supprimer le layer

**Points de vigilance** :
- `MapLiveComponent` est en `ChangeDetectionStrategy.OnPush` — appeler `cdr.markForCheck()` après chargement du layer.
- Les points heatmap utilisent `latBandDeg` comme latitude et `0` comme longitude (proxy) — acceptable pour visualiser la **densité par bande de latitude/altitude**, pas les positions réelles.
- Pour une visualisation plus précise, utiliser les positions réelles depuis `GET /orbit/positions?catalog=debris` (plus coûteux).

**Critère de validation** : activer le toggle sur `/map` → gradient rouge/orange visible sur les bandes polaires (Cosmos-deb ~65–82°) et LEO (~51°).

---

### Étape 5.5 — Feature B : Intégration CesiumJS (npm + Angular build)
**Objectif** : Intégrer CesiumJS dans le projet Angular 21 sans casser le build existant.

**Points de vigilance** :
- CesiumJS (~10 Mo) augmente le bundle Angular — activer le **lazy-loading** de la route `/globe` via `loadComponent()` dès M5 pour ne pas pénaliser le chargement initial.
- Désactiver le token Ion (Cesium Ion est payant) et utiliser l'imagerie OSM :
  ```typescript
  Ion.defaultAccessToken = '';
  // Utiliser TileMapServiceImageryProvider avec OpenStreetMap
  ```
- CesiumJS est incompatible avec Angular SSR — vérifier que SSR n'est pas activé dans `angular.json`.

**Critère de validation** : `ng build` sans erreur ni warning `CommonJS`; `ng serve` → page `/globe` sans erreur console (`Cannot read properties of undefined`).

---

### Étape 5.6 — Feature B : `GlobeComponent` — satellites, débris, conjunctions 3D
**Objectif** : Page `/globe` avec rendu 3D interactif des objets orbitaux.

Fonctionnalités :
- **Initialisation** (`ngAfterViewInit`) : `new Viewer(this.container.nativeElement, { ... })` — terrain WGS84 ellipsoid (pas de terrain payant), imagerie OSM
- **Satellites actifs** : polling 60s sur `GET /api/v1/orbit/positions?catalog=stations` → entités `Entity` avec `billboard` (icône SVG) et `label`
- **Débris** : chargement unique depuis `GET /api/v1/orbit/positions?catalog=debris` → `PointPrimitiveCollection` (plus performant que `EntityCollection` pour les masses de points), code couleur par altitude périgée (vert < 600 km / jaune 600–1 200 km / rouge > 1 200 km)
- **Conjunctions** : `GET /api/v1/conjunction/alerts` → arcs `Polyline` 3D entre les positions des deux satellites au TCA
- **Toggles UI** : boutons "Satellites / Débris / Conjunctions" qui masquent/affichent les couches
- **Destruction** (`ngOnDestroy`) : `this.viewer.destroy()`

`globe.service.ts` (`services/`) : wrapping de `OrbitService.getAllPositions()` avec paramètre `catalog`

**Fichiers à modifier** :
- `app.routes.ts` : `{ path: 'globe', loadComponent: () => import(...).then(m => m.GlobePageComponent) }` (lazy loading)
- Navigation principale : lien "🌍 Globe 3D" vers `/globe`

**Points de vigilance** :
- Utiliser `NgZone.run(() => {...})` pour les callbacks CesiumJS qui modifient des données Angular.
- `PointPrimitiveCollection` est obligatoire pour les débris (~2 000 points) — `EntityCollection` avec autant d'entités est trop lent (< 10 FPS).
- `viewer.destroy()` est critique pour éviter les memory leaks lors de la navigation Angular.
- L'endpoint `GET /api/v1/orbit/positions?catalog=debris` propage SGP4 pour ~2 000 objets — mesurer la latence et envisager un endpoint dédié `/api/v1/orbit/positions/static` utilisant `OrbitalElementsExtractor` si > 500 ms.

**Critère de validation** : page `/globe` affiche l'ISS avec son nom ; toggle "Débris" affiche des points colorés ; `ng build` sans erreur.

---

### Étape 5.7 — Feature C : `OrbitWatchIngestionService` (indexation PgVector)
**Objectif** : Indexer les `textSummary` des satellites dans PgVector après chaque fetch TLE.


Document Spring AI :
```java
Document doc = Document.builder()
    .content(summary.textSummary())
    .metadata(Map.of(
        "noradId", summary.noradId(),
        "name", summary.name(),
        "catalog", event.catalogName()
    ))
    .build();
```

Stratégie d'upsert (éviter les doublons) :
- `vectorStore.delete(vectorStore.similaritySearch(...)...)` ou filtrage par metadata `noradId`
- Spring AI 2.0.0-M4 : vérifier si `PgVectorStore` expose un `delete(Filter)` — sinon, utiliser une requête SQL native `DELETE FROM vector_store WHERE metadata->>'noradId' = ?`

**Points de vigilance** :
- Garde-fou embedding : ne réindexer que si le `textSummary` a changé — calculer un hash MD5 du contenu et stocker en metadata `contentHash`
- `OrbitalHistoryJob` et `OrbitWatchIngestionService` s'exécutent tous les deux `@Async` sur `TleCatalogRefreshedEvent` — pas de dépendance entre eux (les deux peuvent s'exécuter en parallèle)
- `rag.ingestion.enabled=false` dans `application-test.properties` pour ne pas appeler Ollama/PgVector dans les tests

**Critère de validation** :
- `OrbitWatchIngestionServiceTest` (Mockito pur, `VectorStore` mocké) :
  - 3 TLEs catalogue `stations` → 3 appels `vectorStore.add()`
  - Catalogue `debris` → 0 appel (filtré)
  - Liste vide → 0 appel
  - Même satellite réindexé → `delete()` appelé avant le `add()` (upsert)

**Dépendances** : 5.1 (migration V5), 5.2 (starters Spring AI).

---

### Étape 5.8 — Feature C : `OrbitWatchRagService` (recherche vectorielle + prompt LLM)
**Objectif** : Cœur du RAG — embed la question, recherche top-k, construit le prompt, appelle le LLM.

**Classe à créer** : `OrbitWatchRagService` (`service/`)

Algorithme :
1. `vectorStore.similaritySearch(SearchRequest.query(userQuestion).withTopK(5))` → `List<Document>`
2. Construire le contexte : concaténer `doc.getContent()` pour les top-5 documents
3. Prompt système (externalisé dans `resources/prompts/system-prompt.txt`) :
   ```
   Tu es l'assistant de surveillance spatiale OrbitWatch.
   Réponds uniquement à partir des données suivantes :
   {context}
   Si l'information n'est pas dans les données, réponds "Je ne dispose pas de cette information."
   Question : {question}
   ```
4. `chatClient.prompt(prompt).stream().content()` — abonner à `Flux<String>` et envoyer via `SseEmitter`


**Points de vigilance** :
- `Flux<String>` (WebFlux) vs `SseEmitter` (MVC) : le projet est en Spring MVC — souscrire au `Flux` dans un thread `@Async` et déléguer à `SseEmitter` (voir étape 5.9)
- Limiter le contexte à 5 documents × ~500 tokens ≈ 2 500 tokens — rester dans la fenêtre llama3.2 (4 096 tokens)
- Vérifier l'API `ChatClient.Builder` et `SearchRequest` dans la Javadoc Spring AI 2.0.0-M4 (API milestone, susceptible de différer des docs GA)

**Critère de validation** :
- `OrbitWatchRagServiceTest` (Mockito pur) :
  - Mock `vectorStore` retournant 2 documents → `chatClient.prompt()` contient les 2 textes dans le prompt
  - Question vide → comportement gracieux (pas d'exception, réponse vide ou message d'erreur)

**Dépendances** : 5.2, 5.7.

---

### Étape 5.9 — Feature C : `ChatController` (SSE via Spring MVC)
**Objectif** : Endpoint REST streamé `POST /api/v1/ai/chat` avec `SseEmitter`.


**Points de vigilance** :
- Le `TaskExecutor` doit avoir un pool suffisant pour les sessions LLM longues (jusqu'à 60s pour Ollama local)
- `SseEmitter` timeout : configurable via `rag.sse.timeout-ms=120000` (2 min)
- Si Ollama est éteint, le `chatClient` lèvera une exception de connexion → `emitter.completeWithError()` → le frontend reçoit l'erreur

**Critère de validation** :
- `ChatControllerTest` (MockMvc) :
  - `POST /chat {"question": "Où est l'ISS ?"}` → 200 + `Content-Type: text/event-stream`
  - `POST /chat {}` (question vide) → 400
  - `POST /chat` quand `OrbitWatchRagService` lance une exception → `completeWithError` sans 500 non géré

**Dépendances** : 5.8.

---

### Étape 5.10 — Feature C : `ChatComponent` Angular (SSE client)
**Objectif** : Interface conversationnelle avec streaming de la réponse.

### Étape 5.11 — Garde-fous volume (OrbitalHistoryJob + ConjunctionScanJob)
**Objectif** : Protéger les jobs existants contre l'explosion combinatoire liée aux 2 000+ débris.

> **Cette étape doit être appliquée AVANT ou EN MÊME TEMPS que l'ajout du catalogue `debris` en 5.3.**


**Critère de validation** :
- `OrbitalHistoryJobTest` : event `catalogName=debris` → 0 appels `repository.save()`
- `ConjunctionScanJobTest` : catalogue `debris` non inclus dans les paires scannées

---

### Étape 5.12 — Navigation + polish UI
**Objectif** : Cohérence de la navigation entre toutes les pages.

**Fichiers à modifier** :
- Topbar principale : liens "🗺️ Carte", "🌍 Globe 3D", "⚠️ Conjunctions", "🤖 Assistant IA"
- `MapLiveComponent` popup : bouton "🌍 Voir en 3D" → `/globe?satellite=NAME`
- `GlobeComponent` : bouton "🗺️ Carte 2D" → `/map`
- `SatelliteProfilePageComponent` : bouton "🤖 Demander à l'IA" → `/chat?q=Analyse [name]`
- `ChatPageComponent` : lecture du query param `q` au démarrage → préremplit la question

**Critère de validation** : navigation fluide entre toutes les pages sans erreur de routage Angular.

---

## Ordre d'exécution recommandé

```
5.1  Migration V5 (PgVector)
  └→ fondation BDD, bloque 5.7

5.11 Garde-fous volume (OrbitalHistoryJob + ConjunctionScanJob)
  └→ OBLIGATOIRE avant d'activer le catalogue débris

5.2  Dépendances Maven Spring AI (starters Ollama + PgVector)
  └→ bloque 5.7, 5.8, 5.9

     [Parallélisable dès ici : Feature A et Feature B sont indépendantes]

5.3  Backend heatmap débris ────────────── Feature A
  └→ bloque 5.4

5.5  Intégration CesiumJS (npm + angular.json) ── Feature B
  └→ bloque 5.6

5.4  Frontend overlay heatmap Leaflet ─── Feature A
  └→ dépend 5.3

5.6  GlobeComponent (satellites + débris + conjunctions) ── Feature B
  └→ dépend 5.5 + 5.3

5.7  OrbitWatchIngestionService ─────────── Feature C
  └→ dépend 5.1 + 5.2

5.8  OrbitWatchRagService ──────────────── Feature C
  └→ dépend 5.7

5.9  ChatController SSE ────────────────── Feature C
  └→ dépend 5.8

5.10 ChatComponent Angular ─────────────── Feature C
  └→ dépend 5.9

5.12 Navigation + polish UI
  └→ dépend 5.4 + 5.6 + 5.10
```

---

## Critères de validation globaux de M5

1. **Heatmap débris** : `GET /api/v1/orbit/heatmap?catalog=debris` retourne ≥ 10 cellules ; overlay visible sur la carte Leaflet sans dégradation des performances du polling 60s.

2. **Garde-fous volume** : `OrbitalHistoryJob` ne persiste aucune ligne pour le catalogue `debris` ; `ConjunctionScanJob` ne génère pas de paires `debris×debris` — vérifiable via `SELECT count(*) FROM orbital_history WHERE satellite_name NOT IN (SELECT name FROM tle_stations)` (doit retourner 0 pour les débris).

3. **Globe 3D** : page `/globe` affiche l'ISS avec son label et ≥ 1 point de débris coloré ; toggle par couche fonctionnel ; `ng build` sans erreur.

4. **Ingestion RAG** : après un fetch du catalogue `stations`, `SELECT count(*) FROM vector_store` ≥ 1 ; le document pour l'ISS contient "ISS" dans la colonne `content`.

5. **Chat RAG** : `POST /api/v1/ai/chat` avec `{"question": "Où est l'ISS ?"}` retourne `Content-Type: text/event-stream` avec des chunks contenant "ISS" (réponse contextualisée).

6. **Zéro régression** : `mvn test` vert sur l'ensemble des tests M1–M4 existants (≥ 50 tests) avec les nouveaux starters Spring AI dans le classpath et `rag.ingestion.enabled=false` en test.

---

## Points de vigilance pour M6

### 1. Dimension PgVector figée
La migration V5 fixe la dimension à 768. Passer à `text-embedding-ada-002` (OpenAI, 1536 dimensions) nécessitera une migration V6 destructive (`DROP TABLE vector_store` + recréation + réindexation). Décider dès M5 quelle dimension est cible de production — utiliser `text-embedding-3-small` (OpenAI, supportant 768 via paramètre `dimensions=768`) pour éviter ce problème.

### 2. CesiumJS lazy loading
Le bundle CesiumJS (~10 Mo) pénalise le temps de chargement initial. La route `/globe` doit être lazy-loadée (`loadComponent()`) — **déjà prévu en 5.5**, mais vérifier que le code splitting est effectif avec `ng build --stats-json`.

### 3. Agent Spring AI Tool Calling (M6)
Les méthodes `ConjunctionService.analyze()`, `OrbitalHistoryController.getHistory()` etc. seront décorées `@Tool` pour l'agent M6. S'assurer que leurs signatures sont **sérialisables en JSON** (pas de `Pageable`, `Instant` en ISO-8601 String, etc.).

### 4. Mémoire conversationnelle (M6)
Le `ChatComponent` M5 n'a pas de mémoire persistante (historique local navigateur uniquement). M6 ajoutera un `ChatMemory` Spring AI — prévoir la migration Flyway V6 pour une table `chat_history (id, session_id, role, content, created_at)`.

### 5. Prérequis matériels Ollama
Sur une machine sans GPU dédié, `llama3.2` sur Ollama peut prendre 10–30 secondes par réponse. Documenter dans `README.md` les prérequis (`ollama pull llama3.2`, `ollama pull nomic-embed-text`) et proposer un modèle plus léger (`qwen2.5:0.5b`) pour les développeurs sans GPU.

### 6. Volume orbital_history avec débris potentiels
Si à terme les débris sont activés dans `orbital.history.catalogs` (ex : pour une analyse ML étendue en M7), 27 000 objets × 4 fetches/jour = **108 000 lignes/jour**. La purge TTL à 90 jours représente ~9,7 millions de lignes. Prévoir un partitionnement PostgreSQL par `fetched_at` (partition mensuelle) avant d'activer cette extension.

---

## Récapitulatif des nouvelles dépendances

| Dépendance | Type | Usage |
|------------|------|-------|
| `spring-ai-ollama-spring-boot-starter` | Maven (géré par BOM 2.0.0-M4) | LLM chat + embedding en dev |
| `spring-ai-pgvector-store-spring-boot-starter` | Maven (géré par BOM 2.0.0-M4) | Vector store PgVector |
| `leaflet.heat` | npm | Overlay heatmap sur carte Leaflet |
| `cesium` | npm | Globe 3D |

## Récapitulatif des nouvelles migrations

| Migration | Contenu |
|-----------|---------|
| `V5__pgvector_store.sql` | `CREATE EXTENSION vector` + table `vector_store` + index HNSW |

## Récapitulatif des nouveaux endpoints REST

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/orbit/heatmap` | Densité des débris par cellule lat/alt |
| `POST /api/v1/ai/chat` | Réponse RAG streamée (SSE) |
| `GET /api/v1/ai/chat/health` | Statut du service LLM |

---

*Document de référence pour le développement de la Milestone 5.*

