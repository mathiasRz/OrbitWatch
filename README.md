# OrbitWatch

Plateforme web de surveillance spatiale — propagation d'orbites, détection de rapprochements, analyse d'évolution orbitale.

---

## Stack

| Couche | Techno |
|--------|--------|
| Backend | Java 17, Spring Boot 4.0.3, Orekit 13.1.4 |
| Base de données | PostgreSQL 15+ (dev & prod) / H2 en mémoire (tests) |
| Frontend | Angular 21 standalone, Leaflet 1.9, Chart.js |
| Migrations BDD | Flyway (auto au démarrage) |

---

## Prérequis

- Java 17+
- Maven 3.9+
- Node.js 20+ / npm
- **PostgreSQL 15+** installé et démarré

---

## 1. Initialisation de la base de données

À faire **une seule fois**, avant le premier démarrage :

```bash
psql -U postgres -f orbitwatch-core/src/main/resources/db/init/create_db.sql
```

Ce script crée l'utilisateur `orbitwatch` et la base `orbitwatch`.  
Les tables sont ensuite créées automatiquement par **Flyway** au démarrage de l'application.

---

## 2. Configuration des secrets

créer et éditer `application-secrets.properties` avec mon login/mot de passe Space-Track.org.

---

## 3. Démarrage

### Backend
```bash
cd orbitwatch-core
mvn spring-boot:run
# ou lancer OrbitWatchApplication depuis IntelliJ
```
API disponible sur `http://localhost:8080`

### Frontend
```bash
cd orbitwatch-ui
npm install
npm start
```
UI disponible sur `http://localhost:4200`

---

## 4. Schéma de la base

Les migrations Flyway sont dans `orbitwatch-core/src/main/resources/db/migration/` :

| Version | Table | Description |
|---------|-------|-------------|
| V1 | `conjunction_alert` | Alertes de rapprochements |
| V2 | `orbital_history` | Historique des paramètres Keplériens |
| V3 | `anomaly_alert` | Alertes d'anomalies orbitales |

