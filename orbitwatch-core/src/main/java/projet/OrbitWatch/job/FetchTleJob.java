package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import projet.OrbitWatch.client.TleSourceClient;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Job de fetch TLE avec Chain of Responsibility sur les sources.
 *
 * <p>Pour chaque catalogue configure, essaie les sources dans l'ordre injecte
 * (CelesTrak en premier, SpaceTrack en fallback si active).
 * Passe &agrave; la source suivante si la courante echoue ou retourne vide.
 *
 * <p>Remplace {@link FetchCelesTrackTLEJob} qui est conserve pour compatibilite
 * mais del&egrave;gue desormais &agrave; ce job.
 */
@Component
public class FetchTleJob {

    private static final Logger log = LoggerFactory.getLogger(FetchTleJob.class);

    @Value("${tle.celestrak.catalogs:stations,active,visual}")
    private String catalogsConfig;

    /** Sources ordonnees par priorite — injectees par Spring (CelesTrak d'abord, SpaceTrack si active). */
    private final List<TleSourceClient>    sources;
    private final TleService               tleService;
    private final ApplicationEventPublisher eventPublisher;

    public FetchTleJob(List<TleSourceClient> sources,
                       TleService tleService,
                       ApplicationEventPublisher eventPublisher) {
        this.sources        = sources;
        this.tleService     = tleService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Refresh de tous les catalogues configures.
     * Chaque catalogue est tente sur les sources dans l'ordre.
     */
    @Scheduled(initialDelay = 0, fixedDelayString = "${tle.refresh.delay-ms:21600000}")
    public void refreshAll() {
        List<String> catalogs = Arrays.stream(catalogsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        log.info("[FetchTleJob] Debut refresh — {} catalogue(s) : {}, {} source(s) disponible(s)",
                catalogs.size(), catalogs, sources.stream().map(TleSourceClient::sourceName).toList());

        for (String catalogName : catalogs) {
            fetchCatalogWithFallback(catalogName);
        }

        log.info("[FetchTleJob] Refresh termine — {} TLE en memoire", tleService.countAll());
    }

    /**
     * Tente de fetcher un catalogue en parcourant les sources dans l'ordre.
     * S'arrete a la premiere source qui retourne des donnees valides.
     */
    void fetchCatalogWithFallback(String catalogName) {
        for (TleSourceClient source : sources) {
            try {
                String rawBody = source.getCatalog(catalogName);
                if (rawBody == null || rawBody.isBlank()) {
                    log.warn("[FetchTleJob] Source '{}' : reponse vide pour '{}', tentative source suivante.",
                            source.sourceName(), catalogName);
                    continue;
                }

                List<TleEntry> entries = tleService.parseTle3Lines(rawBody, catalogName);
                if (entries.isEmpty()) {
                    log.warn("[FetchTleJob] Source '{}' : 0 TLE parses pour '{}', tentative source suivante.",
                            source.sourceName(), catalogName);
                    continue;
                }

                tleService.getCatalog().put(catalogName, new CopyOnWriteArrayList<>(entries));
                eventPublisher.publishEvent(new TleCatalogRefreshedEvent(catalogName, entries));
                log.info("[FetchTleJob] Catalogue '{}' charge depuis '{}' — {} satellites.",
                        catalogName, source.sourceName(), entries.size());
                return; // succes — on arrete la chaine

            } catch (Exception e) {
                log.warn("[FetchTleJob] Source '{}' indisponible pour catalogue '{}' : {} — tentative source suivante.",
                        source.sourceName(), catalogName, e.getMessage());
            }
        }

        // Toutes les sources ont echoue
        log.error("[FetchTleJob] Toutes les sources ont echoue pour catalogue '{}' — TLE non mis a jour.", catalogName);
    }
}

