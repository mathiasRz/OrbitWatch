package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.OrbitalElementsExtractor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Listener Spring qui persiste les paramètres orbitaux Keplériens après chaque
 * refresh du catalogue TLE.
 *
 * <p>S'abonne à {@link TleCatalogRefreshedEvent} publié par {@link FetchCelesTrackTLEJob}.
 * L'exécution est {@code @Async} : elle ne bloque pas le thread du fetch TLE.
 *
 * <p><b>Garde-fous :</b>
 * <ul>
 *   <li>TLE malformé → skip silencieux + comptage des échecs</li>
 *   <li>&gt; 5 % d'échecs sur le batch → log {@code WARN}</li>
 *   <li>Purge TTL : supprime les snapshots plus vieux que {@code orbital.history.retention-days} (défaut 90j)</li>
 * </ul>
 */
@Component
public class OrbitalHistoryJob {

    private static final Logger log = LoggerFactory.getLogger(OrbitalHistoryJob.class);

    /** Seuil d'échec au-delà duquel un WARN est émis (5 %). */
    private static final double FAILURE_WARN_THRESHOLD = 0.05;

    @Value("${orbital.history.retention-days:90}")
    private int retentionDays;

    @Value("#{'${orbital.history.catalogs.exclude:debris}'.split(',')}")
    private List<String> excludedCatalogs = new ArrayList<>();

    private final OrbitalElementsExtractor  extractor;
    private final OrbitalHistoryRepository  repository;

    public OrbitalHistoryJob(OrbitalElementsExtractor extractor,
                              OrbitalHistoryRepository repository) {
        this.extractor        = extractor;
        this.repository       = repository;
        this.excludedCatalogs = new ArrayList<>();
    }

    /**
     * Reçoit l'événement de refresh du catalogue TLE et persiste les éléments
     * orbitaux de chaque satellite.
     *
     * @param event événement contenant le nom du catalogue et la liste des TLE
     */
    @EventListener
    @Async
    @Transactional
    public void onCatalogRefreshed(TleCatalogRefreshedEvent event) {
        List<TleEntry> entries = event.entries();
        String catalog = event.catalogName();

        // ── Garde-fou volume : skip des catalogues exclus (ex: debris) ────
        if (excludedCatalogs.stream().map(String::trim).anyMatch(catalog::equalsIgnoreCase)) {
            log.info("[OrbitalHistoryJob] Catalogue '{}' exclu — historique non persisté.", catalog);
            return;
        }

        if (entries.isEmpty()) {
            log.info("[OrbitalHistoryJob] Catalogue '{}' vide — rien à persister.", catalog);
            return;
        }

        log.info("[OrbitalHistoryJob] Traitement de {} TLE(s) du catalogue '{}'.", entries.size(), catalog);

        int saved   = 0;
        int skipped = 0;
        Instant fetchedAt = Instant.now();

        for (TleEntry entry : entries) {
            try {
                OrbitalElements elements = extractor.extract(entry.name(), entry.line1(), entry.line2());

                OrbitalHistory snapshot = new OrbitalHistory(
                        elements.noradId(),
                        elements.satelliteName(),
                        fetchedAt,
                        elements.semiMajorAxisKm(),
                        elements.eccentricity(),
                        elements.inclinationDeg(),
                        elements.raanDeg(),
                        elements.argOfPerigeeDeg(),
                        elements.meanMotionRevDay(),
                        elements.altitudePerigeeKm(),
                        elements.altitudeApogeeKm()
                );

                repository.save(snapshot);
                saved++;

            } catch (Exception ex) {
                skipped++;
                log.debug("[OrbitalHistoryJob] Skip '{}' — extraction échouée : {}", entry.name(), ex.getMessage());
            }
        }

        // Avertissement si taux d'échec > 5 %
        if (!entries.isEmpty()) {
            double failureRate = (double) skipped / entries.size();
            if (failureRate > FAILURE_WARN_THRESHOLD) {
                log.warn("[OrbitalHistoryJob] Catalogue '{}' — taux d'échec élevé : {}/{} ({}%)",
                        catalog, skipped, entries.size(),
                        String.format("%.1f", failureRate * 100));
            }
        }

        log.info("[OrbitalHistoryJob] Catalogue '{}' — {} snapshots persistés, {} ignorés.",
                catalog, saved, skipped);

        // ── Purge TTL ─────────────────────────────────────────────────────────
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteByFetchedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[OrbitalHistoryJob] Purge TTL ({} jours) — {} snapshot(s) supprimé(s).",
                    retentionDays, deleted);
        }
    }
}



