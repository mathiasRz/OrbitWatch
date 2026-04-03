package projet.OrbitWatch.job;

import projet.OrbitWatch.dto.TleEntry;

import java.util.List;

/**
 * Événement Spring publié par {@link FetchCelesTrackTLEJob} après chaque
 * chargement réussi d'un catalogue TLE.
 *
 * <p>Permet à {@link OrbitalHistoryJob} de s'abonner via {@code @EventListener}
 * sans dépendance directe ni race condition entre les deux jobs.
 *
 * @param catalogName Nom du catalogue chargé (ex : {@code "stations"})
 * @param entries     Liste des TLE parsés et stockés lors de ce fetch
 */
public record TleCatalogRefreshedEvent(
        String catalogName,
        List<TleEntry> entries
) {}

