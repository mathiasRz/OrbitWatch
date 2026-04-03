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
| 4 | Analyse d'évolution orbitale | En cours | Historique orbital (noradId, paramètres Keplériens), règles métier + Z-score Smile ML, page Profil satellite avec graphes Chart.js, badge anomalies |
| 5 | Surveillance des débris + 3D | À venir | Heatmap orbitale + globe CesiumJS + assistant RAG v1 (Spring AI) |
| 6 | Version vitrine / Agent IA | À venir | Agent autonome Spring AI Tool Calling, polish 3D, SSE, export |

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



- Pour Orekit, initialiser correctement les données via :

```java
@Component
public class OrekitInitializer {
    @PostConstruct
    public void init() throws Exception {
        File orekitData = new File("src/main/resources/orekit-data");
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
    }
}