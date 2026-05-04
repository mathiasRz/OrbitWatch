package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import projet.OrbitWatch.dto.*;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tools Spring AI Tool Calling exposant les méthodes métier OrbitWatch au LLM.
 *
 * <p>Aucun {@code @Transactional} sur ce bean — les appels transactionnels
 * sont délégués aux repositories/services existants pour éviter les conflits proxy CGLIB.
 *
 * <p>Tous les retours sont des DTOs records plats (pas d'entité JPA directe) pour
 * garantir la sérialisation JSON sans boucle circulaire.
 */
@Component
public class OrbitWatchTools {

    private static final Logger log = LoggerFactory.getLogger(OrbitWatchTools.class);
    private static final int MAX_CONJUNCTIONS   = 10;
    private static final int MAX_ORBITAL_HISTORY = 20;
    private static final int MAX_ANOMALIES       = 10;

    private final ConjunctionAlertRepository conjunctionAlertRepository;
    private final OrbitalHistoryRepository   orbitalHistoryRepository;
    private final AnomalyAlertRepository     anomalyAlertRepository;
    private final TleService                 tleService;
    private final ConjunctionService         conjunctionService;

    public OrbitWatchTools(ConjunctionAlertRepository conjunctionAlertRepository,
                           OrbitalHistoryRepository orbitalHistoryRepository,
                           AnomalyAlertRepository anomalyAlertRepository,
                           TleService tleService,
                           ConjunctionService conjunctionService) {
        this.conjunctionAlertRepository = conjunctionAlertRepository;
        this.orbitalHistoryRepository   = orbitalHistoryRepository;
        this.anomalyAlertRepository     = anomalyAlertRepository;
        this.tleService                 = tleService;
        this.conjunctionService         = conjunctionService;
    }

    // ─── Tools ────────────────────────────────────────────────────────────────

    @Tool(description = "Récupère les alertes de rapprochement critique (conjunction) des N dernières heures, triées par distance croissante.")
    public List<ConjunctionAlertDto> getRecentConjunctions(int hours) {
        Instant since = Instant.now().minus(Math.max(1, hours), ChronoUnit.HOURS);
        log.info("[OrbitWatchTools] getRecentConjunctions({} h) depuis {}", hours, since);

        return conjunctionAlertRepository
                .findByDetectedAtAfterOrderByDistanceKmAsc(since)
                .stream()
                .limit(MAX_CONJUNCTIONS)
                .map(c -> new ConjunctionAlertDto(
                        c.getNameSat1(),
                        c.getNameSat2(),
                        c.getDistanceKm(),
                        c.getTca().toString()))
                .toList();
    }

    @Tool(description = "Retourne l'historique des paramètres orbitaux d'un satellite sur N jours (max 20 entrées).")
    public List<OrbitalHistoryDto> getOrbitalHistory(String name, int days) {
        log.info("[OrbitWatchTools] getOrbitalHistory({}, {} jours)", name, days);
        try {
            TleEntry tle = tleService.resolveUniqueTle(name);
            int noradId = tle.noradId();

            Instant since = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);
            return orbitalHistoryRepository
                    .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, MAX_ORBITAL_HISTORY))
                    .stream()
                    .filter(h -> h.getFetchedAt().isAfter(since))
                    .map(h -> new OrbitalHistoryDto(
                            h.getFetchedAt().toString(),
                            h.getAltitudePerigeeKm(),
                            h.getAltitudeApogeeKm(),
                            h.getInclinationDeg(),
                            h.getEccentricity()))
                    .toList();
        } catch (Exception e) {
            log.warn("[OrbitWatchTools] getOrbitalHistory échec pour '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    @Tool(description = "Calcule les rapprochements critiques entre deux satellites sur les prochaines 24 heures.")
    public ConjunctionSummaryDto analyzeConjunction(String sat1, String sat2) {
        log.info("[OrbitWatchTools] analyzeConjunction({}, {})", sat1, sat2);
        try {
            TleEntry tle1 = tleService.resolveUniqueTle(sat1);
            TleEntry tle2 = tleService.resolveUniqueTle(sat2);

            ConjunctionRequest req = new ConjunctionRequest(
                    tle1.name(), tle1.line1(), tle1.line2(),
                    tle2.name(), tle2.line1(), tle2.line2(),
                    24.0, 60, 5.0);

            ConjunctionReport report = conjunctionService.analyze(req);
            double minDist = report.events().isEmpty() ? -1.0
                    : report.events().stream().mapToDouble(ConjunctionEvent::distanceKm).min().orElse(-1.0);

            return new ConjunctionSummaryDto(
                    sat1, sat2,
                    report.events().size(),
                    minDist < 0 ? null : minDist,
                    null);
        } catch (Exception e) {
            log.warn("[OrbitWatchTools] analyzeConjunction({}, {}) échec: {}", sat1, sat2, e.getMessage());
            return new ConjunctionSummaryDto(sat1, sat2, 0, null, "Satellite inconnu : " + e.getMessage());
        }
    }

    @Tool(description = "Retourne les anomalies orbitales non acquittées (max 10), triées par date de détection décroissante.")
    public List<AnomalyAlertDto> getUnreadAnomalies() {
        log.info("[OrbitWatchTools] getUnreadAnomalies()");
        return anomalyAlertRepository
                .findByAcknowledgedFalseOrderByDetectedAtDesc()
                .stream()
                .limit(MAX_ANOMALIES)
                .map(a -> new AnomalyAlertDto(
                        a.getSatelliteName(),
                        a.getType().name(),
                        a.getSeverity().name(),
                        a.getDetectedAt().toString(),
                        a.getDescription()))
                .toList();
    }

    @Tool(description = "Retourne un résumé en langage naturel du satellite : orbite actuelle, anomalies récentes non acquittées.")
    public String getSatelliteSummary(String name) {
        log.info("[OrbitWatchTools] getSatelliteSummary({})", name);
        try {
            TleEntry tle = tleService.resolveUniqueTle(name);
            int noradId = tle.noradId();

            List<OrbitalHistory> history = orbitalHistoryRepository
                    .findByNoradIdOrderByFetchedAtDesc(noradId, PageRequest.of(0, 1));

            StringBuilder sb = new StringBuilder();
            sb.append("Satellite : ").append(tle.name())
              .append(" (NORAD ").append(noradId).append(")\n");

            if (!history.isEmpty()) {
                OrbitalHistory latest = history.get(0);
                sb.append("Orbite actuelle :\n")
                  .append("  - Périgée : ").append(String.format("%.1f km", latest.getAltitudePerigeeKm())).append("\n")
                  .append("  - Apogée  : ").append(String.format("%.1f km", latest.getAltitudeApogeeKm())).append("\n")
                  .append("  - Inclinaison : ").append(String.format("%.2f°", latest.getInclinationDeg())).append("\n")
                  .append("  - Excentricité : ").append(String.format("%.6f", latest.getEccentricity())).append("\n")
                  .append("  - Mis à jour le : ").append(latest.getFetchedAt().toString()).append("\n");
            } else {
                sb.append("Historique orbital non disponible.\n");
            }

            long unreadCount = anomalyAlertRepository.findByNoradIdOrderByDetectedAtDesc(noradId)
                    .stream().filter(a -> !a.isAcknowledged()).count();
            sb.append("Anomalies non acquittées : ").append(unreadCount).append("\n");

            return sb.toString();
        } catch (Exception e) {
            return "Impossible de récupérer les informations pour '" + name + "' : " + e.getMessage();
        }
    }
}


