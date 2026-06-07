package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Job de purge automatique des alertes anciennes (anomalies et conjunctions).
 *
 * <p>S'exécute :
 * <ol>
 *   <li>Au démarrage de l'application (via {@link ApplicationReadyEvent})</li>
 *   <li>Chaque jour à l'heure définie par {@code alerts.purge.cron} (défaut : 02h00)</li>
 * </ol>
 *
 * <p>La rétention est configurable via {@code alerts.purge.retention-days} (défaut : 5 jours).
 *
 * <p>Désactivable via {@code alerts.purge.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "alerts.purge.enabled", matchIfMissing = true)
public class AlertPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AlertPurgeJob.class);

    /** Nombre de jours de rétention des alertes — configurable via properties. */
    @Value("${alerts.purge.retention-days:5}")
    private int retentionDays;

    private final AnomalyAlertRepository     anomalyAlertRepository;
    private final ConjunctionAlertRepository conjunctionAlertRepository;

    public AlertPurgeJob(AnomalyAlertRepository anomalyAlertRepository,
                         ConjunctionAlertRepository conjunctionAlertRepository) {
        this.anomalyAlertRepository     = anomalyAlertRepository;
        this.conjunctionAlertRepository = conjunctionAlertRepository;
    }

    /**
     * Purge déclenchée au démarrage de l'application, une fois que tous
     * les beans sont initialisés et le contexte prêt.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void purgeOnStartup() {
        log.info("[AlertPurgeJob] Purge au démarrage — rétention : {} jour(s).", retentionDays);
        purge();
    }

    /**
     * Purge planifiée une fois par jour à l'heure définie par {@code alerts.purge.cron}.
     *
     * <p>Valeur par défaut : {@code 0 0 2 * * *} — chaque jour à 02h00.
     */
    @Scheduled(cron = "${alerts.purge.cron:0 0 2 * * *}")
    @Transactional
    public void purgeScheduled() {
        log.info("[AlertPurgeJob] Purge planifiée (cron) — rétention : {} jour(s).", retentionDays);
        purge();
    }

    /**
     * Logique commune de purge : supprime les alertes antérieures au seuil de rétention.
     */
    private void purge() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deletedAnomalies    = anomalyAlertRepository.deleteByDetectedAtBefore(cutoff);
        int deletedConjunctions = conjunctionAlertRepository.deleteByDetectedAtBefore(cutoff);

        if (deletedAnomalies > 0 || deletedConjunctions > 0) {
            log.info("[AlertPurgeJob] Purge terminée — {} anomalie(s) et {} conjunction(s) supprimée(s) "
                            + "(cutoff : {}, rétention : {} jour(s)).",
                    deletedAnomalies, deletedConjunctions, cutoff, retentionDays);
        } else {
            log.debug("[AlertPurgeJob] Purge terminée — aucune alerte à supprimer "
                    + "(cutoff : {}, rétention : {} jour(s)).", cutoff, retentionDays);
        }
    }
}

