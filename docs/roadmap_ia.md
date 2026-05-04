# Roadmap IA — OrbitWatch

---

## Vue d'ensemble

OrbitWatch est un candidat naturel à l'IA : données physiques structurées (TLEs, positions, paramètres orbitaux), séries temporelles, événements détectables statistiquement. La roadmap ci-dessous s'insère dans les milestones existants sans rupture de stack.

---

## Phase 1 — Fondations données (déjà en cours)

**Milestone 3 / 4**

- Ingestion TLE toutes les 6 h (`FetchCelesTrackTLEJob`)
- API REST propre (positions, conjunctions)
- Stockage historique en BDD (`conjunction_alert`, futur `orbital_history`)

> Ces fondations sont **indispensables** avant tout ML — pas de modèle sans données historiques.

---

## Phase 2 — Détection d'anomalies ML

**Milestone 4 (à planifier)**

### Objectif
Détecter automatiquement les comportements orbitaux anormaux :
- Variation anormale d'altitude (manœuvre ou rentrée atmosphérique imminente)
- Changement de plan orbital (RAAN ou inclinaison qui dérivent)
- Décélération anormale (traînée atmosphérique accrue → tempête solaire ?)

### Ce qu'il faut mettre en place

1. **Table `orbital_history`** : historiser les paramètres orbitaux à chaque fetch TLE
   ```sql
   CREATE TABLE orbital_history (
     id           BIGSERIAL PRIMARY KEY,
     satellite_name VARCHAR(100),
     fetched_at   TIMESTAMP,
     semi_major_axis_km DOUBLE PRECISION,
     eccentricity       DOUBLE PRECISION,
     inclination_deg    DOUBLE PRECISION,
     raan_deg           DOUBLE PRECISION,
     arg_of_perigee_deg DOUBLE PRECISION,
     mean_motion_rev_day DOUBLE PRECISION
   );
   ```

2. **`OrbitalHistoryJob`** : job `@Scheduled` qui extrait les paramètres orbitaux de chaque TLE au moment du fetch et les persiste

3. **`AnomalyDetectionService`** : détection de déviations statistiques sur les séries temporelles
   - Algorithme : **Isolation Forest** ou **Z-score glissant** (fenêtre mobile de N dernières observations)
   - Bibliothèque Java : **Smile ML** (`com.github.haifengl:smile-core`) — dépendance Maven directe, 0 micro-service externe
   - Seuil configurable par paramètre orbital

4. **`AnomalyAlert`** : entité JPA, même patron que `ConjunctionAlert`

5. **Endpoint** : `GET /api/v1/anomaly/alerts` + badge IHM (même mécanique que conjunctions)

### Dépendance Maven à ajouter
```xml
<dependency>
    <groupId>com.github.haifengl</groupId>
    <artifactId>smile-core</artifactId>
    <version>3.1.1</version>
</dependency>
```

---

## Phase 3 — Assistant IA OrbitWatch (RAG)

**Milestone 5 ou 6 (à planifier)**

### Objectif
Un assistant conversationnel embarqué dans OrbitWatch qui répond à des questions comme :
- *"Quels satellites ont eu une anomalie de trajectoire cette semaine ?"*
- *"Explique-moi pourquoi l'ISS a changé d'orbite le 15 mars"*
- *"Donne-moi une analyse des conjunctions détectées sur les débris Cosmos"*

### Stack technique

| Composant | Choix | Justification |
|-----------|-------|---------------|
| Framework LLM | **Spring AI** (`spring-ai-bom`) | Même paradigme Spring Boot, 0 rupture de stack |
| LLM dev | **Ollama + Llama 3** (local) | Gratuit, 0 dépendance réseau en dev |
| LLM prod | **OpenAI GPT-4o** ou **Mistral** | Spring AI abstrait les deux avec le même code |
| Vector store | **PgVector** (extension PostgreSQL) | 0 nouvelle infra — juste `CREATE EXTENSION vector` sur la BDD existante |
| Données indexées | Historique orbital, alertes conjunction, anomalies | Données déjà en BDD depuis Phase 1 & 2 |

### Dépendances Maven à ajouter
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- OpenAI ou Ollama selon le profil -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>
    <!-- Vector store PgVector -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

### Architecture RAG
```
[Angular]
  ChatComponent  →  saisie question utilisateur
                 ←  réponse streamée (SSE)

[Spring Boot]
  POST /api/v1/ai/chat
    → OrbitWatchRagService
        1. Embed la question (OpenAI Embeddings / Ollama)
        2. Recherche vectorielle dans PgVector (top-k alertes/historiques pertinents)
        3. Construit le prompt : contexte orbital + question
        4. Appelle le LLM → stream de la réponse
```

---

## Phase 4 — Agent autonome spatial

**Milestone 6 ou 7 (à planifier)**

### Objectif
Un agent qui observe, raisonne et agit de manière autonome :
1. **Observe** : surveille TLEs, conjunctions, anomalies en continu
2. **Raisonne** : "ce satellite s'approche à 2.3 km dans 4h, opérateur Starlink → probabilité de manœuvre évasive ?"
3. **Agit** : envoie une alerte enrichie, propose une fenêtre de manœuvre, génère un rapport automatique

### Stack agent
- **Spring AI Tool Calling** : les APIs REST OrbitWatch deviennent des "tools" appelables par l'agent
- **LangGraph4J** ou **Spring AI Advisors** pour l'orchestration multi-étapes
- **Mémoire persistante** : l'agent mémorise les événements passés via PgVector (déjà en place depuis Phase 3)

### Exemple de tools exposés à l'agent
```java
@Tool("Récupère les conjunctions critiques des dernières N heures")
List<ConjunctionAlert> getRecentConjunctions(int hours) { ... }

@Tool("Récupère l'historique orbital d'un satellite")
List<OrbitalHistory> getOrbitalHistory(String name, int days) { ... }

@Tool("Calcule la distance minimale entre deux satellites sur 24h")
ConjunctionReport analyzeConjunction(String sat1, String sat2) { ... }
```

---

## Récapitulatif de la roadmap intégrée

| Milestone | Contenu | IA |
|-----------|---------|-----|
| M3 | Conjunctions + BDD + notifications IHM | fondations données |
| M4 | Historique orbital + **anomalies ML** (Smile) | 1er module IA |
| M5 | Débris + 3D CesiumJS + **assistant RAG v1** (Spring AI) | RAG v1 |
| M6 | **Agent autonome v1** (Spring AI Tool Calling) + mémoire conversationnelle + ground track 3D | Agent opérationnel |

---

## Décisions techniques actées

- **Smile ML** pour la Phase 2 (pas de micro-service Python — même JVM, même déploiement)
- **Spring AI** comme framework IA principal (même paradigme que Spring Boot)
- **PgVector** comme vector store (extension PostgreSQL déjà utilisé en prod — 0 nouvelle infra)
- **Ollama en dev** (Llama 3 local, gratuit) + **OpenAI en prod** (même code, profil Spring différent)
- Introduire `spring-ai-bom` dans le `pom.xml` dès M4 pour se familiariser, même si l'utilisation réelle commence en M5

---

*Document à reprendre lors de la planification de M4.*

