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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Job dédié au chargement du catalogue logique "debris" en mémoire.
 *
 * <p>CelesTrak ne propose pas de groupe "debris" unique. Ce job télécharge
 * les trois catalogues de débris majeurs séparément et les fusionne sous
 * l'alias {@code "debris"} dans le {@link TleService} :
 * <ul>
 *   <li>{@code fengyun-1c-debris}  — ASAT chinois 2007, ~3 000 fragments, ~800 km / 98°</li>
 *   <li>{@code iridium-33-debris}  — Collision 2009, ~600 fragments, ~780 km / 86°</li>
 *   <li>{@code cosmos-2251-debris} — Collision 2009 (autre satellite), ~1 500 fragments</li>
 * </ul>
 *
 * <p>Activé par {@code tle.debris.enabled=true} (défaut).
 * Désactivable via {@code tle.debris.enabled=false} pour les environnements sans accès CelesTrak.
 */
@Component
@ConditionalOnProperty(name = "tle.debris.enabled", havingValue = "true", matchIfMissing = true)
public class FetchDebrisTleJob {

    private static final Logger log = LoggerFactory.getLogger(FetchDebrisTleJob.class);

    /** Nom logique utilisé dans TleService pour tous les débris fusionnés. */
    public static final String DEBRIS_CATALOG_NAME = "debris";

    /**
     * Groupes CelesTrak réels à fusionner.
     * Configurable via {@code tle.debris.sources} pour ajouter/retirer des catalogues.
     */
    @Value("${tle.debris.sources:fengyun-1c-debris,iridium-33-debris,cosmos-2251-debris}")
    private String debrisSources;

    private final CelesTrackClient          celestrackClient;
    private final TleService                tleService;
    private final ApplicationEventPublisher eventPublisher;

    public FetchDebrisTleJob(CelesTrackClient celestrackClient,
                              TleService tleService,
                              ApplicationEventPublisher eventPublisher) {
        this.celestrackClient = celestrackClient;
        this.tleService       = tleService;
        this.eventPublisher   = eventPublisher;
    }

    /**
     * Télécharge chaque groupe de débris CelesTrak et les fusionne sous l'alias "debris".
     * Déclenché 5 secondes après le démarrage (après FetchTleJob) puis toutes les 6h.
     */
    @Scheduled(initialDelay = 5000, fixedDelayString = "${tle.refresh.delay-ms:21600000}")
    public void refreshDebris() {
        List<String> sources = Arrays.stream(debrisSources.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        log.info("[FetchDebrisTleJob] Début chargement débris — {} source(s) : {}", sources.size(), sources);

        List<TleEntry> allDebris = new ArrayList<>();

        for (String groupName : sources) {
            try {
                String rawBody = celestrackClient.getCatalog(groupName);
                if (rawBody == null || rawBody.isBlank()) {
                    log.warn("[FetchDebrisTleJob] Groupe '{}' : réponse vide, ignoré.", groupName);
                    continue;
                }

                // Tous les TLE sont parsés sous le nom logique "debris"
                List<TleEntry> entries = tleService.parseTle3Lines(rawBody, DEBRIS_CATALOG_NAME);
                allDebris.addAll(entries);
                log.info("[FetchDebrisTleJob] Groupe '{}' -> {} TLE.", groupName, entries.size());

            } catch (Exception e) {
                log.warn("[FetchDebrisTleJob] Groupe '{}' indisponible : {}", groupName, e.getMessage());
            }
        }

        if (allDebris.isEmpty()) {
            log.warn("[FetchDebrisTleJob] Aucun débris chargé — catalogue '{}' reste vide.", DEBRIS_CATALOG_NAME);
            return;
        }

        // Stockage fusionné sous l'alias "debris"
        tleService.getCatalog().put(DEBRIS_CATALOG_NAME, new CopyOnWriteArrayList<>(allDebris));

        // Publier l'event — OrbitalHistoryJob et ConjunctionScanJob le filtreront (garde-fous 5.11)
        eventPublisher.publishEvent(new TleCatalogRefreshedEvent(DEBRIS_CATALOG_NAME, allDebris));

        log.info("[FetchDebrisTleJob] Catalogue '{}' prêt — {} TLE au total.", DEBRIS_CATALOG_NAME, allDebris.size());
    }
}

