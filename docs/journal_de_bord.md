# Journal de bord - OrbitWatch

**Projet** : OrbitWatch  
**Date de début** : 2026-03-03  
**Auteur** : Mathias Rezenthel  

---

## 1. Objectif global

Développer une plateforme web de surveillance spatiale permettant de :

- Propager des orbites à partir de TLE publiques  
- Détecter les rapprochements critiques (conjunctions)  
- Analyser l’évolution des paramètres orbitaux  
- Surveiller les débris spatiaux  

---

## 2. Milestones et suivi

| Milestone | Objectif | Statut | Notes |
|-----------|----------|--------|-------|
| 1 | Moteur orbital minimal | Terminé | Orekit 13.1.4, propagation SGP4, API REST, 12 tests unitaires |
| 2 | Ground track 2D | Terminé | Backend + frontend opérationnels, intégration CelesTrak, carte live multi-satellites |
| 3 | Détection de rapprochements | Terminé | ConjunctionService + ConjunctionScanJob + BDD (conjunction_alert) + notifications IHM (badge polling 30 s) + page /conjunction + 23 tests |
| 4 | Analyse d'évolution orbitale | Terminé | Historique orbital (noradId, paramètres Keplériens), règles métier + Z-score Smile ML, page Profil satellite avec graphes Chart.js, badge anomalies |
| 5 | Surveillance des débris + 3D | **Terminé** | Heatmap orbitale + globe CesiumJS + assistant RAG v1 (Spring AI) + navigation unifiée |
| 6 | Version vitrine / Agent IA | À venir | Agent Spring AI Tool Calling + mémoire conversationnelle (JdbcChatMemory) + ground track 3D |

---

## 3. Décisions techniques

- **Backend** : Java 17, Spring Boot 4.0.3, Maven  
- **Bibliothèque scientifique** : Orekit 13.1.4  
- **Base de données** : PostgreSQL (prod) / H2 en mémoire (dev)  
- **Frontend** : Angular 21 (standalone), Leaflet 1.9, CesiumJS prévu en M5  
- **Méthodologie** : Milestones incrémentales, tests unitaires pour chaque module scientifique  

---

## 4. Journal d’avancement

### 2026-03-03
- Définition du projet et des jalons  
- Validation de l’idée OrbitWatch : moteur orbital + visualisation  
- Décision stack technologique : Java 17 + Spring Boot + Angular  

### 2026-03-04
- Création structure Maven / Spring Boot  
- Téléchargement et placement orekit-data  

---
### 2026-03-07
**Milestone 1 — Backend**
- `pom.xml` nettoyé, H2 ajouté, `application.properties` configuré (port 8080, profil dev)
- `OrekitInitializer` corrigé (`ClassPathResource`), `SecurityConfig` permissif en dev
- `SatellitePosition` (DTO record), `PropagationService` (SGP4, TEME→ITRF→géodésique)
- `OrbitController` : `GET /api/v1/orbit/position` et `GET /api/v1/orbit/groundtrack`
- `PropagationServiceTest` : 12 tests unitaires (validations physiques ISS)

**Milestone 2 — Frontend**
- `OrbitService` : `getGroundTrack()` via `HttpClient`, interface `SatellitePosition` typée
- `MapComponent` : carte Leaflet OSM, polyline ground track, marqueur sur position initiale
- `TleFormComponent` : formulaire réactif, validation format TLE, pré-rempli ISS
- `OrbitPageComponent` : orchestration form → service → carte, gestion états chargement/erreur
- CORS backend : `CorsConfigurationSource` sur `/api/**` → `localhost:4200`
- `README.md` : architecture, flux de données, stack, scripts

### 2026-03-17
**Milestone 2 — Intégration CelesTrak & architecture catalogue TLE**

- `CelesTrackClient` : client HTTP dédié (`RestTemplate`), télécharge un catalogue CelesTrak par son nom (`GROUP=<name>&FORMAT=TLE`), URL configurable via `tle.celestrak.base-url`
- `TleService` refactorisé : parsing TLE 3 lignes (`parseTle3Lines`), stockage en mémoire thread-safe (`ConcurrentHashMap` + `CopyOnWriteArrayList`), méthodes `findAll`, `findByCatalog`, `findByName`, `resolveUniqueTle` (404/409 si absent/ambigu), `parseEpoch`
- `FetchCelesTrackTLEJob` : job `@Scheduled` (démarrage immédiat + refresh toutes les 6 h), charge les catalogues configurés via `tle.celestrak.catalogs` (défaut : `stations,active,visual`)
- `TleController` : `GET /api/v1/tle/names` (liste triée des satellites) et `GET /api/v1/tle/status` (catalogues chargés + total TLE)
- `OrbitController` mis à jour : résolution du satellite par nom depuis le catalogue en mémoire et propagation
- **Tests unitaires**


---
### 2026-03-21
**Milestone 3 — Lancement : Détection de rapprochements (Conjunctions)**
- Plan M3 rédigé dans `plan_milestone3.md`
- Architecture cible : `ConjunctionService` (propagation ECI, distance euclidienne, minima locaux, raffinement TCA parabolique) + `ConjunctionController` (`POST /api/v1/conjunction/analyze`)
- DTOs : `ConjunctionRequest`, `ConjunctionEvent`, `ConjunctionReport`
- Frontend : `ConjunctionFormComponent`, `ConjunctionResultPanel`, `ConjunctionMapComponent`, page `/conjunction`
- **Décision** : visualisation 3D avancée en **M5** (fusion heatmap débris + globe CesiumJS), pas M6 — plus cohérent car la heatmap et les conjunctions sont bien plus lisibles en 3D. M6 devient une phase de polish.

---
### 2026-03-22
**Milestone 3 — Carte live multi-satellites (étapes 3.0a / 3.0b)**

**Backend**
- `PropagationService.snapshotCatalog()` : propage tous les satellites d'un catalogue en `parallelStream` à `Instant.now()`, erreurs silencieusement ignorées par satellite — réutilisable par le futur `ConjunctionScanJob`
- `GET /api/v1/orbit/positions?catalog=stations` : nouveau endpoint, retourne un snapshot JSON de toutes les positions instantanées (~50 ms, ~50 Ko pour le catalogue `stations`)
- `AppConfig` : bean `RestTemplate` DEV avec bypass SSL (`TrustManager` permissif, `@Profile("dev")`) pour contourner l'erreur PKIX sur CelesTrak — bean prod inchangé
- `GlobalExceptionHandler` : `@RestControllerAdvice` global étendant `ResponseEntityExceptionHandler` — intercepte toutes les exceptions non catchées du workflow REST, log `ERROR` + retourne 500 JSON structuré ; les exceptions `@ResponseStatus` (404/409) et Spring MVC standard (400) sont re-throwées pour que Spring les gère normalement

