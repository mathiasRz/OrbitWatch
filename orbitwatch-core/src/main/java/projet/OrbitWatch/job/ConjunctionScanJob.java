package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
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

    /**
     * Seuil de détection co-orbital : deux satellites dont le mouvement moyen
     * diffère de moins de 0.01 rev/jour ET l'inclinaison de moins de 0.1°
     * sont considérés comme co-orbitaux (ex. modules ISS + vaisseaux amarrés)
     * et leur paire est ignorée pour éviter les faux positifs permanents.
     */
    private static final double CO_ORBITAL_MEAN_MOTION_DELTA = 0.01; // rev/jour
    private static final double CO_ORBITAL_INCLINATION_DELTA  = 0.1; // degrés

    @Value("#{'${conjunction.scan.catalogs:stations,active}'.split(',')}")
    private List<String> scannedCatalogs;

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
        List<TleEntry> entries = new ArrayList<>();
        for (String catalog : scannedCatalogs) {
            entries.addAll(tleService.findByCatalog(catalog.trim()));
        }

        if (entries.size() < 2) {
            log.info("[ConjunctionScanJob] Moins de 2 satellites en mémoire — scan ignoré.");
            return;
        }

        log.info("[ConjunctionScanJob] Démarrage du scan — {} satellites à analyser.", entries.size());

        int newAlerts = 0;
        int skipDedup = 0;
        int skipCoOrb = 0;
        int erreurs   = 0;

        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                TleEntry sat1 = entries.get(i);
                TleEntry sat2 = entries.get(j);

                int norad1 = sat1.noradId();
                int norad2 = sat2.noradId();

                if (norad1 <= 0 || norad2 <= 0) {
                    log.debug("[ConjunctionScanJob] NORAD ID invalide pour {} ({}) ou {} ({}) — paire ignorée.",
                            sat1.name(), norad1, sat2.name(), norad2);
                    erreurs++;
                    continue;
                }

                // ── Filtre co-orbital ─────────────────────────────────────
                // Exclut les paires sur la même orbite (modules ISS + vaisseaux amarrés)
                // qui génèrent des faux positifs permanents.
                if (isCoOrbital(sat1.line2(), sat2.line2())) {
                    log.debug("[ConjunctionScanJob] Paire co-orbitale ignorée : {} ↔ {}",
                            sat1.name(), sat2.name());
                    skipCoOrb++;
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

                        log.warn("[ConjunctionScanJob] ⚠ Nouvelle alerte : {} ({}) ↔ {} ({}) — {} km au {}",
                                sat1.name(), norad1, sat2.name(), norad2,
                                String.format("%.3f", event.distanceKm()), event.tca());
                    }

                } catch (Exception ex) {
                    erreurs++;
                    log.debug("[ConjunctionScanJob] Erreur sur paire {} / {} : {}",
                            sat1.name(), sat2.name(), ex.getMessage());
                }
            }
        }

        log.info("[ConjunctionScanJob] Scan terminé — {} nouvelle(s), {} dédupliquée(s), {} co-orbitale(s) ignorée(s), {} erreur(s).",
                newAlerts, skipDedup, skipCoOrb, erreurs);
    }

    /**
     * Détermine si deux satellites sont co-orbitaux en comparant leur mouvement
     * moyen (colonnes 52-63 du TLE ligne 2) et leur inclinaison (colonnes 8-16).
     *
     * @param line2a TLE ligne 2 du satellite A
     * @param line2b TLE ligne 2 du satellite B
     * @return {@code true} si les deux satellites sont considérés co-orbitaux
     */
    static boolean isCoOrbital(String line2a, String line2b) {
        try {
            double mmA   = Double.parseDouble(line2a.substring(52, 63).trim());
            double mmB   = Double.parseDouble(line2b.substring(52, 63).trim());
            double inclA = Double.parseDouble(line2a.substring(8,  16).trim());
            double inclB = Double.parseDouble(line2b.substring(8,  16).trim());
            return Math.abs(mmA - mmB) < CO_ORBITAL_MEAN_MOTION_DELTA
                && Math.abs(inclA - inclB) < CO_ORBITAL_INCLINATION_DELTA;
        } catch (Exception e) {
            return false;
        }
    }
}
