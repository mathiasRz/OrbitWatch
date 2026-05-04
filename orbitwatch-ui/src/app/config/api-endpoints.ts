/**
 * Configuration centralisée des URLs de l'API backend OrbitWatch.
 *
 * Modifier uniquement ici pour changer l'hôte/port en dev, staging ou prod.
 * En production, remplacer BASE_URL par la valeur issue de environment.ts.
 */

const BASE_URL = 'http://localhost:8080/api/v1';

export const API_ENDPOINTS = {

  // ── Orbite & propagation ─────────────────────────────────────────────────
  orbit: {
    position:   `${BASE_URL}/orbit/position`,
    positions:  `${BASE_URL}/orbit/positions`,
    groundtrack:`${BASE_URL}/orbit/groundtrack`,
    heatmap:    `${BASE_URL}/orbit/heatmap`,
  },

  // ── Catalogue TLE ────────────────────────────────────────────────────────
  tle: {
    names:      `${BASE_URL}/tle/names`,
  },

  // ── Historique orbital ───────────────────────────────────────────────────
  orbitalHistory: {
    byNorad:    (noradId: number) => `${BASE_URL}/orbital-history/${noradId}`,
    latest:     (noradId: number) => `${BASE_URL}/orbital-history/${noradId}/latest`,
    export:     (noradId: number) => `${BASE_URL}/orbital-history/${noradId}/export`,
  },

  // ── Profil satellite ─────────────────────────────────────────────────────
  satellite: {
    summary:       (noradId: number) => `${BASE_URL}/satellite/${noradId}/summary`,
    summaryByName: (name: string)    => `${BASE_URL}/satellite/byname/${encodeURIComponent(name)}/summary`,
  },

  // ── Conjunctions ─────────────────────────────────────────────────────────
  conjunction: {
    analyze:       `${BASE_URL}/conjunction/analyze`,
    analyzeByName: `${BASE_URL}/conjunction/analyze-by-name`,
    alerts:        `${BASE_URL}/conjunction/alerts`,
    alertsUnread:  `${BASE_URL}/conjunction/alerts/unread`,
    alertAck:      (id: number) => `${BASE_URL}/conjunction/alerts/${id}/ack`,
  },

  // ── Assistant IA / RAG ───────────────────────────────────────────────────
  ai: {
    chat:    `${BASE_URL}/ai/chat`,
    history: `${BASE_URL}/ai/chat/history`,
    health:  `${BASE_URL}/ai/chat/health`,
  },

  // ── Anomalies ─────────────────────────────────────────────────────────────
  anomaly: {
    alerts:       `${BASE_URL}/anomaly/alerts`,
    alertsUnread: `${BASE_URL}/anomaly/alerts/unread`,
    alertAck:     (id: number) => `${BASE_URL}/anomaly/alerts/${id}/ack`,
  },

} as const;

