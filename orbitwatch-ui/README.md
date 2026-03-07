# OrbitWatch — Frontend

Interface web de surveillance spatiale construite avec **Angular 21** (standalone components) et **Leaflet**.

---

## Démarrage rapide

```bash
npm install
npm start        # http://localhost:4200
```

Le backend Spring Boot doit tourner sur `http://localhost:8080`.

---

## Architecture

```
src/app/
├── models/
│   └── satellite-position.model.ts   # Interface TypeScript du DTO backend
│
├── services/
│   └── orbit.service.ts              # Appels HTTP vers /api/v1/orbit
│
├── components/                       # Composants réutilisables
│   ├── map/
│   │   ├── map.component.ts          # Logique Leaflet (init, polyline, marqueur)
│   │   ├── map.component.html        # <div> conteneur de la carte
│   │   └── map.component.scss
│   └── tle-form/
│       ├── tle-form.component.ts     # Formulaire réactif + validation TLE
│       ├── tle-form.component.html
│       └── tle-form.component.scss
│
├── pages/                            # Vues routées
│   └── orbit-page/
│       ├── orbit-page.component.ts   # Orchestrateur : form → service → carte
│       ├── orbit-page.component.html
│       └── orbit-page.component.scss
│
├── app.routes.ts                     # Routes : / → /orbit
├── app.config.ts                     # Bootstrap : router, HttpClient
└── app.ts                            # Composant racine (<router-outlet>)
```

### Flux de données

```
TleFormComponent  ──(propagate)──▶  OrbitPageComponent
                                          │
                                    OrbitService.getGroundTrack()
                                          │ HTTP GET /api/v1/orbit/groundtrack
                                          ▼
                                    MapComponent  ──[track]──▶  Leaflet Polyline
```

---

## Stack

| Outil | Version | Rôle |
|---|---|---|
| Angular | 21 | Framework — composants standalone |
| Leaflet | 1.9 | Carte 2D interactive |
| RxJS | 7.8 | Gestion des flux HTTP |
| TypeScript | 5.9 | Typage statique |
| Vitest | 4 | Tests unitaires |

---

## Scripts

```bash
npm start          # Serveur de développement (port 4200)
npm run build      # Build de production → dist/
npm test           # Tests unitaires (Vitest)
```
