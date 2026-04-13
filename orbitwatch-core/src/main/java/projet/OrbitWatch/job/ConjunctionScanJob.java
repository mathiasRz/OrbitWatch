package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import projet.OrbitWatch.dto.ConjunctionEvent;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.ConjunctionRequest;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.service.ConjunctionService;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Job planifié de détection automatique des rapprochements critiques.
 *
 * <p>Toutes les heures, itère sur toutes les paires de satellites du catalogue
 * en mémoire, appelle {@link ConjunctionService#analyze} et persiste les nouvelles
 * alertes via {@link ConjunctionAlertRepository}.
 *
 * <p>Désactivable via {@code conjunction.scan.enabled=false} (utile en tests).
 */
@Component
@ConditionalOnProperty(name = "conjunction.scan.enabled", matchIfMissing = true)
public class ConjunctionScanJob {

    private static final Logger log = LoggerFactory.getLogger(ConjunctionScanJob.class);

    /** Fenêtre de déduplication : ±5 minutes autour du TCA pour éviter les doublons. */
    private static final long DEDUP_MARGIN_MINUTES = 5;

    private final TleService                 tleService;
    private final ConjunctionService         conjunctionService;
    private final ConjunctionAlertRepository repository;

    public ConjunctionScanJob(TleService tleService,
                               ConjunctionService conjunctionService,
                               ConjunctionAlertRepository repository) {
        this.tleService         = tleService;
        this.conjunctionService = conjunctionService;
        this.repository         = repository;
    }

    /**
     * Scan lancé au démarrage puis toutes les heures (configurable via
     * {@code conjunction.scan.delay-ms}).
     */
    @Scheduled(initialDelay = 30_000,
               fixedDelayString = "${conjunction.scan.delay-ms:3600000}")
    public void scan() {
        List<TleEntry> entries = tleService.findAll();

        if (entries.size() < 2) {
            log.info("[ConjunctionScanJob] Moins de 2 satellites en mémoire — scan ignoré.");
            return;
        }

        log.info("[ConjunctionScanJob] Démarrage du scan — {} satellites à analyser.", entries.size());

        int newAlerts  = 0;
        int skipDedup  = 0;
        int erreurs    = 0;

        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                TleEntry sat1 = entries.get(i);
                TleEntry sat2 = entries.get(j);

                int norad1 = sat1.noradId();
                int norad2 = sat2.noradId();

                // Ignorer les entrées dont le NORAD ID n'a pas pu être extrait
                if (norad1 <= 0 || norad2 <= 0) {
                    log.debug("[ConjunctionScanJob] NORAD ID invalide pour {} ({}) ou {} ({}) — paire ignorée.",
                            sat1.name(), norad1, sat2.name(), norad2);
                    erreurs++;
                    continue;
                }

                try {
                    ConjunctionRequest req = new ConjunctionRequest(
                            sat1.name(), sat1.line1(), sat1.line2(),
                            sat2.name(), sat2.line1(), sat2.line2(),
                            24.0, 60, 5.0
                    );

                    ConjunctionReport report = conjunctionService.analyze(req);

                    for (ConjunctionEvent event : report.events()) {
                        // Déduplication par NORAD ID : fenêtre ±5 min autour du TCA
                        Instant from = event.tca().minus(DEDUP_MARGIN_MINUTES, ChronoUnit.MINUTES);
                        Instant to   = event.tca().plus(DEDUP_MARGIN_MINUTES, ChronoUnit.MINUTES);

                        boolean exists = repository.existsByNoradId1AndNoradId2AndTcaBetween(
                                norad1, norad2, from, to);

                        if (exists) {
                            skipDedup++;
                            continue;
                        }

                        ConjunctionAlert alert = new ConjunctionAlert(
                                sat1.name(), sat2.name(),
                                norad1, norad2,
                                event.tca(), event.distanceKm(),
                                event.sat1().latitude(),  event.sat1().longitude(),  event.sat1().altitude(),
                                event.sat2().latitude(),  event.sat2().longitude(),  event.sat2().altitude()
                        );
                        repository.save(alert);
                        newAlerts++;

                        log.warn("[ConjunctionScanJob] ⚠ Nouvelle alerte : {} ({}) ↔ {} ({}) — {:.3f} km au {}",
                                sat1.name(), norad1, sat2.name(), norad2, event.distanceKm(), event.tca());
                    }

                } catch (Exception ex) {
                    erreurs++;
                    log.debug("[ConjunctionScanJob] Erreur sur paire {} / {} : {}",
                            sat1.name(), sat2.name(), ex.getMessage());
                }
            }
        }

        log.info("[ConjunctionScanJob] Scan terminé — {} nouvelle(s) alerte(s), {} dédupliquée(s), {} erreur(s).",
                newAlerts, skipDedup, erreurs);
    }
}