**Frontend**
- `OrbitService.getAllPositions()` : appel vers `GET /api/v1/orbit/positions`
- `MapLiveComponent` : carte Leaflet avec N marqueurs, pull automatique toutes les 60 s via `interval + switchMap + catchError` (stream RxJS non interrompu sur erreur), `ChangeDetectorRef.markForCheck()` pour forcer le re-rendu Angular (`OnPush`), marqueurs en style inline (contournement encapsulation SCSS), `invalidateSize()` après init pour recalcul hauteur Leaflet
- `MapPageComponent` : page `/map` avec topbar de navigation, route par défaut
- `OrbitPageComponent` : lit le query param `?name=` au chargement → lance automatiquement le ground track depuis la carte live ; lien retour "← Carte live"

---
### 2026-03-26
** Backend : `ConjunctionService` (logique pure)**
- Méthode principale : `ConjunctionReport analyze(ConjunctionRequest req)`
- Boucle temporelle sur `[now, now + durationHours]` par pas de `stepSeconds`
- À chaque instant : propager les 2 TLEs → positions ECI (x/y/z) → distance euclidienne
- Détection de minima locaux : `d[i-1] > d[i] < d[i+1]` ET `d[i] < thresholdKm`
- Raffinement du TCA par interpolation parabolique sur 3 points
- Retourne la liste des `ConjunctionEvent` triés par distance croissante
- **Aucune dépendance JPA** dans ce service — logique pure, testable unitairement

**Backend : entité `ConjunctionAlert` + `ConjunctionAlertRepository`**
- Entité JPA `ConjunctionAlert` avec tous les champs (voir DTOs ci-dessus)
- `ConjunctionAlertRepository extends JpaRepository<ConjunctionAlert, Long>`
- Méthode custom : `findByAcknowledgedFalseOrderByTcaAsc()` → pour le badge IHM
- Méthode de déduplication : `existsByNameSat1AndNameSat2AndTcaBetween()` → éviter de rejouer la même alerte à chaque scan
---
### 2026-03-28 - 2026-04-01
**Milestone 3 — Conjunctions : backend + frontend complets (3.3 → 3.11)**

**Backend**
- `ConjunctionScanJob` : job `@Scheduled` (initialDelay 30 s + toutes les heures), itère sur toutes les paires de satellites du catalogue, appelle `ConjunctionService.analyze()`, déduplique via `existsByNameSat1AndNameSat2AndTcaBetween` (±5 min autour du TCA), persiste les nouvelles alertes — désactivable via `conjunction.scan.enabled=false`
- `ConjunctionController` : `POST /analyze` (on-demand TLEs bruts), `POST /analyze-by-name` (résolution via `TleService`), `GET /alerts` (page paginée), `GET /alerts/unread` (badge IHM), `PUT /alerts/{id}/ack` (acquittement) 

**Tests backend**
- `ConjunctionServiceTest` : 11 tests — analyze ISS↔CSS seuil large, tri croissant, fenêtre start<end, seuil nul, même TLE×2, fenêtre trop courte, TCA dans fenêtre, distance positive, `eciDistance` (identique→0, triangle 3-4-5→5), `refineTca` (parabole symétrique, dénominateur nul, clamping)
- `ConjunctionControllerTest` : 12 tests MockMvc — POST /analyze (200/400/empty/500), POST /analyze-by-name (200/404/400 blank), GET /alerts (200 page/vide), GET /alerts/unread (200/vide), PUT /alerts/{id}/ack (204+save/404)

**Frontend**
- `conjunction.model.ts` : interfaces `ConjunctionRequest`, `AnalyzeByNameRequest`, `ConjunctionEvent`, `ConjunctionReport`, `ConjunctionAlert`, `AlertPage`
- `ConjunctionService` (Angular) : `analyze`, `analyzeByName`, `getAlerts`, `getUnreadAlerts`, `acknowledge`
- `AlertBadgeComponent` : polling 30 s sur `/alerts/unread`, badge rouge avec compteur, émet `(badgeClicked)` avec la liste des alertes
- `AlertPanelComponent` : drawer latéral, liste des alertes avec code couleur (rouge <1 km / orange 1–5 km / jaune >5 km), boutons "Voir sur carte" et "Acquitter"
- `ConjunctionFormComponent` : mode catalogue (saisie nom → résolution serveur) + mode manuel (TLEs bruts), paramètres avancés (durée/pas/seuil) en accordéon, pré-remplissage depuis query params
- `ConjunctionMapComponent` : carte Leaflet, deux polylines colorées (cyan/orange), marqueur sur le TCA le plus proche avec popup détaillé
- `ConjunctionPageComponent` : page `/conjunction`, layout sidebar+carte, orchestration formulaire→résultats+carte, badge + panneau intégrés
- `MapPageComponent` : badge + panneau d'alertes ajoutés, lien "Conjunctions" dans la navbar
- `app.routes.ts` : route `/conjunction` ajoutée
- Toute la syntaxe de flux de contrôle Angular moderne (`@if`, `@for`) — pas de `*ngIf`/`*ngFor`


---
### 2026-04-03
**Milestone 4 — Lancement : Analyse d'évolution orbitale**
- Plan M4 rédigé dans `plan_milestone4.md`
- Analyse critique du plan initial (roadmap_ia.md) : cold start problem ignoré, visualisation absente, noms ambigus comme clé, race condition entre jobs
- Architecture cible révisée :
  - `OrbitalElementsExtractor` (Orekit, extraction Keplériens + noradId)
  - `OrbitalHistory` (entité JPA, table `orbital_history`, clé `norad_id`)
  - `OrbitalHistoryJob` (EventListener sur `TleCatalogRefreshedEvent`, purge TTL configurable)
  - `AnomalyDetectionService` Phase 1 (règles métier, seuils configurables, zéro cold start) + Phase 2 (Z-score glissant Smile ML, garde `minHistory=30`)
  - `AnomalyAlert` + `AnomalyScanJob` + `AnomalyController`
  - Frontend : page `/satellite/:name` (Profil satellite), `OrbitalChartComponent` (Chart.js), badge anomalies combiné
- Décision : `noradId` comme clé primaire universelle (prerequis RAG M5)
- Décision : Z-score glissant (Java pur) à la place d'Isolation Forest (risque modules Java 17 + cold start)
- Décision : règles métier explicites *avant* ML — valeur immédiate sans cold start
- Feature ajoutée : filtres `Specification` JPA sur `GET /conjunction/alerts` (oubli M3)
- Feature ajoutée : `GET /satellite/{noradId}/summary` avec champ `textSummary` pour indexation RAG M5

