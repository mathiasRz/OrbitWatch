# Plan d'implémentation — Milestone 4 : Historique orbital & Détection d'anomalies
**Date de rédaction** : 2026-04-03  

---

## Objectif

Historiser les paramètres orbitaux Keplériens à chaque fetch TLE, détecter automatiquement les comportements anormaux (manœuvres, rentrées, dérives) et les exposer via une page "Profil satellite" avec graphes temporels.

---

## Analyse critique du plan initial

### Ce qui était bien prévu 
- La table `orbital_history` est une fondation absolument nécessaire — sans données accumulées, le ML n'a rien à traiter.
- Smile ML en JVM évite un micro-service Python (cohérence de stack).
- Le patron `AnomalyAlert` calqué sur `ConjunctionAlert` assure une cohérence architecturale forte.
- Introduire `spring-ai-bom` en M4 (même sans l'utiliser) détecte les conflits de dépendances avant M5.

### Ce qui était mal ordonné 
Le plan initial listait "OrbitalHistoryJob → AnomalyDetectionService → Endpoints → Badge" sans jamais mentionner la visualisation. **Résultat** : la donnée existait en BDD mais était invisible côté utilisateur. La visualisation des graphes doit être développée en parallèle de l'accumulation des données, pas après.

### Lacunes identifiées et corrigées dans ce plan

1. **Extraction Keplérienne absente** : `TleEntry` ne contient que les 2 lignes brutes. Il faut parser via `TLE` d'Orekit pour obtenir `a`, `e`, `i`, RAAN, `ω`, `n`. Étape non-triviale, ajoutée en 4.1.

2. **Cold start problem ignoré** : au démarrage, `orbital_history` est vide. Un `AnomalyDetectionService` lancé sur 0 point produira des faux positifs massifs. Garde-fou `minHistorySize` configurable ajouté.

3. **Visualisation absente** : sans graphes, l'historique est invisible. Page "Profil satellite" avec `Chart.js` ajoutée en 4.10 — c'est la feature à plus forte valeur visible de M4.

4. **Noms ambigus comme clé** : `satellite_name VARCHAR(100)` est instable (ex : `STARLINK-1234` vs `STARLINK-1234 (DEB)`). Le `NORAD_CAT_ID` extrait de la ligne 1 TLE est l'identifiant stable universel — utilisé comme clé dès ce plan.

5. **Race condition OrbitalHistoryJob/FetchJob** : deux jobs séparés sur la même source de données. Résolu en passant par un `ApplicationEventPublisher` Spring — `FetchCelesTrackTLEJob` publie un `TleCatalogRefreshedEvent` consommé par `OrbitalHistoryJob`.

6. **Smile ML 3.x + Java 17** : Isolation Forest nécessite BLAS/LAPACK natif — risque d'`InaccessibleObjectException` en runtime. **Décision** : utiliser uniquement le **Z-score glissant** (implémentation Java pure, zéro dépendance native) en Phase 2. Isolation Forest sera réévalué en M6 quand plusieurs semaines de données seront accumulées.

7. **Règles métier avant ML** : une détection par seuils configurables (delta altitude, delta inclinaison) produit de la valeur immédiatement, est interprétable, et n'a pas de cold start. Elle précède la couche Smile en Phase 2 (4.6 → 4.7).

8. **Filtres conjunctions manquants** : `GET /conjunction/alerts` renvoie une liste paginée brute sans aucun filtre. Correctif simple (Specification JPA) ajouté en 4.5.

---

## Features non prévues — ajoutées à M4

| Feature | Valeur ajoutée | Complexité |
|---------|----------------|------------|
| **Graphes historique orbital** (Chart.js / ng2-charts) | Feature la plus visible de M4 — sans elle l'historique est invisible | M |
| **Page "Profil satellite" `/satellite/:name`** | Point d'entrée UX unifié par satellite | M |
| **`noradId` comme clé primaire** de `orbital_history` | Stabilité des données, prerequis pour le RAG M5 | S |
| **Filtre/recherche alertes conjunctions** | Rend les alertes exploitables (oubli M3) | S |
| **Export CSV** de l'historique orbital | Valeur pour l'analyse externe, trivial | S |
| **`TleAgeWarning`** (badge jaune si TLE > 7 jours) | Qualité de données, crédibilité scientifique | S |
| **`GET /satellite/:noradId/summary`** | Agrège tout en un appel — prerequis pour le RAG | S |
| **Règles métier explicites avant Smile** | Valeur immédiate, pas de cold start, valide le patron AnomalyAlert | S |

---

## Architecture cible

```
[Angular]
  SatelliteProfilePageComponent (/satellite/:name)
    ├── OrbitalChartComponent   → graphes altitude périgée/apogée + inclinaison
    ├── AnomalyListComponent    → alertes d'anomalies liées au satellite
    └── ConjunctionListComponent→ conjunctions récentes liées au satellite

  AlertBadgeComponent (existant)
    └── polling /conjunction/alerts/unread + /anomaly/alerts/unread (badge combiné)

  AlertPanelComponent (existant, étendu)
    └── onglets "Conjunctions" / "Anomalies"

[Spring Boot]
  FetchCelesTrackTLEJob (existant)
    └── publie TleCatalogRefreshedEvent → OrbitalHistoryJob

  OrbitalHistoryJob (@EventListener)
    └── OrbitalElementsExtractor → OrbitalHistoryRepository

  AnomalyScanJob (@Scheduled toutes les heures)
    └── AnomalyDetectionService
          ├── Phase 1 : règles métier (seuils configurables)
          └── Phase 2 : Z-score glissant (Smile ML, si >= minHistory points)

  REST:
    GET /api/v1/orbital-history/{noradId}?days=30
    GET /api/v1/orbital-history/{noradId}/latest
    GET /api/v1/satellite/{noradId}/summary
    GET /api/v1/orbital-history/{noradId}/export?format=csv
    GET /api/v1/anomaly/alerts (paginé + filtres)
    GET /api/v1/anomaly/alerts/unread
    PUT /api/v1/anomaly/alerts/{id}/ack
    GET /api/v1/conjunction/alerts?sat=&from=&to=&maxKm= (filtres ajoutés)
```

---

## Plan d'action

### Étape 4.1 — `OrbitalElementsExtractor` (service pur Orekit)
**Objectif** : Parser les 2 lignes TLE via `org.orekit.propagation.analytical.tle.TLE` et retourner les 6 paramètres Keplériens + `noradId`.

**Classes à créer** :
- `OrbitalElements` (record DTO) :
  ```java
  record OrbitalElements(
    int     noradId,
    String  satelliteName,
    Instant epochTle,
    double  semiMajorAxisKm,     // a = (μ/n²)^(1/3) via Constants.WGS84_EARTH_MU
    double  eccentricity,         // e
    double  inclinationDeg,       // i
    double  raanDeg,              // RAAN Ω
    double  argOfPerigeeDeg,      // ω
    double  meanMotionRevDay,     // n (rad/s → rev/day)
    double  altitudePerigeeKm,    // a*(1-e) - R_Earth
    double  altitudeApogeeKm      // a*(1+e) - R_Earth
  ) {}
  ```
- `OrbitalElementsExtractor` (@Service) : `OrbitalElements extract(String name, String line1, String line2)`

**Calculs clés** :
```java
TLE tle = new TLE(line1, line2);
double n     = tle.getMeanMotion(); // rad/s
double mu    = Constants.WGS84_EARTH_MU; // m³/s²
double a     = Math.cbrt(mu / (n * n)); // en mètres
double aKm   = a / 1000.0;
double Re    = Constants.WGS84_EARTH_EQUATORIAL_RADIUS / 1000.0; // km
double periKm = aKm * (1 - tle.getE()) - Re;
double apoKm  = aKm * (1 + tle.getE()) - Re;
```

**Dépendances** : Orekit déjà présent — zéro nouvelle dépendance Maven.  
**Critère de validation** : `OrbitalElementsExtractorTest` — ISS TLE → `a ≈ 6780 km`, `i ≈ 51.6°`, `e < 0.001`, `altitudePerigee ≈ 400 km`.

---

### Étape 4.2 — Entité `OrbitalHistory` + migration Flyway V2
**Objectif** : Persister les paramètres Keplériens, avec `norad_cat_id` comme identifiant stable.

**Classes à créer** :
- `OrbitalHistory` (entité JPA, `@Table(name = "orbital_history")`) :
  ```java
  @Entity
  class OrbitalHistory {
    @Id @GeneratedValue Long id;
    int    noradId;
    String satelliteName;
    Instant fetchedAt;
    double semiMajorAxisKm;
    double eccentricity;
    double inclinationDeg;
    double raanDeg;
    double argOfPerigeeDeg;
    double meanMotionRevDay;
    double altitudePerigeeKm;
    double altitudeApogeeKm;
  }
  ```
- `OrbitalHistoryRepository` :
  - `findByNoradIdOrderByFetchedAtDesc(int noradId, Pageable p)`
  - `findByNoradIdAndFetchedAtBetween(int noradId, Instant from, Instant to)`
  - `countByNoradId(int noradId)`
  - `deleteByFetchedAtBefore(Instant cutoff)` — pour la purge TTL

**Fichier à créer** : `V2__create_orbital_history.sql`
```sql
CREATE TABLE orbital_history (
  id                  BIGSERIAL PRIMARY KEY,
  norad_id            INTEGER NOT NULL,
  satellite_name      VARCHAR(120),
  fetched_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  semi_major_axis_km  DOUBLE PRECISION,
  eccentricity        DOUBLE PRECISION,
  inclination_deg     DOUBLE PRECISION,
  raan_deg            DOUBLE PRECISION,
  arg_of_perigee_deg  DOUBLE PRECISION,
  mean_motion_rev_day DOUBLE PRECISION,
  altitude_perigee_km DOUBLE PRECISION,
  altitude_apogee_km  DOUBLE PRECISION
);
CREATE INDEX idx_orbital_history_norad_time ON orbital_history (norad_id, fetched_at DESC);
```

**Dépendances** : étape 4.1 terminée.  
**Critère de validation** : migration Flyway sans erreur H2 et PostgreSQL ; test `@DataJpaTest` sur `OrbitalHistoryRepository`.

---

### Étape 4.3 — `OrbitalHistoryJob` (via event listener)
**Objectif** : Après chaque fetch TLE réussi, extraire et persister les paramètres orbitaux. Passer par `ApplicationEventPublisher` pour éviter la race condition entre les deux jobs.

**Classes à créer/modifier** :
- `TleCatalogRefreshedEvent` (record) : `String catalogName`, `List<TleEntry> entries`
- `FetchCelesTrackTLEJob` (modifier) : après le stockage en `TleService`, publier `applicationEventPublisher.publishEvent(new TleCatalogRefreshedEvent(catalogName, entries))`
- `OrbitalHistoryJob` (@Component, @EventListener) :
  ```java
  @EventListener
  @Async
  public void onCatalogRefreshed(TleCatalogRefreshedEvent event) {
    // Itère sur les TleEntry
    // Appelle orbitalElementsExtractor.extract(entry)
    // Skip silencieux si extraction échoue (TLE malformé)
    // Persiste via orbitalHistoryRepository.save()
  }
  ```
- **Purge TTL** : dans ce même job, appeler `repository.deleteByFetchedAtBefore(Instant.now().minus(retentionDays, DAYS))` — configurable via `orbital.history.retention-days=90`.

**Garde-fous** :
- Skip silencieux si extraction échoue (TLE malformé)
- Log `WARN` si > 5% d'échecs sur le batch

**Critère de validation** : `OrbitalHistoryJobTest` — publier un `TleCatalogRefreshedEvent` avec 3 TLEs → 3 lignes en base.

---

### Étape 4.4 — `OrbitalHistoryController` + endpoints REST
**Objectif** : Exposer l'historique orbital pour Angular et le futur RAG M5.

**Classes à créer** :
- `SatelliteSummary` (DTO record) : `noradId`, `name`, `tleEpoch`, `tleLine1`, `tleLine2`, `tleAgeHours`, `latestElements: OrbitalElements`, `recentConjunctions: List<ConjunctionAlert>`, `textSummary: String` (champ texte lisible pour l'embedding RAG)
- `OrbitalHistoryController` :
  - `GET /api/v1/orbital-history/{noradId}?days=30` → `List<OrbitalElements>`
  - `GET /api/v1/orbital-history/{noradId}/latest` → `OrbitalElements`
  - `GET /api/v1/satellite/{noradId}/summary` → `SatelliteSummary`
  - `GET /api/v1/orbital-history/{noradId}/export?format=csv` → `ResponseEntity<byte[]>` + `Content-Disposition: attachment; filename=orbital-history-{noradId}.csv`

> **Note** : Le champ `textSummary` dans `SatelliteSummary` est intentionnel pour M5 — il sera la string indexée dans PgVector par le RAG.

**Critère de validation** : MockMvc → `GET /orbital-history/25544?days=7` → 200 + JSON array.

---

### Étape 4.5 — Filtres sur `GET /conjunction/alerts` (complément M3)
**Objectif** : Rendre les alertes conjunctions exploitables via des filtres.

**Classes à modifier** :
- `ConjunctionAlertRepository` : étendre `JpaSpecificationExecutor<ConjunctionAlert>`
- `ConjunctionController.getAlerts()` : nouveaux `@RequestParam` optionnels :
  - `sat` (String, filtre LIKE sur `nameSat1` ou `nameSat2`)
  - `from` (ISO-8601)
  - `to` (ISO-8601)
  - `maxKm` (Double)
- `ConjunctionSpecification` : factory statique de `Specification<ConjunctionAlert>`

**Critère de validation** : test MockMvc `GET /alerts?sat=ISS&maxKm=5` → résultats filtrés.

---

### Étape 4.6 — `AnomalyDetectionService` Phase 1 : règles métier
**Objectif** : Détecter les anomalies par seuils configurables sans dépendance ML. Valeur immédiate, zéro cold start.

**Classes à créer** :
- `AnomalyType` (enum) : `ALTITUDE_CHANGE`, `INCLINATION_CHANGE`, `RAAN_DRIFT`, `ECCENTRICITY_CHANGE`, `STATISTICAL`
- `AnomalySeverity` (enum) : `LOW`, `MEDIUM`, `HIGH`
- `AnomalyAlert` (entité JPA, `@Table(name = "anomaly_alert")`) :
  ```java
  @Entity
  class AnomalyAlert {
    @Id @GeneratedValue Long id;
    int          noradId;
    String       satelliteName;
    Instant      detectedAt;
    AnomalyType  type;
    AnomalySeverity severity;
    String       description;  // "Altitude variation of +12.3 km detected"
    boolean      acknowledged;
  }
  ```
- `AnomalyAlertRepository` :
  - `findByAcknowledgedFalseOrderByDetectedAtDesc()`
  - `existsByNoradIdAndTypeAndDetectedAtAfter(int noradId, AnomalyType type, Instant after)` — déduplication
- `AnomalyDetectionService` : méthode `List<AnomalyAlert> detectRuleBased(int noradId)`
  - Récupère les N derniers snapshots (`orbital_history`)
  - Calcule les deltas sur la fenêtre
  - Compare aux seuils configurables :
    ```properties
    anomaly.threshold.altitude-km=10
    anomaly.threshold.inclination-deg=0.5
    anomaly.threshold.raan-deg=1.0
    anomaly.threshold.eccentricity=0.001
    ```

**Fichier à créer** : `V3__create_anomaly_alert.sql`
```sql
CREATE TABLE anomaly_alert (
  id             BIGSERIAL PRIMARY KEY,
  norad_id       INTEGER NOT NULL,
  satellite_name VARCHAR(120),
  detected_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  type           VARCHAR(30) NOT NULL,
  severity       VARCHAR(10) NOT NULL,
  description    TEXT,
  acknowledged   BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_anomaly_norad_type ON anomaly_alert (norad_id, type, detected_at DESC);
```

**Critère de validation** : test unitaire pur — injecter 2 snapshots avec delta altitude > seuil → `detectRuleBased()` retourne 1 `AnomalyAlert` de type `ALTITUDE_CHANGE`. Test cold start : 0 snapshots → liste vide, aucune exception.

---

### Étape 4.7 — `AnomalyDetectionService` Phase 2 : Z-score glissant (Smile ML)
**Objectif** : Détecter les anomalies statistiques via Z-score sur fenêtre mobile — uniquement si assez de données.

**Dépendance Maven à ajouter** dans `pom.xml` :
```xml
<dependency>
    <groupId>com.github.haifengl</groupId>
    <artifactId>smile-core</artifactId>
    <version>3.1.1</version>
</dependency>
```

**JVM args** — ajouter dans le plugin `maven-surefire-plugin` (compatibilité Java 17) :
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
    </configuration>
</plugin>
```

**Algorithme** : Z-score = `(valeur - moyenne_fenêtre) / stddev_fenêtre` — score > seuil configurable (`anomaly.ml.zscore-threshold=3.0`) → anomalie. Implémentation Java pure, sans BLAS natif (pas d'Isolation Forest pour éviter les problèmes de modules Java 17).

**Méthode ajoutée** dans `AnomalyDetectionService` :
- `List<AnomalyAlert> detectZScore(int noradId, int windowSize)` — skip si `countByNoradId < anomaly.ml.min-history` (défaut : 30)

**Critère de validation** :
- Test — série de 30 points avec 1 outlier injecté → détecté avec score > 3.0
- Test cold start — < 30 points → aucune alerte, aucune exception, log `INFO "Insufficient history"`

---

### Étape 4.8 — `AnomalyScanJob` + `AnomalyController`
**Objectif** : Orchestrer les scans périodiques et exposer les alertes d'anomalies.

**Classes à créer** :
- `AnomalyScanJob` :
  - `@Scheduled(fixedDelayString = "${anomaly.scan.delay-ms:3600000}")`
  - `@ConditionalOnProperty(name = "anomaly.scan.enabled", matchIfMissing = true)`
  - Itère sur tous les `noradId` distincts de `orbital_history`
  - Appelle `detectRuleBased()` et `detectZScore()` pour chaque satellite
  - Déduplique via `existsByNoradIdAndTypeAndDetectedAtAfter()` (fenêtre ±6h)
  - Persiste les nouvelles alertes, log `WARN` pour chaque nouvelle alerte
- `AnomalyController` :
  - `GET /api/v1/anomaly/alerts` (paginé + filtres : `noradId`, `type`, `from`, `to`, `severity`)
  - `GET /api/v1/anomaly/alerts/unread` → `List<AnomalyAlert>`
  - `PUT /api/v1/anomaly/alerts/{id}/ack` → 204 / 404

**Critère de validation** : `AnomalyControllerTest` MockMvc — GET 200, PUT ack 204/404 ; test `AnomalyScanJobTest` — mock `AnomalyDetectionService` → vérifie la déduplication.

---

### Étape 4.9 — `spring-ai-bom` dans `pom.xml`
**Objectif** : Introduire le BOM Spring AI pour détecter les conflits de dépendances avant M5.

>  **Point de vigilance** : Spring AI 1.0.0 GA cible Spring Boot 3.x. Avec Spring Boot 4.0.3, il faudra probablement utiliser Spring AI 1.1.x+ ou la branche compatible Boot 4. Vérifier la matrice de compatibilité avant d'ajouter la dépendance.

**Fichier à modifier** : `pom.xml` — ajouter `<dependencyManagement>` avec `spring-ai-bom` (version à déterminer selon compatibilité Boot 4.0.3).

**Critère de validation** : `mvn dependency:tree` sans conflit ; `mvn test` toujours vert.

---

### Étape 4.10 — Frontend : page "Profil satellite" `/satellite/:name`
**Objectif** : Vue complète par satellite — point d'entrée UX unifié.

**Dépendance Angular à installer** :
```bash
npm install chart.js ng2-charts
```

**Classes/composants à créer** :
- `OrbitalHistoryService` (Angular service) :
  - `getHistory(noradId: number, days: number): Observable<OrbitalElements[]>`
  - `getSummary(noradId: number): Observable<SatelliteSummary>`
  - `exportCsv(noradId: number): Observable<Blob>`
- `AnomalyService` (Angular service) :
  - `getAlerts(params?): Observable<AnomalyAlertPage>`
  - `getUnreadAlerts(): Observable<AnomalyAlert[]>`
  - `acknowledge(id: number): Observable<void>`
- `OrbitalChartComponent` :
  - Graphe 1 : altitude périgée/apogée vs temps (`Chart.js`, type `line`)
  - Graphe 2 : inclinaison vs temps
  - Graphe 3 (secondaire, accordéon) : RAAN drift, excentricité
  - Affiche un indicateur de l'âge du TLE (`tleAgeHours`) — badge jaune si > 168h (7 jours)
- `SatelliteProfilePageComponent` :
  - Route `/satellite/:name`
  - Layout : header (nom, NORAD ID, TLE epoch, âge TLE), graphes historique, section anomalies, section conjunctions liées
  - Bouton "Exporter CSV" → `OrbitalHistoryService.exportCsv()`
- `satellite.model.ts` : interfaces `OrbitalElements`, `SatelliteSummary`, `AnomalyAlert`, `AnomalyAlertPage`

**Fichiers à modifier** :
- `app.routes.ts` : `{ path: 'satellite/:name', component: SatelliteProfilePageComponent }`
- `MapPageComponent` : bouton "Voir profil" sur les marqueurs → `/satellite/:name`
- `ConjunctionPageComponent` : lien "Profil satellite" sur les alertes

**Critère de validation** : naviguer depuis `/map` → clic marqueur → "Voir profil" → `/satellite/ISS (ZARYA)` → graphes visibles avec données réelles.

---

### Étape 4.11 — Frontend : badge anomalies + extension `AlertPanelComponent`
**Objectif** : Intégrer les alertes d'anomalies dans la mécanique de notification existante.

**Classes à modifier** :
- `AlertBadgeComponent` : polling sur `/anomaly/alerts/unread` en plus de `/conjunction/alerts/unread` — badge total combiné avec `(conjunctions + anomalies)` non lus
- `AlertPanelComponent` : onglets "Conjunctions" / "Anomalies" — même mécanique de code couleur
- `MapPageComponent` : mise à jour `AlertBadgeComponent` si besoin

**Critère de validation** : badge affiche le total conjunction + anomalies non lues.

---

## Ordre d'exécution recommandé

```
4.1  OrbitalElementsExtractor (service pur Orekit)
  └→ fondation scientifique, bloque tout le reste

4.2  OrbitalHistory entité + migration Flyway V2
  └→ sans la table, rien ne peut être persisté

4.3  OrbitalHistoryJob (EventListener sur FetchCelesTrackTLEJob)
  └→ lance l'accumulation des données dès le déploiement

4.4  OrbitalHistoryController + GET /summary + export CSV
  └→ les données sont maintenant exploitables par Angular et le RAG M5

4.5  Filtres alertes conjunctions (Specification JPA — complément M3)
  └→ petit effort, gros gain UX, indépendant des autres étapes

4.6  AnomalyDetectionService Phase 1 (règles métier)
  └→ valeur immédiate sans cold start, valide le patron AnomalyAlert

4.7  AnomalyDetectionService Phase 2 (Z-score Smile ML)
  └→ seulement après accumulation de données (guard minHistory=30)

4.8  AnomalyScanJob + AnomalyController
  └→ dépend de 4.6 et 4.7

4.9  spring-ai-bom dans pom.xml
  └→ peut être fait à tout moment — idéalement avant 4.7 pour détecter
     les conflits Smile/Spring AI ensemble

4.10  Frontend SatelliteProfilePage + OrbitalChartComponent
  └→ dépend de 4.4 (API historique opérationnelle)

4.11  Frontend badge anomalies + AlertPanel étendu
  └→ dépend de 4.8 (endpoints anomalies opérationnels)
```

---

## Critères de validation globaux du Milestone 4

1. **Accumulation de données** : après 24h de fonctionnement, `orbital_history` contient ≥ 4 snapshots (4 fetches × 6h) pour chaque satellite — vérifiable via `GET /api/v1/orbital-history/25544?days=1`.

2. **Graphes orbitaux visibles** : la page `/satellite/ISS (ZARYA)` affiche les graphes altitude périgée/apogée et inclinaison avec des données réelles, sans erreur console Angular.

3. **Détection d'anomalie fonctionnelle** : en injectant deux snapshots avec delta altitude > seuil via un test `@SpringBootTest`, le `AnomalyScanJob` persiste une `AnomalyAlert` de type `ALTITUDE_CHANGE` et elle apparaît dans le badge (non lue).

4. **Cold start safe** : avec H2 vide au démarrage, `OrbitalHistoryJob`, `AnomalyDetectionService` et `AnomalyScanJob` ne lancent aucune exception — logs `INFO` uniquement.

5. **`mvn test` vert** : tous les tests existants (23 de M3 + nouveaux M4 ≥ 15 tests) passent avec `smile-core` et `spring-ai-bom` dans le classpath.

---

## Points de vigilance pour M5

### 1. `noradId` comme clé universelle — obligatoire
M5 introduira le catalogue de débris (~27 000 objets). Si `orbital_history` et `anomaly_alert` utilisent `satellite_name` comme clé, les jointures seront impossibles. Le `norad_cat_id` est la clé de tout le référentiel spatial public (SATCAT, Space-Track, CelesTrak) — il est utilisé comme clé dans ce plan M4.

### 2. `textSummary` dans `SatelliteSummary` — prerequis RAG
Le champ `textSummary` (String lisible) ajouté à `SatelliteSummary` en 4.4 est exactement ce que `OrbitWatchRagService` indexera via PgVector en M5. Il doit être complet et en langage naturel :
```
"ISS (ZARYA), NORAD 25544, altitude 408-412 km, inclinaison 51.64°,
dernière conjunction détectée à 3.2 km le 2026-04-02T14:33Z"
```

### 3. PgVector — ne pas bloquer son activation
M5 utilisera `CREATE EXTENSION vector` sur la même base PostgreSQL. Aucune migration Flyway M4 ne doit rendre cette extension impossible à activer. Vérifier que le user DB a les droits `SUPERUSER` ou `CREATE EXTENSION`.

### 4. Volume `orbital_history` avec les débris
Avec 500 satellites × 4 fetches/jour = 2 000 lignes/jour → acceptable.  
Avec débris M5 (~27 000 objets) × 4 fetches/jour = **108 000 lignes/jour** → la table explose en 2 semaines sans politique de rétention. Le TTL configurable `orbital.history.retention-days=90` ajouté en 4.3 est obligatoire, pas optionnel.

### 5. Tests `AnomalyDetectionService` — tests purs, pas de BDD
Le service de détection doit être testé en isolation totale (zéro dépendance Spring, zéro BDD) avec des données synthétiques injectées en dur — même patron que `ConjunctionService` (logique pure, pas de `@Autowired` dans la méthode métier). Les tests fragiles qui dépendent de données accumulées sont à proscrire.

### 6. Compatibilité Spring AI / Spring Boot 4.0.3
Spring AI 1.0.0 GA cible Spring Boot 3.x. Avec Spring Boot 4.0.3 (basé sur Spring Framework 7.x), il faudra vérifier la version Spring AI compatible. Préférer une version Spring AI qui référence Spring Boot 4.x dans sa matrice de compatibilité avant d'importer le BOM en 4.9.

---

*Document de référence pour le développement de la Milestone 4.*

