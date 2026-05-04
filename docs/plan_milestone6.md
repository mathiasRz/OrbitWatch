# Plan d'implémentation — Milestone 6 : Agent IA + Mémoire conversationnelle + Ground track 3D
**Date de rédaction** : 2026-05-04

---

## Objectif global

Transformer l'assistant RAG passif (M5) en un **agent autonome Spring AI Tool Calling** opérationnel, capable d'appeler les APIs métier OrbitWatch pour répondre à des questions complexes. Ajouter la **mémoire conversationnelle persistante** par session et le **ground track 3D** sur le globe CesiumJS.

| Feature | Périmètre |
|---------|-----------|
| **A — Agent Spring AI Tool Calling** | `OrbitWatchTools` avec `@Tool` sur les méthodes métier, `OrbitWatchAgent` remplaçant `OrbitWatchRagService`, compatibilité SSE maintenue |
| **B — Mémoire conversationnelle** | Migration Flyway V6, `JdbcChatMemory`, `sessionId` UUID côté Angular (`localStorage`), endpoints `GET/DELETE /history` |
| **C — Ground track 3D + polish UI** | Polyline orbitale sur clic satellite dans le globe CesiumJS, bouton "🤖 Analyser" depuis `SatelliteProfilePage` |

---

## Analyse critique des prérequis

### Ce qui est déjà en place

- **`OrbitWatchRagService`** — pipeline RAG complet (vectorStore top-k + ChatClient + SseEmitter) : la logique RAG est conservée, l'agent s'y ajoute
- **`ChatController`** — SSE via `SseEmitter` + `ragTaskExecutor` : seul le service appelé change (`agent.chat()` au lieu de `ragService.chat()`)
- **`ConjunctionService.analyze()`** — sans état, sans JPA, retourne un DTO : idéale en `@Tool`
- **`OrbitalHistoryRepository`** — données disponibles, à wrapper sans `Pageable`
- **`AnomalyAlertRepository`** — `findByAcknowledgedFalse()` retourne déjà une `List<AnomalyAlert>`
- **`TleService.resolveUniqueTle()`** — résolution par nom, réutilisable dans les tools
- **`GlobeComponent`** — `PointPrimitiveCollection` + `CustomDataSource` opérationnels ; `GlobeService` expose déjà `getStations()` et `getDebris()`
- **Migration V5** — `vector_store` (768 dims, HNSW cosinus) opérationnelle
- **`ChatComponent` Angular** — buffer SSE robuste, `prefill()`, `ChangeDetectionStrategy.OnPush`

### Ce qui manque / points de vigilance

