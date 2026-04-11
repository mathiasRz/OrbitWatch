package projet.OrbitWatch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import projet.OrbitWatch.client.CelesTrackClient;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @deprecated Remplace par {@link FetchTleJob} qui gere le fallback multi-sources.
 * Conserve uniquement pour retro-compatibilite des tests existants.
 * Desactive par defaut via {@code tle.legacy-fetch.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "tle.legacy-fetch.enabled", havingValue = "true", matchIfMissing = false)
public class FetchCelesTrackTLEJob {

    private static final Logger log = LoggerFactory.getLogger(FetchCelesTrackTLEJob.class);

    /** Liste des catalogues CelesTrak à charger, séparés par des virgules. */
    @Value("${tle.celestrak.catalogs:stations,active,visual}")
    private String catalogsConfig;

    private final CelesTrackClient         celestrackclient;
    private final TleService               tleservice;
    private final ApplicationEventPublisher eventPublisher;

    public FetchCelesTrackTLEJob(CelesTrackClient celestrackclient,
                                  TleService tleservice,
                                  ApplicationEventPublisher eventPublisher) {
        this.celestrackclient = celestrackclient;
        this.tleservice       = tleservice;
        this.eventPublisher   = eventPublisher;
    }


	/**
   * Déclenche le refresh de tous les catalogues configurés.
   * Appelé automatiquement au démarrage (initialDelay=0)
   * puis toutes les 6 heures (fixedDelay=6h).
   */
  @Scheduled(initialDelay = 0, fixedDelayString = "${tle.refresh.delay-ms:21600000}")
  public void refreshAll() {
      List<String> catalogs = Arrays.stream(catalogsConfig.split(","))
              .map(String::trim)
              .filter(s -> !s.isBlank())
              .toList();

      log.info("[TleService] Début du refresh — {} catalogue(s) : {}", catalogs.size(), catalogs);

      for (String catalogName : catalogs) {
          try {
              String rawBody = celestrackclient.getCatalog(catalogName);
          List<TleEntry> entries = tleservice.parseTle3Lines(rawBody, catalogName);
              CopyOnWriteArrayList<TleEntry> list = new CopyOnWriteArrayList<>(entries);
              tleservice.getCatalog().put(catalogName, list);

              // Publier l'event pour OrbitalHistoryJob (async, non bloquant)
              eventPublisher.publishEvent(new TleCatalogRefreshedEvent(catalogName, entries));

              log.info("[TleService] Catalogue '{}' chargé — {} satellites.", catalogName, entries.size());
          } catch (Exception e) {
              log.error("[TleService] Échec du refresh du catalogue '{}' : {}", catalogName, e.getMessage());
          }
      }

      log.info("[TleService] Refresh terminé — {} TLE en mémoire au total", tleservice.countAll());
  }


}
