package projet.OrbitWatch.dto;

import java.time.Instant;
import java.util.List;

/**
 * Rapport complet d'une analyse de conjunction sur une fenêtre temporelle.
 *
 * @param nameSat1    Nom du satellite 1
 * @param nameSat2    Nom du satellite 2
 * @param thresholdKm Seuil utilisé pour la détection (km)
 * @param windowStart Début de la fenêtre d'analyse
 * @param windowEnd   Fin de la fenêtre d'analyse
 * @param events      Liste des rapprochements critiques détectés, triés par distance croissante
 */
public record ConjunctionReport(
        String                 nameSat1,
        String                 nameSat2,
        double                 thresholdKm,
        Instant                windowStart,
        Instant                windowEnd,
        List<ConjunctionEvent> events
) {}

