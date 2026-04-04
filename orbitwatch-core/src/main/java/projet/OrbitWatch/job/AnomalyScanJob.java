package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.AnomalyDetectionService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Job planifié qui orchestre la détection d'anomalies orbitales.
 *
 * <p>Toutes les heures (configurable via {@code anomaly.scan.delay-ms}), il :
 * <ol>
 *   <li>Récupère tous les NORAD IDs distincts de {@code orbital_history}</li>
 *   <li>Appelle {@link AnomalyDetectionService#detectRuleBased} et
 *       {@link AnomalyDetectionService#detectZScore} pour chaque satellite</li>
 *   <li>Déduplique via une fenêtre de ±6h avant persistance</li>
 *   <li>Persiste les nouvelles alertes et logue un {@code WARN} pour chacune</li>
 * </ol>
 *
 * <p>Désactivable via {@code anomaly.scan.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "anomaly.scan.enabled", matchIfMissing = true)
public class AnomalyScanJob {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScanJob.class);

    /** Fenêtre de déduplication : si une alerte du même type existe dans les 6h, on skip. */
    private static final int DEDUP_WINDOW_HOURS = 6;

    /** Taille de fenêtre Z-score transmise au service. */
    private static final int ZSCORE_WINDOW = 30;

    private final AnomalyDetectionService  anomalyDetectionService;
    private final AnomalyAlertRepository   anomalyAlertRepository;
    private final OrbitalHistoryRepository orbitalHistoryRepository;

    public AnomalyScanJob(AnomalyDetectionService anomalyDetectionService,
                          AnomalyAlertRepository anomalyAlertRepository,
                          OrbitalHistoryRepository orbitalHistoryRepository) {
        this.anomalyDetectionService  = anomalyDetectionService;
        this.anomalyAlertRepository   = anomalyAlertRepository;
        this.orbitalHistoryRepository = orbitalHistoryRepository;
    }

    @Scheduled(fixedDelayString = "${anomaly.scan.delay-ms:3600000}")
    @Transactional
    public void scan() {
        List<Integer> noradIds = orbitalHistoryRepository.findDistinctNoradIds();

        if (noradIds.isEmpty()) {
            log.info("[AnomalyScanJob] Aucun satellite en base — scan ignoré (cold start safe).");
            return;
        }

        log.info("[AnomalyScanJob] Début du scan anomalies sur {} satellite(s).", noradIds.size());

        int totalNew = 0;

        for (int noradId : noradIds) {
            // ── Phase 1 : règles métier ────────────────────────────────────────
            List<AnomalyAlert> ruleAlerts = anomalyDetectionService.detectRuleBased(noradId);
            totalNew += persist(ruleAlerts);

            // ── Phase 2 : Z-score glissant ────────────────────────────────────
            List<AnomalyAlert> zscoreAlerts = anomalyDetectionService.detectZScore(noradId, ZSCORE_WINDOW);
            totalNew += persist(zscoreAlerts);
        }

        log.info("[AnomalyScanJob] Scan terminé — {} nouvelle(s) anomalie(s) persistée(s).", totalNew);
    }

    /**
     * Persiste les alertes après déduplication (fenêtre ±{@value DEDUP_WINDOW_HOURS}h).
     *
     * @return nombre d'alertes effectivement persistées
     */
    int persist(List<AnomalyAlert> candidates) {
        int count = 0;
        Instant dedupCutoff = Instant.now().minus(DEDUP_WINDOW_HOURS, ChronoUnit.HOURS);

        for (AnomalyAlert alert : candidates) {
            if (isDuplicate(alert, dedupCutoff)) {
                log.debug("[AnomalyScanJob] Alerte dupliquée ignorée : NORAD {} type {}",
                        alert.getNoradId(), alert.getType());
                continue;
            }
            anomalyAlertRepository.save(alert);
            log.warn("[AnomalyScanJob] ⚠ Nouvelle anomalie : NORAD {} ({}) — {} [{}] — {}",
                    alert.getNoradId(), alert.getSatelliteName(),
                    alert.getType(), alert.getSeverity(), alert.getDescription());
            count++;
        }
        return count;
    }

    private boolean isDuplicate(AnomalyAlert alert, Instant cutoff) {
        return anomalyAlertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                alert.getNoradId(), alert.getType(), cutoff);
    }
}