**Milestone 4 — Étapes 4.1 & 4.2 : fondations orbitales**

*Étape 4.1 — `OrbitalElementsExtractor` + `OrbitalElements`*
- `OrbitalElements` (record DTO, `dto/`) : 11 champs — `noradId`, `satelliteName`, `epochTle`, `semiMajorAxisKm`, `eccentricity`, `inclinationDeg`, `raanDeg`, `argOfPerigeeDeg`, `meanMotionRevDay`, `altitudePerigeeKm`, `altitudeApogeeKm`
- `OrbitalElementsExtractor` (@Service) : parse les 2 lignes TLE via `org.orekit.propagation.analytical.tle.TLE`, calcule `a = (μ/n²)^(1/3)` (3e loi de Kepler), périgée/apogée via `a*(1±e) - R_Earth`, convertit `epochTle` via `TimeScalesFactory.getUTC()` — zéro nouvelle dépendance Maven (Orekit déjà présent)
- `OrbitalElementsExtractorTest` : 12 tests unitaires — NORAD ID 25544, `a ≈ 6780 km`, `i ≈ 51.64°`, `e < 0.001`, `altPerigee ∈ [350, 450] km`, RAAN/ω dans `[0°, 360°]`, `n ≈ 15.5 rev/jour`, `apogée ≥ périgée`, TLE malformé → exception, trim des espaces

*Étape 4.2 — `OrbitalHistory` + migration Flyway V2*
- `OrbitalHistory` (entité JPA, `model/`) : table `orbital_history`, index composite `(norad_id, fetched_at DESC)`, constructeur à 11 paramètres + getters, constructeur JPA protégé
- `OrbitalHistoryRepository` : 5 méthodes — `findByNoradIdOrderByFetchedAtDesc` (Pageable), `findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc`, `countByNoradId`, `deleteByFetchedAtBefore` (purge TTL, `@Modifying @Query`), `findDistinctNoradIds` (`@Query DISTINCT` — prêt pour `AnomalyScanJob`)
- `V2__create_orbital_history.sql` : migration Flyway PostgreSQL, `CREATE TABLE IF NOT EXISTS`, `BIGSERIAL`, `TIMESTAMPTZ`, `CREATE INDEX IF NOT EXISTS idx_orbital_history_norad_time`
- `OrbitalHistoryRepositoryTest` : 13 tests `@DataJpaTest` (H2) — save/champs, findByNoradId (isolation satellites, ordre DESC, pagination, inconnu→vide), findByFetchedAtBetween (fenêtre), countByNoradId, deleteByFetchedAtBefore (TTL : 2 supprimés / 0 supprimés), findDistinctNoradIds (2 IDs distincts / table vide)
- `pom.xml` : ajout `spring-boot-starter-data-jpa-test` (scope test) — `@DataJpaTest` déplacé en Spring Boot 4.x vers `org.springframework.boot.data.jpa.test.autoconfigure`

