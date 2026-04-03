# Plan d'implémentation — Milestone 2 : Ground Track 2D
**Date de rédaction** : 2026-03-07  

---

## Rappel de l'objectif

Afficher la trajectoire d'un satellite sur une carte 2D interactive (Leaflet) à partir d'un TLE saisi par l'utilisateur.

---

## État des acquis (Milestone 1)

- `PropagationService.groundTrack()` → liste de `SatellitePosition` (lat/lon/alt) 
- `GET /api/v1/orbit/groundtrack` → JSON prêt à consommer 
- Frontend vide 

---

## Plan d'action

### Étape 2.1 — Initialiser le projet Angular
- Générer le projet dans `orbitwatch-frontend/` (`ng new orbitwatch-frontend --routing --style=scss`)
- Installer Leaflet et ses types : `leaflet`, `@types/leaflet`
- Créer la structure de modules : `CoreModule`, `SharedModule`, `OrbitModule`

### Étape 2.2 — Service Angular `OrbitService`
- Méthode `getGroundTrack(tle1, tle2, name, duration, step)` → `Observable<SatellitePosition[]>`
- Pointer sur `http://localhost:8080/api/v1/orbit/groundtrack`
- Typer la réponse avec une interface `SatellitePosition` côté front

### Étape 2.3 — Composant `MapComponent`
- Initialiser une carte Leaflet (fond de carte OpenStreetMap)
- Tracer la ground track sous forme de `Polyline` (lat/lon de chaque point)
- Placer un marqueur sur la position actuelle (premier point de la liste)
- Gérer la destruction du composant (`ngOnDestroy`) pour éviter les fuites mémoire Leaflet

### Étape 2.4 — Composant `TleFormComponent`
- Formulaire réactif avec trois champs : `name`, `tle1`, `tle2`
- Paramètres optionnels exposés : `duration` (min, défaut 90), `step` (s, défaut 60)
- Validation : format TLE ligne 1 commence par `1 `, ligne 2 par `2 `
- À la soumission, émet un événement capturé par le composant parent `OrbitPageComponent`

### Étape 2.5 — Page `OrbitPageComponent`
- Orchestrateur : reçoit le TLE du formulaire, appelle `OrbitService`, passe les données au `MapComponent`
- Gestion des états : chargement, erreur API, succès
- Pré-remplir le formulaire avec le TLE de l'ISS pour faciliter les tests

### Étape 2.6 — CORS côté backend
- Annoter `OrbitController` avec `@CrossOrigin(origins = "http://localhost:4200")`  
  ou configurer un `CorsConfigurationSource` global dans `SecurityConfig`

### Étape 2.7 — Tests
- **Backend** : test d'intégration `OrbitControllerTest` (étape 1.8 du plan M1, à enchaîner ici) — `MockMvc` + TLE ISS → HTTP 200 + JSON valide
- **Frontend** : test unitaire `OrbitService` (mock `HttpClient`) et `TleFormComponent` (validation du formulaire)

---

## Ordre d'exécution

```
2.1 → 2.2 → 2.6 → 2.3 → 2.4 → 2.5 → 2.7
```
2.6 (CORS) doit être fait avant tout test de l'Angular contre le backend local.

---

## Critère de validation du Milestone 2

> Saisir le TLE de l'ISS dans le formulaire → cliquer "Propager" → la ground track s'affiche sur la carte Leaflet avec un marqueur sur la position à l'époque du TLE.

