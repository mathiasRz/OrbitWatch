package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.job.TleCatalogRefreshedEvent;
import projet.OrbitWatch.service.OrbitalElementsExtractor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Service d'ingestion RAG : écoute {@link TleCatalogRefreshedEvent} et
 * indexe les résumés textuels des satellites dans PgVector.
 *
 * <p>Seuls les catalogues {@code stations} et {@code active} sont indexés
 * (le catalogue {@code debris} est volontairement exclu pour éviter une explosion
 * du volume de l'index — ~2 000 documents supplémentaires pour des objets sans
 * données textuelles utiles).
 *
 * <p>Stratégie d'upsert : suppression native JDBC par {@code noradId} avant
 * réindexation, pour garantir l'unicité des documents dans le vector store.
 */
@Service
public class OrbitWatchIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OrbitWatchIngestionService.class);

    @Value("${rag.ingestion.enabled:true}")
    private boolean enabled;

    @Value("#{'${rag.ingestion.catalogs:stations,active}'.split(',')}")
    private List<String> allowedCatalogs;

    private final VectorStore              vectorStore;
    private final OrbitalElementsExtractor extractor;
    private final JdbcTemplate             jdbcTemplate;

    public OrbitWatchIngestionService(VectorStore vectorStore,
                                      OrbitalElementsExtractor extractor,
                                      JdbcTemplate jdbcTemplate) {
        this.vectorStore  = vectorStore;
        this.extractor    = extractor;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Déclenché après chaque refresh du catalogue TLE.
     * Indexe les résumés des satellites dans PgVector (upsert par noradId).
     */
    @EventListener
    @Async
    public void onCatalogRefreshed(TleCatalogRefreshedEvent event) {
        if (!enabled) {
            return;
        }

        String catalog = event.catalogName();
        boolean allowed = allowedCatalogs.stream()
                .map(String::trim)
                .anyMatch(catalog::equalsIgnoreCase);

        if (!allowed) {
            log.info("[Ingestion] Catalogue '{}' ignoré (non dans la liste d'ingestion).", catalog);
            return;
        }

        List<TleEntry> entries = event.entries();
        if (entries.isEmpty()) {
            log.info("[Ingestion] Catalogue '{}' vide — aucune indexation.", catalog);
            return;
        }

        log.info("[Ingestion] Démarrage indexation : {} TLE(s) du catalogue '{}'.", entries.size(), catalog);
        int indexed = 0;
        int skipped = 0;

        for (TleEntry entry : entries) {
            try {
                OrbitalElements el = extractor.extract(entry.name(), entry.line1(), entry.line2());

                String textSummary = buildTextSummary(el, catalog);
                String contentHash = md5(textSummary);

                // Upsert : suppression du document existant par noradId (SQL natif — PgVector)
                jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'noradId' = ?",
                    String.valueOf(el.noradId())
                );

                Document doc = Document.builder()
                        .text(textSummary)
                        .metadata(Map.of(
                                "noradId",     String.valueOf(el.noradId()),
                                "name",        el.satelliteName(),
                                "catalog",     catalog,
                                "contentHash", contentHash
                        ))
                        .build();

                vectorStore.add(List.of(doc));
                indexed++;

            } catch (Exception ex) {
                skipped++;
                log.debug("[Ingestion] Skip '{}' : {}", entry.name(), ex.getMessage());
            }
        }

        log.info("[Ingestion] Catalogue '{}' — {} document(s) indexés, {} ignorés.", catalog, indexed, skipped);
    }

    /**
     * Construit un résumé textuel en langage naturel à partir des éléments orbitaux.
     * Format cohérent avec celui produit par {@code OrbitalHistoryController.getSummary()}.
     */
    String buildTextSummary(OrbitalElements el, String catalog) {
        return "%s, NORAD %d, catalogue %s, altitude %.0f–%.0f km, inclinaison %.2f°, excentricité %.6f, mouvement moyen %.4f rev/jour.".formatted(
                el.satelliteName(),
                el.noradId(),
                catalog,
                el.altitudePerigeeKm(),
                el.altitudeApogeeKm(),
                el.inclinationDeg(),
                el.eccentricity(),
                el.meanMotionRevDay()
        );
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