*Correctif M3 — `ConjunctionServiceTest`*
- `refineTca_symmetricParabola_returnsCenter` : test corrigé — avec `d0=d2`, le numérateur `(d0-d2)=0` → `offset=0` → TCA = `tPrev` (pas `tPrev + step` comme le test l'affirmait à tort)
- Nouveau test `refineTca_asymmetricParabola_tcaInWindow` : valide `offset = 15s` pour `d0=10, d1=4, d2=6`

**Étape 4.3 : `OrbitalHistoryJob` (accumulation via EventListener)**

*Pattern event/listener*
- `TleCatalogRefreshedEvent` (record, `job/`) : publié par `FetchCelesTrackTLEJob` après chaque catalogue chargé avec succès — `catalogName` + `List<TleEntry>`
- `FetchCelesTrackTLEJob` modifié : injection `ApplicationEventPublisher`, publication de `TleCatalogRefreshedEvent` après `tleservice.getCatalog().put()` — le fetch reste la seule responsabilité du job
- Décision d'architecture validée : pattern Event/Listener conservé (vs tout dans `FetchCelesTrackTLEJob`) — extensible pour le RAG M5 qui ajoutera un second `@EventListener` sur le même event sans toucher au fetch

*`OrbitalHistoryJob`*
- `@EventListener @Async @Transactional` : s'exécute dans un thread séparé, n'impacte pas le scheduler du fetch
- Itère sur les `TleEntry` de l'event, appelle `OrbitalElementsExtractor.extract()`, persiste via `OrbitalHistoryRepository.save()`
- Skip silencieux si extraction échoue (TLE malformé) + log `WARN` si taux d'échec > 5% du batch
- Purge TTL : `deleteByFetchedAtBefore(now - retentionDays)` en fin de batch (configurable via `orbital.history.retention-days=90`)
- `application.properties` : propriété `orbital.history.retention-days=90` ajoutée

*Tests*
- `OrbitalHistoryJobTest` : 8 tests Mockito — 3 TLEs → 3 saves, champs snapshot corrects (noradId/nom/Keplériens), TLE malformé → skip sans exception, 2 valides + 1 malformé → 2 saves, liste vide → cold start safe (aucun appel extractor/repository), purge TTL appelée après batch, purge TTL non appelée sur liste vide

---
### 2026-04-04
**Étape 4.4 : `OrbitalHistoryController` + endpoints REST**

*DTO `SatelliteSummary`*
- Record DTO (`dto/`) : `noradId`, `name`, `tleLine1`, `tleLine2`, `tleEpoch`, `tleAgeHours`, `latestElements: OrbitalElements`, `recentConjunctions: List<ConjunctionAlert>`, `textSummary: String`
- Champ `textSummary` intentionnellement en langage naturel — prerequis RAG M5 (sera indexé dans PgVector par `OrbitWatchRagService`)

*`OrbitalHistoryController`*
- `GET /api/v1/orbital-history/{noradId}?days=30` → `List<OrbitalElements>` (fenêtre glissante, clampage 1–365 jours)
- `GET /api/v1/orbital-history/{noradId}/latest` → `OrbitalElements` ou **404** si aucun snapshot
- `GET /api/v1/satellite/{noradId}/summary` → `SatelliteSummary` (TLE courant en mémoire + dernier snapshot + conjunctions filtrées par nom + textSummary avec warning TLE obsolète si > 168 h) ou **404**
- `GET /api/v1/orbital-history/{noradId}/export` → CSV téléchargeable (`Content-Disposition: attachment`), en-tête + une ligne par snapshot

---

**Étape 4.5 : filtres `Specification` JPA sur `GET /conjunction/alerts`**

*`ConjunctionSpecification`* (nouveau, `repository/`)
- Factory statique `build(sat, from, to, maxKm)` → `Specification<ConjunctionAlert>`
- `sat` : LIKE insensible à la casse sur `nameSat1` OU `nameSat2`
- `from` / `to` : bornes sur le champ `tca` (ISO-8601)
- `maxKm` : borne supérieure sur `distanceKm`
- Tous critères optionnels, combinés en ET logique

*`ConjunctionAlertRepository`* (modifié)
- Ajout de `JpaSpecificationExecutor<ConjunctionAlert>`

*`ConjunctionController.getAlerts()`* (modifié)
- 4 nouveaux `@RequestParam` optionnels : `sat`, `from` (Instant), `to` (Instant), `maxKm` (Double)
- `repository.findAll(spec, pageable)` au lieu de `findAll(pageable)`

*`ConjunctionControllerTest`* (modifié + étendu)
- 2 tests existants mis à jour pour `findAll(Specification, Pageable)`
- 4 nouveaux tests : filtre `sat`, `maxKm`, combiné `sat+maxKm`, fenêtre temporelle `from+to`

---
**Correctif `ConjunctionServiceTest` (stabilisation)**
- Refactorisation complète : les tests `analyze_*` ne dépendent plus de la propagation SGP4 réelle avec des TLEs CSS fictifs dont la géométrie n'était pas maîtrisée
- Introduction de `serviceWithMock` (PropagationService mocké Mockito) + helper `trackWithMinimum()` générant des trajectoires ECI synthétiques déterministes avec un minimum garanti
- `trackWithMinimum()` utilise `Instant.now()` comme `t0` pour s'aligner sur `windowStart = Instant.now()` calculé dans `analyze()` — évite les échecs temporels
- Tests SGP4 réels conservés uniquement pour les cas géométriquement triviaux : `zeroThreshold` et `sameTleTwice` (ISS vs ISS-B, distance constante = 0)

---
### 2026-04-06
**Étapes 4.6 & 4.7 : `AnomalyDetectionService` Phase 1 (règles métier) + Phase 2 (Z-score)**

*Modèle*
- `AnomalyType` (enum) : `ALTITUDE_CHANGE`, `INCLINATION_CHANGE`, `RAAN_DRIFT`, `ECCENTRICITY_CHANGE`, `STATISTICAL`
- `AnomalySeverity` (enum) : `LOW` (ratio < 2), `MEDIUM` (ratio < 5), `HIGH` (ratio ≥ 5)
- `AnomalyAlert` (entité JPA, table `anomaly_alert`) : `noradId`, `satelliteName`, `detectedAt`, `type`, `severity`, `description`, `acknowledged` — index `(norad_id, type, detected_at DESC)`
- `V3__create_anomaly_alert.sql` : migration Flyway `CREATE TABLE IF NOT EXISTS anomaly_alert` + index

*`AnomalyDetectionService`*
- Phase 1 `detectRuleBased(noradId)` : récupère les 2 derniers snapshots, calcule les deltas périgée/apogée/inclinaison/RAAN/excentricité, compare aux seuils configurables (`anomaly.threshold.*`), retourne les alertes sans persister — cold start safe (< 2 snapshots → liste vide)
- Gestion du saut circulaire RAAN (360°→0°)
- Phase 2 `detectZScore(noradId, windowSize)` : Z-score glissant Java pur (`mean` + `stddev` boucles simples, zéro BLAS), skip si `countByNoradId < anomaly.ml.min-history` (défaut : 30), analyse uniquement le point le plus récent — série constante (`stddev < 1e-10`) → liste vide sans exception
- Utilitaires statiques `mean()`, `stddev()`, `severity()` — package-visible pour les tests
- `pom.xml` : ajout `smile-core 3.1.1` + `maven-surefire-plugin` avec `--add-opens java.base/java.lang=ALL-UNNAMED`
- `application.properties` : 6 nouvelles propriétés `anomaly.threshold.*` et `anomaly.ml.*`

*`AnomalyDetectionServiceTest`* (20 tests Mockito purs)
- Phase 1 : cold start (0/1 snapshot), ALTITUDE_CHANGE périgée et apogée, INCLINATION_CHANGE, RAAN_DRIFT, ECCENTRICITY_CHANGE, aucune anomalie, anomalies multiples simultanées, sévérité LOW/HIGH
- Phase 2 : cold start (< minHistory), outlier Z-score > 3.0 → STATISTICAL détecté, série normale → vide, série constante → vide sans exception
- Utilitaires : `mean`, `stddev`, `severity` LOW/MEDIUM/HIGH

---

**Étape 4.8 : `AnomalyScanJob` + `AnomalyController`**

*`AnomalyScanJob`*
- `@Scheduled(fixedDelayString = "${anomaly.scan.delay-ms:3600000}")` + `@ConditionalOnProperty(anomaly.scan.enabled, matchIfMissing = true)`
- Itère sur `findDistinctNoradIds()` (orbital_history), appelle `detectRuleBased()` + `detectZScore()` pour chaque satellite
- Déduplication via fenêtre ±6h : `existsByNoradIdAndTypeAndDetectedAtAfter()` — évite de rejouer la même alerte
- Méthode `persist()` package-visible (testable) — log `WARN` par nouvelle alerte persistée
- Cold start safe : table vide → log `INFO`, aucun appel au service de détection

*`AnomalyAlertRepository`* (mis à jour)
- Ajout `JpaSpecificationExecutor<AnomalyAlert>`

*`AnomalySpecification`* (nouveau, `repository/`)
- Factory `build(noradId, type, severity, from, to)` → `Specification<AnomalyAlert>`, tous critères optionnels

*`AnomalyController`*
- `GET /api/v1/anomaly/alerts` : paginé + 5 filtres optionnels (noradId, type, severity, from, to)
- `GET /api/v1/anomaly/alerts/unread` → `List<AnomalyAlert>` (badge IHM)
- `PUT /api/v1/anomaly/alerts/{id}/ack` → 204 / 404

*`application-test.properties`* : `anomaly.scan.enabled=false`

*Tests*
- `AnomalyControllerTest` : 8 tests MockMvc — GET alertes (200/vide/filtre noradId/filtre type+severity), GET unread (200/vide), PUT ack (204/404)
- `AnomalyScanJobTest` : 9 tests Mockito — `scan()` (cold start, 1 save, dédupliqué, 2 satellites, règle+zscore) + `persist()` (vide, nouveau, dupliqué, 2 alertes dont 1 dupliquée)

---

### 2026-04-06 (suite)
**Étape 4.9 : `spring-ai-bom` dans `pom.xml`**

- Version retenue : **Spring AI 2.0.0-M4** — seule version du projet Spring AI qui cible Spring Boot 4.x / Spring Framework 7.x (les versions 1.x ciblent Boot 3.x)
- Disponible sur Maven Central (pas de dépôt milestone supplémentaire nécessaire)
- Ajout d'une propriété `<spring-ai.version>2.0.0-M4</spring-ai.version>` dans `<properties>`
- Ajout d'un bloc `<dependencyManagement>` avec `spring-ai-bom` en `scope=import` / `type=pom`
- Aucun starter Spring AI n'est activé à ce stade — le BOM est présent uniquement pour la détection préventive des conflits avec Smile 3.1.1 et les dépendances existantes

---

### 2026-04-06 (suite)
**Étapes 4.10 & 4.11 : Frontend — Page Profil satellite + Badge anomalies**

*Dépendance installée*
- `chart.js` ajouté (`npm install chart.js`) — utilisé directement sans ng2-charts (compatible Angular 21 standalone)

*Nouveaux fichiers*
- `satellite.model.ts` : interfaces `OrbitalElements`, `SatelliteSummary`, `ConjunctionAlertRef`, `AnomalyAlert`, `AnomalyType`, `AnomalySeverity`, `AnomalyAlertPage`
- `orbital-history.service.ts` : `getHistory()`, `getLatest()`, `getSummary()`, `getSummaryByName()`, `exportCsv()`
- `anomaly.service.ts` : `getAlerts()`, `getUnreadAlerts()`, `acknowledge()`
- `OrbitalChartComponent` : 4 graphes Chart.js (altitude périgée/apogée, inclinaison, RAAN, excentricité) — canvases toujours dans le DOM (CSS `display:none` au lieu de `@if` pour éviter les problèmes Chart.js), accordéon pour RAAN/excentricité
- `SatelliteProfilePageComponent` (route `/satellite/:noradId` et `/satellite/byname/:name`) :
  - Mode noradId direct (depuis AlertPanel → bouton "Voir profil")
  - Mode résolution par nom (depuis carte live popup → bouton "Profil")
  - Layout : header (nom, NORAD ID, badge âge TLE > 7j), éléments Keplériens, graphes historique, anomalies récentes, conjunctions récentes
  - Bouton "Exporter CSV" → téléchargement via `Blob`
  - Bouton "Ground track" → `/orbit?name=`

*Backend — endpoint ajouté*
- `GET /api/v1/satellite/byname/{name}/summary` : résout le nom via `TleService.resolveUniqueTle()`, extrait le noradId, délègue à `getSummary(noradId)` — retourne 404 si inconnu, 409 si ambigu

*Map live — popup mise à jour*
- Bouton "Profil" ajouté dans chaque popup Leaflet → navigue vers `/satellite/byname/:name`

*Étape 4.11 — Badge combiné + Panel à onglets*
- `AlertBadgeComponent` étendu : polling combiné `combineLatest([conjunction/unread, anomaly/unread])` — badge affiche la somme ; émet `CombinedAlerts { conjunctions, anomalies }`
- `AlertPanelComponent` étendu : onglets "Rapprochements" / "Anomalies" avec badges individuels, acquittement des anomalies via `AnomalyService`, bouton "Voir profil" sur chaque anomalie
- `MapPageComponent`, `ConjunctionPageComponent`, `SatelliteProfilePageComponent` : mis à jour pour `CombinedAlerts` (rétro-compatibilité `ConjunctionAlert[]` conservée via setter)
- `app.routes.ts` : routes `satellite/byname/:name` (statique, AVANT) + `satellite/:noradId` (paramétrique)

---

### 2026-04-10 (suite)
**Fallback Space-Track.org — résilience source TLE**

*Contexte*
- CelesTrak peut être momentanément indisponible (maintenance, surcharge) — sans fallback, l'historique orbital s'arrête de s'accumuler et les alertes ne sont plus mises à jour
- Space-Track.org (18th Space Control Squadron / USSPACECOM) est la source officielle dont CelesTrak lui-même se nourrit → fallback fiable et gratuit

*Décisions d'architecture*
- **Pattern Chain of Responsibility** : pour chaque catalogue, les sources sont essayées dans l'ordre jusqu'à succès — extensible sans modifier le job
- **`TleSourceClient`** (interface) : contrat commun `sourceName()` + `getCatalog(catalogName)` — les deux implémentations sont interchangeables
- **`FetchTleJob`** remplace `FetchCelesTrackTLEJob` comme job actif — l'ancien job est conservé mais désactivé par défaut (`@ConditionalOnProperty(tle.legacy-fetch.enabled=false)`) pour ne pas casser les tests existants

*Nouvelles classes*
- `TleSourceClient` (interface, `client/`) : contrat commun pour toutes les sources TLE
- `CelesTrackClient` : ajout `implements TleSourceClient` + `sourceName()` = `"celestrak"` — zéro changement fonctionnel
- `SpaceTrackClient` (@Service, `@ConditionalOnProperty(tle.spacetrack.enabled=true)`) :
  - Auth session : POST `/ajaxauth/login` → cookie `Set-Cookie` (name=value uniquement, Path/Expires strip)
  - Mapping catalogue CelesTrak → requête GP Space-Track : `stations` → NORAD IDs fixes 25544+48274, `active` → `OBJECT_TYPE/PAYLOAD/DECAYED/0`, `visual` → `RCS_SIZE/LARGE`, `debris` → `OBJECT_TYPE/DEBRIS`, catalogue inconnu → fallback `PAYLOAD/DECAYED/0`
  - Fetch : GET `/basicspacedata/query/class/gp/{query}/orderby/NORAD_CAT_ID/format/3le` avec cookie en header
- `FetchTleJob` (@Component, `client/`) : Chain of Responsibility — itère sur `List<TleSourceClient>` injectée par Spring, passe à la source suivante si exception, réponse vide, ou 0 TLE parsés — log WARN par basculement, log ERROR si toutes sources KO

*Tests*
- `SpaceTrackClientTest` : 8 tests Mockito — `sourceName`, login+fetch nominal, URL GP correcte pour `stations` (NORAD IDs), catalogue inconnu → DEFAULT_QUERY, réponse vide → null, login KO réseau, login KO sans cookie (credentials invalides), cookie transmis dans le header du fetch
- `FetchTleJobTest` : 8 tests Mockito — primaire OK (fallback non appelé), primaire KO → fallback, primaire vide → fallback, primaire 0 TLE → fallback, toutes KO → pas d'exception ni d'event, `refreshAll` itère tous catalogues, un catalogue KO n'empêche pas les autres, event contient catalogName+entries corrects

---

### 2026-04-13
**Milestone 5 — Lancement : Surveillance des débris + Globe 3D + Assistant RAG v1**
- Plan M5 rédigé dans `plan_milestone5.md`
- Architecture cible : 3 features — (A) heatmap débris Leaflet, (B) globe CesiumJS, (C) assistant RAG Spring AI
- Décisions clés :
  - Dimension PgVector fixée à **768** (compatible Ollama `nomic-embed-text` ET OpenAI `text-embedding-3-small` avec `dimensions=768`)
  - Garde-fous volume activés avant le catalogue `debris` (`orbital.history.catalogs.exclude=debris`, `conjunction.scan.catalogs=stations,active`)
  - SSE via `SseEmitter` (Spring MVC, pas WebFlux) pour le streaming LLM
  - CesiumJS lazy-loadé sur la route `/globe` pour ne pas pénaliser le chargement initial
  - Ollama en dev (gratuit, local) / OpenAI en prod (même code, profil Spring)

**Étapes 5.1 + 5.11 + 5.2 — Fondations RAG + garde-fous volume**

*Étape 5.1 — config PgVector*
- `V5__pgvector_store.sql` : `CREATE EXTENSION IF NOT EXISTS vector`, table `vector_store` (id UUID, content TEXT, metadata JSONB, embedding vector(768)), index HNSW cosinus
- `application.properties` : config PgVector (dimensions=768, HNSW, COSINE_DISTANCE, initialize-schema=false) + propriétés RAG (ingestion.enabled, ingestion.catalogs, sse.timeout-ms) + garde-fous volume
- `application-dev.properties` : ajout `spring.ai.ollama.base-url=http://localhost:11434`
- `application-test.properties` : exclusion auto-config PgVector + `rag.ingestion.enabled=false` (H2 ne supporte pas l'extension `vector`)
- `application-secrets.properties.example` : ajout des exemples de config OpenAI commentés (pour la prod)

*Étape 5.11 — Garde-fous volume (OrbitalHistoryJob + ConjunctionScanJob)*
- `OrbitalHistoryJob` : nouveau champ `excludedCatalogs` (`@Value` SpEL split), vérification en tête de `onCatalogRefreshed` — le catalogue `debris` est ignoré silencieusement
- `ConjunctionScanJob` : nouveau champ `scannedCatalogs` (`@Value` SpEL split), `scan()` reconstruit la liste uniquement depuis les catalogues autorisés (plus de `tleService.findAll()`)
- Les deux propriétés sont configurables : `orbital.history.catalogs.exclude=debris` et `conjunction.scan.catalogs=stations,active`

*Étape 5.2 — Dépendances Maven Spring AI*
- Starters ajoutés dans `pom.xml` : `spring-ai-starter-model-ollama` et `spring-ai-starter-vector-store-pgvector` (nouveaux GAV Spring AI 2.x, version gérée par le BOM 2.0.0-M4)

---

### 2026-04-19
**Milestone 5 — Feature A : Heatmap débris (étapes 5.3, 5.4 + infrastructure debris)**

#### Étape 5.3 — Backend heatmap orbitale

*Nouveau DTO*
- `HeatmapCell` (record, `dto/`) : `latBandDeg`, `altBandKm`, `count` — représente une cellule de densité orbitale

*`HeatmapController`*
- `GET /api/v1/orbit/heatmap?catalog=debris&altMin=0&altMax=2000`
- Logique sans SGP4 : utilise `OrbitalElementsExtractor` (Keplériens statiques) sur chaque TLE du catalogue
- Agrégation en cellules : `latBand = round(inclinaison/5)*5`, `altBand = round(altPérigée/50)*50`
- **Correction orbites rétrogrades** : inclinaison > 90° → `effectiveInclination = 180° - inclinaison` (ex : Fengyun à 98° → 82° → latBand 80°, pas 100° hors carte)
- Cap à 85° (limite projection Web Mercator Leaflet)
- Filtrage par `altMin`/`altMax`, tri par count décroissant
- Skip silencieux si TLE malformé + log WARN si > 5% d'échecs

*Tests — `HeatmapControllerTest` (12 tests MockMvc)*
- Cas nominaux : 200 OK, JSON correct, paramètre `catalog=debris` par défaut
- Filtrage altitude : `altMin`/`altMax` filtrent correctement, aucun match → liste vide
- Calcul latBand : orbite directe 74°→75°, **rétrograde 98°→80°**, cap 85°
- Tri et agrégation : tri par count desc, 2 objets même cellule → count=2
- Robustesse : TLE malformé → skip silencieux, tous malformés → liste vide (pas de 500)

#### Infrastructure débris — `FetchDebrisTleJob`

*Problème résolu* : CelesTrak ne propose pas de groupe `"debris"` unique — les catalogues de débris ont des noms spécifiques (`fengyun-1c-debris`, `iridium-33-debris`, `cosmos-2251-debris`)

*`FetchDebrisTleJob`* (nouveau `job/`)
- `@ConditionalOnProperty(tle.debris.enabled=true, matchIfMissing=true)`
- `@Scheduled(initialDelay=5000, fixedDelayString=${tle.refresh.delay-ms})` — après `FetchTleJob`
- Télécharge 3 groupes CelesTrak séparément et les **fusionne** sous l'alias logique `"debris"` dans `TleService`
  - `fengyun-1c-debris`  : ASAT chinois 2007, ~3 000 fragments, ~800 km / 98°
  - `iridium-33-debris`  : Collision 2009, ~600 fragments, ~780 km / 86°
  - `cosmos-2251-debris` : Collision 2009 (Cosmos 2251), ~1 500 fragments
- Résilient par groupe : un groupe KO n'empêche pas les autres d'être chargés
- Publie `TleCatalogRefreshedEvent("debris", ...)` → filtré par `OrbitalHistoryJob` (garde-fou 5.11 ✅) et ignoré par `ConjunctionScanJob` (scan sur `scannedCatalogs=stations,active` uniquement ✅)

*`application.properties`* mis à jour
- `tle.celestrak.catalogs=stations` — `"debris"` retiré (groupe invalide CelesTrak)
- Ajout `tle.debris.enabled=true` et `tle.debris.sources=fengyun-1c-debris,iridium-33-debris,cosmos-2251-debris`

*`application-test.properties`*
- `tle.debris.enabled=false` — évite les appels HTTP CelesTrak en test

*Tests — `FetchDebrisTleJobTest` (8 tests Mockito purs)*
- Fusion : 2 groupes OK → TLEs fusionnés, 1 seul event publié avec `catalogName="debris"`
- Alias : `parseTle3Lines` appelé avec `"debris"` (jamais avec le nom du groupe source)
- TleService : clé `"debris"` bien mise à jour dans le catalogue
- Résilience : 1 groupe KO, 1 groupe vide, tous KO → pas d'exception, pas d'event
- Garde-fous : `catalogName` de l'event = `"debris"` (filtrable par `OrbitalHistoryJob`)
- Sources vides `""` → aucun appel CelesTrak, aucun event

#### Étape 5.4 — Frontend overlay heatmap Leaflet

*Nouveaux fichiers*
- `heatmap-cell.model.ts` : interface TypeScript `HeatmapCell { latBandDeg, altBandKm, count }`
- `debris-heatmap.service.ts` : `getHeatmap(catalog, altMin, altMax)` → `Observable<HeatmapCell[]>`

*`MapLiveComponent`* (modifié)
- Import `leaflet.heat` comme side-effect (plugin s'enregistre sur `window.L`)
- Bouton toggle dans la status bar (états : OFF / loading ⏳ / ON avec couleur orange)
- `toggleHeatmap()` → `loadHeatmap()` si inactif, `removeHeatLayer()` si actif
- `renderHeatLayer(cells)` : réplique chaque cellule toutes les 20° de longitude pour créer des **bandes horizontales visibles** (proxy de densité, pas des positions réelles) + bandes symétriques Nord ET Sud
- Gradient cyan→orange→rouge selon la densité, `minOpacity=0.3`
- Feedback utilisateur si catalogue vide (message d'erreur dans la status bar)

*Résolution technique `leaflet.heat` + Esbuild (Angular 21)*
- Problème : `leaflet-heat.js` utilise `window.L` comme global, mais Leaflet est importé comme module ES dans Angular → `L` absent de `window` au moment où le plugin s'exécute
- Solution : exposition explicite dans `main.ts` avant le bootstrap : `(window as any)['L'] = L` — garantit que `window.L` est défini avant tout chargement de composant
- `angular.json` : `allowedCommonJsDependencies: ["leaflet.heat"]`

*Centralisation des endpoints API*
- Nouveau fichier `src/app/config/api-endpoints.ts` : constante `API_ENDPOINTS` avec toutes les URLs groupées par domaine (orbit, tle, orbitalHistory, satellite, conjunction, anomaly)
- Les 5 services Angular existants mis à jour pour utiliser `API_ENDPOINTS` (suppression des `baseUrl` hardcodées)

*`ConjunctionScanJob`* (commentaire enrichi)
- Javadoc de `scan()` précise explicitement le garde-fou volume : `"debris"` (~5 000 objets) exclu de `scannedCatalogs` → O(n²) évité

---

### 2026-04-19 (suite)
**Milestone 5 — Feature B : Globe 3D CesiumJS (étapes 5.5 + 5.6)**

#### Étape 5.5 — Intégration CesiumJS dans Angular 21 / Esbuild

*Installation*
- `npm install cesium@1.140.0`

*`angular.json`* (modifié)
- Ajout des 4 dossiers assets CesiumJS copiés vers `/cesium/` au build : `Workers/`, `Assets/`, `Widgets/`, `ThirdParty/` (nécessaires pour les web workers de propagation et le rendu)
- `allowedCommonJsDependencies` : ajout `"cesium"` (supprime les warnings Esbuild)
- Budgets portés à `maximumWarning: 2MB / maximumError: 5MB` (bundle CesiumJS ~10 Mo en lazy chunk)

*`styles.scss`*
- Import CSS widgets CesiumJS : `@import 'cesium/Build/Cesium/Widgets/widgets.css'`

*`app.routes.ts`*
- Route `/globe` lazy-loadée : `loadComponent: () => import(...GlobePageComponent)` — bundle Cesium isolé dans un chunk séparé, ne pénalise pas le chargement initial

*Problème technique résolu — `UrlTemplateImageryProvider`*
- `OpenStreetMapImageryProvider.fromUrl()` n'existe pas dans CesiumJS 1.140
- Solution : `UrlTemplateImageryProvider` avec template `https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png` et sous-domaines `['a','b','c']` — API stable depuis 1.104

*Problème technique résolu — Import dynamique CesiumJS + Esbuild*
- `window.CESIUM_BASE_URL = '/cesium/'` doit être défini AVANT l'évaluation du module Cesium (même raison que `window.L = L` pour leaflet.heat)
- Solution : `await import('cesium')` dans `ngAfterViewInit()` à l'intérieur de `NgZone.runOutsideAngular()` — Esbuild isole le chunk ET la variable globale est définie juste avant

#### Étape 5.6 — GlobeComponent (satellites + débris + conjunctions 3D)

*Nouveaux fichiers*
- `globe.service.ts` : façade des 3 appels API (`getStations()`, `getDebris()`, `getConjunctionAlerts()`) — wrapping de `OrbitService` et `ConjunctionService`
- `globe.component.ts/html/scss` : composant principal
- `globe-page.component.ts` : page conteneur lazy-loadée

*3 couches CesiumJS toggleables*

| Couche | Technique | Comportement |
|---|---|---|
| **Satellites** | `CustomDataSource` + `billboard` SVG + `label` | Polling 60 s, `GET /orbit/positions?catalog=stations` |
| **Débris** | `PointPrimitiveCollection` (~5 000 pts) | Chargement unique, `GET /orbit/positions?catalog=debris`, couleur altitude : vert < 600 / orange 600-1200 / rouge > 1200 km |
| **Conjunctions** | `Polyline` 3D dans `CustomDataSource` | `GET /conjunction/alerts`, arc entre les 2 satellites au TCA, couleur distance : rouge < 1 km / orange 1-5 / jaune > 5 km |

*Points techniques clés*
- `PointPrimitiveCollection` obligatoire pour les débris — `EntityCollection` avec 5 000 entités < 10 FPS
- `NgZone.runOutsideAngular()` pour tout le rendu CesiumJS, `NgZone.run()` uniquement pour les updates de propriétés Angular (compteurs, états)
- `viewer.destroy()` dans `ngOnDestroy()` — prévient les memory leaks lors de la navigation Angular
- `Ion.defaultAccessToken = ''` — pas de token payant, imagerie OSM gratuite
- `EllipsoidTerrainProvider` — terrain plat WGS84, pas de terrain payant Cesium World Terrain

*Navigation*
- Lien "Globe 3D" ajouté dans la topbar de `MapPageComponent`
- Boutons "Carte 2D" et "Conjunctions" dans la topbar du globe (navigation croisée)
- Panneau de contrôle superposé en overlay CSS avec `pointer-events: none` (les clics passent vers CesiumJS par défaut)
- Légende de couleur des débris affichée quand la couche est active

---

### 2026-05-03
**Milestone 5 — Feature C : Assistant RAG v1 Spring AI (étapes 5.7 à 5.10)**

#### Étape 5.7 — `OrbitWatchIngestionService` (indexation PgVector)

- `@EventListener @Async` sur `TleCatalogRefreshedEvent` — s'exécute en parallèle de `OrbitalHistoryJob` sans dépendance
- Filtre : seuls les catalogues `rag.ingestion.catalogs` (stations, active) sont indexés — `debris` exclu
- Pour chaque satellite : crée un `Document` Spring AI depuis `SatelliteSummary.textSummary()` avec metadata `noradId`, `name`, `catalog`
- Stratégie upsert : suppression par filtre metadata `noradId` avant réindexation pour éviter les doublons
- Guard `rag.ingestion.enabled=false` dans `application-test.properties`
- `OrbitWatchIngestionServiceTest` (Mockito pur) : 3 TLEs → 3 adds, catalogue debris → 0 add, liste vide → 0 add, upsert → delete appelé avant add

#### Étape 5.8 — `OrbitWatchRagService` (pipeline RAG)

- Seuil de similarité cosinus **0.55** (au lieu de `SIMILARITY_THRESHOLD_ACCEPT_ALL`) — évite d'injecter un contexte satellite non pertinent pour des questions hors-sujet
- Contexte injecté conditionnellement : si aucun document ne dépasse le seuil, `{context}` est vide et le LLM répond librement
- `OrbitWatchRagServiceTest` (Mockito pur, 4 tests) : contexte injecté avec 2 docs, question vide → Flux vide, question nulle → Flux vide, aucun doc → prompt sans contexte satellite

#### Étape 5.9 — `ChatController` (SSE Spring MVC)

- `POST /api/v1/ai/chat` + `GET /api/v1/ai/chat/health`
- `SseEmitter` avec timeout configurable (`rag.sse.timeout-ms=120000`)
- `ragTaskExecutor` (pool dédié) via `@Qualifier` — évite le self-invocation proxy Spring
- `onErrorResume` sur le `Flux` : Ollama indisponible → message SSE lisible au lieu d'un 500
- `ChatControllerTest` (MockMvc, 5 tests) : SSE 200, corps vide → 400, question vide → 400, exception service → 200 sans 500 non géré

#### Étape 5.10 — `ChatComponent` Angular (client SSE)

- `ChatService` : parsing SSE robuste avec **buffer** inter-chunks TCP — les événements incomplets sont conservés jusqu'au prochain chunk (`split('\n\n')` sur les événements complets)
- `line.slice('data:'.length)` à la place de `.replace + .trim()` — préserve les espaces en début de token (évitait la fusion de mots : `"Salutations.Comment"`)
- Tokens multi-lignes reconstitués par `join('\n')` des lignes `data:` d'un même événement
- `ChatComponent` : accumulation des tokens dans `currentToken`, consolidation en message assistant à la complétion du stream, `ChangeDetectionStrategy.OnPush`
- `system-prompt.txt` simplifié : contexte injecté uniquement si présent, LLM non contraint pour les questions hors-sujet (corrigeait les réponses tronquées/incohérentes avec gemma3:4b)

---

### 2026-05-04
**Milestone 5 — Navigation + polish UI (étape 5.12)**

#### Étape 5.12 — Cohérence de la navigation

- **`orbit-page`** : ajout topbar standard (logo + 5 liens + `routerLinkActive`) — refactor du layout `grid` en `flex-column` + `orbit-body` grid pour accommoder la topbar ; import `RouterLinkActive` dans le composant
- **`chat-page`** : remplacement de la `<nav>` minimaliste par la topbar standard unifiée ; remplacement des styles `.chat-nav` par `.topbar` cohérent avec les autres pages
- **`map-live` popup** : ajout d'un bouton "Globe 3D" dans la popup Leaflet naviguant vers `/globe?satellite=NAME`
- **`globe.component`** : topbar overlay complétée avec tous les liens (Ground track, Assistant IA) ; import `ActivatedRoute` ; lecture du query param `?satellite=` au chargement des stations → `flyTo()` vers l'entité CesiumJS ciblée

**Résultat** : navigation fluide entre toutes les pages de l'application — Carte ↔ Globe ↔ Ground track ↔ Conjunctions ↔ Assistant IA, avec raccourci direct depuis les popups satellites.

**Milestone 5 — TERMINÉ** (étapes 5.1 → 5.12)

---

### 2026-05-04
**Milestone 6 — Lancement : Agent IA + Mémoire conversationnelle + Ground track 3D**
- Plan M6 rédigé dans `plan_milestone6.md`
- Objectifs : agent Spring AI Tool Calling opérationnel, mémoire conversationnelle persistante par session, ground track 3D sur le globe CesiumJS
- Aucune nouvelle dépendance Maven — tout est dans le BOM Spring AI 2.0.0-M5 déjà importé

#### Étape 6.1 — Migration Flyway V6 : table `chat_history`

- `V6__create_chat_history.sql` : table `chat_history (id, session_id, role, content, created_at)` + index composite `(session_id, id)` (tri par id plus robuste que created_at en cas de résolution milliseconde insuffisante)
- `init_schema.sql` : section V6 ajoutée (cohérence avec le script d'initialisation manuelle)

#### Étape 6.2 — `JdbcChatMemory` (implémentation `ChatMemory`)

- `JdbcChatMemory` (`@Component`, implémente `org.springframework.ai.chat.memory.ChatMemory`) : persistance JDBC des échanges de chat via `JdbcTemplate` sur la table `chat_history`
- `add()` → INSERT par message (role : USER / ASSISTANT / SYSTEM)
- `get(conversationId, lastN)` → `ORDER BY id DESC LIMIT ?` puis inversion — fiable même si timestamps identiques (H2, transactions rapides)
- `clear(conversationId)` → DELETE complet de la session
- Mapping bidirectionnel `MessageType` ↔ `String role` ; roles inconnus ignorés avec log WARN
- `JdbcChatMemoryTest` (`@DataJpaTest` + `@Import(JdbcChatMemory.class)`) : 8 tests — ordre chronologique, lastN, isolation sessions, clear, 3 rôles reconstitués, liste vide


