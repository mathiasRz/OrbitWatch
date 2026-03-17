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
| 2 | Ground track 2D | En cours | Backend + frontend opérationnels, intégration CelesTrak, bugs de rafraichissement trajectoire à corriger |
| 3 | Détection de rapprochements | À venir | Module de calcul distance 3D entre satellites |
| 4 | Analyse d'évolution orbitale | À venir | Suivi paramètres orbitaux, détection de dérive ou décrochage |
| 5 | Surveillance des débris | À venir | Heatmap orbitale, analyse zones à forte densité |
| 6 | Version vitrine | À venir | Application complète avec visualisation 3D et analyses |

---

## 3. Décisions techniques

- **Backend** : Java 17, Spring Boot 4.0.3, Maven  
- **Bibliothèque scientifique** : Orekit 13.1.4  
- **Base de données** : PostgreSQL (prod) / H2 en mémoire (dev)  
- **Frontend** : Angular 21 (standalone), Leaflet 1.9, CesiumJS prévu en M6  
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

## 5. Suivi futur

- Documenter chaque nouvelle fonctionnalité et API  
- Valider les calculs orbitaux avant visualisation 3D  
- Noter toutes les anomalies ou incohérences scientifiques rencontrées  
- Tenir à jour les versions de dépendances scientifiques Orekit / Hipparchus  

---

## 6. Notes techniques / astuces

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