1. **Sérialisation `@Tool`** : Spring AI sérialise les paramètres et retours en JSON. Ne jamais retourner une entité JPA `@Entity` directement (boucle Jackson) — utiliser des **DTOs records plats**. `Instant` → `String ISO-8601`, `Page<T>` → `List<T>` (max 10–20 éléments).
2. **Proxy Spring AOP + `@Tool`** : placer les tools dans un bean `@Component` dédié **sans** `@Transactional` pour éviter les conflits proxy CGLIB ; déléguer les appels transactionnels aux repositories/services existants.
3. **`ChatClient` dynamique** : le `MessageChatMemoryAdvisor` est stateful par `conversationId` — le `ChatClient` doit être reconfiguré par requête (`.mutate()`) plutôt qu'injecté comme singleton.
4. **`JdbcChatMemory`** : Spring AI 2.0.0-M4 ne fournit qu'`InMemoryChatMemory` — implémenter l'interface `ChatMemory` manuellement sur la table `chat_history` via `JdbcTemplate`.
5. **`analyzeConjunction` tool** : nécessite la résolution des TLEs via `TleService` ; si un satellite est inconnu, retourner un DTO avec champ `error` plutôt que lever une exception (le LLM doit voir le message d'erreur, pas un crash).
6. **Taille de contexte LLM avec tools** : 5 tools × ~200 tokens de description + résultats ≈ ~2 000 tokens d'overhead. Limiter les résultats retournés à **10 éléments max** par tool pour rester dans la fenêtre llama3.2 (4 096 tokens) / gemma3:4b.
7. **Ground track 3D** : `GET /api/v1/orbit/groundtrack` retourne des positions géodésiques en km — multiplier l'altitude par 1 000 pour les mètres Cesium. L'appel est synchrone côté Angular (`HttpClient`) mais s'exécute hors zone Angular (`NgZone.runOutsideAngular`).

---

## Architecture cible

```
[Angular]
  ChatPageComponent (/chat)
    └── ChatComponent
          ├── sessionId (UUID généré + persisté localStorage)
          ├── POST /api/v1/ai/chat  {question, sessionId}  → SSE stream
          ├── GET  /api/v1/ai/chat/history?sessionId=      → rechargement historique
          └── DELETE /api/v1/ai/chat/history?sessionId=    → bouton "Effacer"

  SatelliteProfilePageComponent (/satellite/:noradId)
    └── Bouton "🤖 Analyser avec l'agent" → /chat?q=Analyse le satellite [name]

  GlobePageComponent (/globe)
    └── GlobeComponent (CesiumJS)
          └── Clic satellite → GET /api/v1/orbit/groundtrack?name=NAME&steps=90
                             → PolylineCollection (tracé orbital cyan, toggle)

[Spring Boot]
  ChatController (modifié)
    ├── POST /api/v1/ai/chat  {question, sessionId}
    │     └── OrbitWatchAgent.chat(question, sessionId)
    │           ├── MessageChatMemoryAdvisor (JdbcChatMemory → chat_history)
    │           ├── VectorStore.similaritySearch()  ← RAG conservé
    │           └── OrbitWatchTools
    │                 ├── @Tool getRecentConjunctions(int hours)
    │                 ├── @Tool getOrbitalHistory(String name, int days)
    │                 ├── @Tool analyzeConjunction(String sat1, String sat2)
    │                 ├── @Tool getUnreadAnomalies()
    │                 └── @Tool getSatelliteSummary(String name)
    ├── GET  /api/v1/ai/chat/history?sessionId=
    └── DELETE /api/v1/ai/chat/history?sessionId=

  BDD PostgreSQL
    ├── vector_store  (V5 — existant)
    └── chat_history  (V6 — nouvelle migration)
```

---

## Plan d'action détaillé

---

### Étape 6.1 — Migration Flyway V6 : table `chat_history`
**Objectif** : Créer la table de persistance des échanges conversationnels.

**Fichier à créer** : `db/migration/V6__create_chat_history.sql`

```sql
CREATE TABLE chat_history (
    id         BIGSERIAL    PRIMARY KEY,
    session_id VARCHAR(36)  NOT NULL,
    role       VARCHAR(20)  NOT NULL,   -- 'USER' | 'ASSISTANT' | 'SYSTEM'
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_history_session ON chat_history (session_id, created_at);
```

**Points de vigilance** :
- `session_id` en `VARCHAR(36)` (UUID texte) — pas de contrainte FK, sessions anonymes.
- Index composite `(session_id, created_at)` obligatoire — la requête de rechargement d'historique `WHERE session_id = ? ORDER BY created_at` serait un sequential scan sans lui.
- Pas de purge TTL automatique en M6 — à prévoir en M7 si le volume le justifie.

**Critère de validation** :
- Migration appliquée sans erreur (`mvn flyway:migrate`).
- `SELECT count(*) FROM chat_history` retourne `0` (table vide, schéma correct).

---

### Étape 6.2 — `JdbcChatMemory` (implémentation `ChatMemory`)
**Objectif** : Persistance JDBC de la mémoire conversationnelle Spring AI.

**Classe à créer** : `service/JdbcChatMemory.java` (`@Component`, implémente `org.springframework.ai.chat.memory.ChatMemory`)

```java
// Les 3 méthodes de l'interface ChatMemory :
void add(String conversationId, List<Message> messages)
List<Message> get(String conversationId, int lastN)
void clear(String conversationId)
```

Logique :
- `add` → `INSERT INTO chat_history (session_id, role, content, created_at) VALUES (?, ?, ?, now())`
- `get` → `SELECT role, content FROM chat_history WHERE session_id = ? ORDER BY created_at DESC LIMIT ?` — puis inverser la liste pour ordre chronologique
- `clear` → `DELETE FROM chat_history WHERE session_id = ?`
- Mapping `role` : `UserMessage` → `"USER"`, `AssistantMessage` → `"ASSISTANT"`, `SystemMessage` → `"SYSTEM"` ; reconstruction inverse sur `get`

**Points de vigilance** :
- Utiliser `JdbcTemplate` (déjà disponible via `spring-boot-starter-data-jpa`) — pas d'entité JPA pour cette table.
- `lastN` limité à **50 messages** max pour ne pas dépasser la fenêtre de contexte LLM.
- `application-test.properties` : la table existe en H2 via la migration V6 — pas de mock nécessaire.

**Critère de validation** :
- `JdbcChatMemoryTest` (`@JdbcTest` H2) :
  - `add()` 3 messages → `get(id, 10)` retourne 3 messages en ordre chronologique
  - `clear()` → `get(id, 10)` retourne liste vide
  - `get(id, 2)` sur 5 messages → 2 derniers seulement

---

### Étape 6.3 — `OrbitWatchTools` : beans `@Tool`
**Objectif** : Exposer les méthodes métier OrbitWatch comme tools appelables par le LLM.

**Classe à créer** : `service/OrbitWatchTools.java` (`@Component`, **pas** `@Transactional`)

| Tool | Signature | Description `@Tool` | Source |
|------|-----------|---------------------|--------|
| `getRecentConjunctions` | `(int hours) → List<ConjunctionAlertDto>` | "Récupère les alertes de rapprochement critique des N dernières heures" | `ConjunctionAlertRepository.findByCreatedAtAfterOrderByDistanceKmAsc()` — max 10 |
| `getOrbitalHistory` | `(String name, int days) → List<OrbitalHistoryDto>` | "Retourne l'historique des paramètres orbitaux d'un satellite sur N jours" | `OrbitalHistoryRepository` — wrapper sans Pageable, max 20 entrées |
| `analyzeConjunction` | `(String sat1, String sat2) → ConjunctionSummaryDto` | "Calcule les rapprochements entre deux satellites sur les prochaines 24h" | `TleService.resolveUniqueTle()` × 2 + `ConjunctionService.analyze()` |
| `getUnreadAnomalies` | `() → List<AnomalyAlertDto>` | "Retourne les anomalies orbitales non acquittées" | `AnomalyAlertRepository.findByAcknowledgedFalseOrderByDetectedAtDesc()` — max 10 |
| `getSatelliteSummary` | `(String name) → String` | "Retourne un résumé en langage naturel du satellite (orbite, statut, anomalies récentes)" | `OrbitalHistoryController.getSummary()` via service interne |

**DTOs à créer** (records plats dans `dto/`) :
```java
record ConjunctionAlertDto(String sat1, String sat2, double distanceKm, String tca)
record OrbitalHistoryDto(String fetchedAt, double altPerigeeKm, double altApogeeKm,
                         double inclinationDeg, double eccentricity)
record AnomalyAlertDto(String satelliteName, String type, String severity,
                       String detectedAt, String description)
record ConjunctionSummaryDto(String sat1, String sat2, int eventCount,
                              Double minDistanceKm, String error)
```

**Points de vigilance** :
- `analyzeConjunction` : si `resolveUniqueTle()` lève une exception (404/409), retourner `new ConjunctionSummaryDto(sat1, sat2, 0, null, "Satellite inconnu : " + e.getMessage())` — jamais propager l'exception au LLM.
- `Instant` dans les DTOs : convertir en `String` ISO-8601 (`instant.toString()`) — Jackson ne sérialise pas `Instant` correctement par défaut sans configuration supplémentaire.
- `getOrbitalHistory` : résoudre d'abord le `noradId` via `TleService.resolveUniqueTle(name).noradId()` avant d'appeler le repository.

**Critère de validation** :
- `OrbitWatchToolsTest` (Mockito pur) :
  - `getRecentConjunctions(6)` → filtre les alertes dans la fenêtre des 6 h
  - `analyzeConjunction("ISS", "UNKNOWN")` → retourne un `ConjunctionSummaryDto` avec `error` non null (pas d'exception)
  - `getOrbitalHistory("ISS", 30)` → max 20 entrées même si la BDD en contient 200
  - `ObjectMapper.writeValueAsString(dto)` ne lève pas d'exception (sérialisation JSON valide)

---

### Étape 6.4 — `OrbitWatchAgent` : RAG + Tool Calling + mémoire
**Objectif** : Service unifié orchestrant RAG, Tool Calling et mémoire conversationnelle.

**Classe à créer** : `service/OrbitWatchAgent.java`
**Classe existante `OrbitWatchRagService`** : conservée telle quelle (pas de suppression).

**Signature publique** :
```java
public Flux<String> chat(String question, String sessionId)
```

Algorithme :
1. Construire le **contexte RAG** : `vectorStore.similaritySearch(question, topK=5, threshold=0.55)` → concaténer les `doc.getContent()` → prompt système (identique à `OrbitWatchRagService`)
2. Appeler `chatClient.prompt()` avec :
   - `.system(systemPromptWithContext)`
   - `.user(question)`
   - `.advisors(new MessageChatMemoryAdvisor(jdbcChatMemory, sessionId, 20))` ← 20 derniers messages
   - `.tools(orbitWatchTools)`
   - `.stream().content()` → `Flux<String>`

**Points de vigilance** :
- **`ChatClient` par requête** : le `MessageChatMemoryAdvisor` est paramétré avec le `sessionId` — utiliser `chatClient.mutate().build()` ou construire l'appel via le builder sans stocker de state dans le bean. Tester si Spring AI 2.0.0-M4 permet de passer l'advisor directement dans `.advisors()` sur un `ChatClient` singleton (API milestone susceptible de varier).
- **Compatibilité avec `ChatController`** : le `Flux<String>` retourné est souscrit dans le `ragTaskExecutor` par `SseEmitter` — aucun changement dans `ChatController`, juste remplacer `ragService.chat(question)` par `agent.chat(question, sessionId)`.
- **Si Tool Calling non disponible en M4** : en cas de non-compatibilité de l'API `@Tool` avec Spring AI 2.0.0-M4, replier sur un **pseudo-tool calling** : injecter les résultats des 3 outils les plus pertinents (conjunctions + anomalies) dans le prompt système directement, sans déclencher de cycle tool — dégradé acceptable pour M6.

**Critère de validation** :
- `OrbitWatchAgentTest` (Mockito + `ChatClient` mocké) :
  - Question contenant "conjunction" → `vectorStore.similaritySearch()` appelé + `chatClient.prompt()` appelé
  - `sessionId` fourni → `jdbcChatMemory.get(sessionId)` appelé (mémoire chargée)
  - Question vide → `Flux.empty()` sans exception (comportement hérité de `OrbitWatchRagService`)

---

### Étape 6.5 — Mise à jour `ChatController` : `sessionId` + endpoints mémoire
**Objectif** : Intégrer l'agent et exposer la gestion de l'historique.

**Modifications de `ChatController`** :
- `ChatRequest` : ajouter `String sessionId` (nullable — si absent, générer un UUID côté serveur)
- Remplacer l'appel `ragService.chat(question)` par `agent.chat(question, resolvedSessionId)`
- Ajouter `GET /api/v1/ai/chat/history?sessionId=` → `jdbcChatMemory.get(sessionId, 50)` mappé en `List<ChatMessageDto>`
- Ajouter `DELETE /api/v1/ai/chat/history?sessionId=` → `jdbcChatMemory.clear(sessionId)` → `204 No Content`
- S'assurer que la config CORS autorise `DELETE` (ajouter à la liste des méthodes autorisées si nécessaire)

**DTO à créer** :
```java
record ChatMessageDto(String role, String content, String createdAt) {}
```

**Critère de validation** :
- `ChatControllerTest` (MockMvc — tests existants conservés + nouveaux) :
  - `POST /chat {"question":"…", "sessionId":"uuid"}` → 200 SSE (regression test)
  - `POST /chat {"question":"…"}` sans sessionId → 200 SSE avec UUID auto-généré
  - `GET /chat/history?sessionId=unknown` → 200 + `[]`
  - `DELETE /chat/history?sessionId=uuid` → 204

---

### Étape 6.6 — `ChatComponent` Angular : `sessionId` + historique
**Objectif** : Persistance de la session et affichage de l'historique au rechargement.

**Modifications de `ChatService`** (`services/chat.service.ts`) :
- `getOrCreateSessionId(): string` — lit `localStorage.getItem('orbitwatch_session_id')` ou génère `crypto.randomUUID()` et le stocke
- Ajouter `sessionId` au body de chaque requête `POST /chat`
- `loadHistory(sessionId: string): Observable<ChatMessageDto[]>` → `GET /history?sessionId=`
- `clearHistory(sessionId: string): Observable<void>` → `DELETE /history?sessionId=`

**Modifications de `ChatComponent`** :
- `ngOnInit` : appeler `chatService.loadHistory(sessionId)` → pré-peupler `messages[]` avec les échanges précédents
- Bouton "🗑️ Nouvelle conversation" : appelle `clearHistory()` + vide `messages[]` + génère un nouveau `sessionId`
- Afficher un indicateur de session (ex : `Session #${sessionId.slice(0, 6)}`) dans le header du chat

**Points de vigilance** :
- `localStorage` : disponible uniquement côté navigateur — SSR désactivé (déjà requis par CesiumJS).
- Tronquer l'affichage au chargement si > 30 messages (ne pas rendre 50 bulles en DOM).
- Le streaming SSE existant utilise `fetch` avec body — l'ajout du `sessionId` dans le body est transparent.

**Critère de validation** :
- Rechargement de `/chat` → messages de la session précédente visibles
- Bouton "Nouvelle conversation" → historique vidé côté UI et côté BDD (`chat_history` purgé pour le `sessionId`)
- Nouvelle session (sessionId inconnu) → `GET /history` retourne `[]`, pas d'erreur

---

### Étape 6.7 — Ground track 3D sur le globe CesiumJS
**Objectif** : Afficher la trajectoire orbitale d'un satellite au clic dans le globe.

**Modifications de `GlobeComponent`** (`globe.component.ts`) :

Nouveau comportement :
1. Clic sur un satellite (billboards `CustomDataSource`) → `this.globeService.getGroundTrack(sat.name)` → `GET /api/v1/orbit/groundtrack?name=NAME&duration=90&step=60` (90 min = ~1,5 orbite ISS)
2. Mapper `SatellitePosition[]` → `Cesium.Cartesian3.fromDegrees(lon, lat, alt * 1000)[]`
3. Créer une entité `Polyline` dans `stationLayer` avec `PolylineDashMaterialProperty` (tirets cyan)
4. Au clic suivant sur le même satellite → supprimer la polyline (toggle) ; au clic sur un autre satellite → remplacer

**Modifications de `GlobeService`** :
- Ajouter `getGroundTrack(name: string): Observable<SatellitePosition[]>` → `GET /api/v1/orbit/groundtrack?name=${name}&duration=90&step=60`

**Points de vigilance** :
- `alt * 1000` : l'altitude de `SatellitePosition` est en km, Cesium attend des mètres.
- Utiliser `NgZone.runOutsideAngular()` pour la création de la `Polyline` Cesium — seule la mise à jour du compteur/état doit être dans `NgZone.run()`.
- `PolylineDashMaterialProperty` est disponible dans CesiumJS sans import supplémentaire.
- Si le endpoint `/groundtrack` répond lentement (> 1 s), afficher un spinner dans le panneau de contrôle pendant le chargement.

**Critère de validation** :
- Clic sur l'ISS dans le globe → tracé cyan en tirets visible suivant la trajectoire orbitale
- Deuxième clic sur l'ISS → tracé disparaît
- Clic sur un autre satellite → l'ancien tracé est remplacé par le nouveau
- `ng build` sans erreur supplémentaire

---

### Étape 6.8 — Polish UI : liens agent depuis `SatelliteProfilePage`
**Objectif** : Finaliser la cohérence de l'interface — dernier polish pour la version vitrine.

**Modifications `satellite-profile-page/`** :
- Bouton "🤖 Analyser avec l'agent" (déjà partiellement en place en M5) : vérifier le lien `[routerLink]="['/chat']" [queryParams]="{ q: 'Analyse le satellite ' + summary.name + ' et ses anomalies récentes' }"`
- Vérifier que `ChatPageComponent.ngAfterViewInit()` lit bien le query param `q` et appelle `chatComp.prefill()` ← déjà implémenté en M5, confirmer fonctionnel

**Modifications `ChatPageComponent`** :
- Si le query param `q` est présent ET que `messages[]` est vide (nouvelle session) → **soumettre automatiquement** la question sans attendre que l'utilisateur clique "Envoyer"

**Critère de validation** :
- Depuis le profil de l'ISS → clic "🤖 Analyser" → arrivée sur `/chat` avec la question préremplie et soumise automatiquement
- La réponse de l'agent utilise les tools (`getOrbitalHistory`, `getUnreadAnomalies`) et retourne une analyse contextualisée

---

## Ordre d'exécution recommandé

```
6.1  Migration V6 (chat_history)
  └→ fondation BDD, bloque 6.2

6.2  JdbcChatMemory
  └→ bloque 6.4 + 6.5

6.3  OrbitWatchTools (@Tool beans)
  └→ bloque 6.4
  └→ indépendant de 6.1 / 6.2

       [Parallélisable : Feature A (6.3→6.4→6.5) et Feature C (6.7) sont indépendantes]

6.4  OrbitWatchAgent (RAG + Tool Calling + mémoire)
  └→ dépend 6.2 + 6.3

6.5  ChatController mis à jour + endpoints mémoire
  └→ dépend 6.4

6.6  ChatComponent Angular (sessionId + historique)
  └→ dépend 6.5

6.7  Ground track 3D CesiumJS  ── Feature C (indépendante)

6.8  Polish UI (SatelliteProfilePage + auto-submit)
  └→ dépend 6.5 + 6.6
```

---

## Critères de validation globaux de M6

1. **Agent Tool Calling** : question `"Quels satellites ont une conjunction critique en ce moment ?"` → l'agent appelle `getRecentConjunctions()` et retourne une réponse contextualisée avec les données réelles (visible dans les logs `[OrbitWatchAgent] tool called`).

2. **Mémoire conversationnelle** : après un échange sur l'ISS, rechargement de `/chat` → les messages précédents sont affichés ; bouton "Nouvelle conversation" efface l'historique côté UI et BDD.

3. **Sessionné par UUID** : deux onglets navigateur avec des `sessionId` différents → historiques distincts, pas d'interférence.

4. **Ground track 3D** : clic sur l'ISS dans le globe → polyline cyan en tirets visible ; toggle fonctionnel ; `ng build` sans erreur.

5. **Lien agent depuis profil** : clic "🤖 Analyser" sur le profil ISS → navigation vers `/chat` avec question soumise automatiquement et réponse de l'agent en streaming.

6. **Zéro régression** : `mvn test` vert sur l'ensemble des tests M1–M5 (≥ 50 tests). Endpoints M1–M5 existants fonctionnels. `ng build` sans erreur.

---

## Récapitulatif des nouvelles dépendances

| Dépendance | Type | Usage |
|------------|------|-------|
| *(aucune)* | — | Tout est déjà dans le BOM Spring AI 2.0.0-M4 (`ChatMemory`, `MessageChatMemoryAdvisor`, Tool Calling) |

## Récapitulatif des nouvelles migrations Flyway

| Migration | Contenu |
|-----------|---------|
| `V6__create_chat_history.sql` | Table `chat_history` (session_id, role, content, created_at) + index |

## Récapitulatif des nouveaux endpoints REST

| Endpoint | Méthode | Description |
|----------|---------|-------------|
| `POST /api/v1/ai/chat` | POST | Modifié : ajout `sessionId` dans le body |
| `GET /api/v1/ai/chat/history` | GET | Historique de la session (`?sessionId=`) |
| `DELETE /api/v1/ai/chat/history` | DELETE | Effacer la session (`?sessionId=`) |

---

*Document de référence pour le développement de la Milestone 6 — dernière milestone OrbitWatch.*

