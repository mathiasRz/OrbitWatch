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

| Milestone | Objectif | Statut | Notes / Actions |
|-----------|----------|--------|----------------|
| 1 | Moteur orbital minimal | TERMINE | Intégration Orekit, initialisation orekit-data, propagation TLE test |
| 2 | Ground track 2D | À venir | Création API REST et affichage sur carte Leaflet |
| 3 | Détection de rapprochements | À venir | Module de calcul distance 3D entre satellites |
| 4 | Analyse d’évolution orbitale | À venir | Suivi paramètres orbitaux, détection de dérive ou décrochage |
| 5 | Surveillance des débris | À venir | Heatmap orbitale, analyse zones à forte densité |
| 6 | Version vitrine | À venir | Application complète avec visualisation 3D et analyses |

---

## 3. Décisions techniques

- **Backend** : Java 17, Spring Boot, Maven
- **Bibliothèque scientifique** : Orekit 12.0
- **Base de données** : PostgreSQL (pour stockage TLE et résultats d’analyse)
- **Frontend** : Angular, visualisation 3D avec CesiumJS (v1 pour test, v2 pour vitrine)  
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

### 2026-03-07
- Étape 1.1 : nettoyage `pom.xml` (dépendances de test invalides → `spring-boot-starter-test`, ajout `h2`)
- Étape 1.2 : `application.properties` configuré (port 8080, profil `dev`) + `application-dev.properties` (H2 en mémoire) + `SecurityConfig` permissif sur profil `dev`
- Étape 1.3 : `OrekitInitializer` corrigé — `ClassPathResource` à la place du chemin relatif fragile
- Étape 1.4 : DTO `SatellitePosition` créé (record Java immuable : name, epoch, lat, lon, alt, x, y, z)
- Étape 1.5 : `PropagationService` implémenté — `propagate()` et `groundTrack()` via `TLEPropagator` + conversion TEME→ITRF→GeodeticPoint
- Étape 1.6 : `OrbitController` exposé — `GET /api/v1/orbit/position` et `GET /api/v1/orbit/groundtrack`
- Étape 1.7 : `PropagationServiceTest` — 12 tests unitaires (position instantanée + ground track, validations physiques ISS)




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