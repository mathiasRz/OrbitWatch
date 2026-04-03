package projet.OrbitWatch.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import projet.OrbitWatch.model.OrbitalHistory;

import java.time.Instant;
import java.util.List;

/**
 * Repository JPA pour l'historique orbital.
 *
 * <p>Toutes les requêtes sont indexées via {@code idx_orbital_history_norad_time}
 * (norad_id, fetched_at DESC).
 */
@Repository
public interface OrbitalHistoryRepository extends JpaRepository<OrbitalHistory, Long> {

    /**
     * Snapshots d'un satellite triés du plus récent au plus ancien.
     *
     * @param noradId identifiant NORAD
     * @param pageable pour limiter le nombre de résultats retournés
     * @return liste paginée de snapshots
     */
    List<OrbitalHistory> findByNoradIdOrderByFetchedAtDesc(int noradId, Pageable pageable);

    /**
     * Snapshots d'un satellite sur une fenêtre temporelle donnée.
     *
     * @param noradId identifiant NORAD
     * @param from    début de la fenêtre (inclusif)
     * @param to      fin de la fenêtre (inclusif)
     * @return liste de snapshots dans l'intervalle, triée par date croissante
     */
    List<OrbitalHistory> findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
            int noradId, Instant from, Instant to);

    /**
     * Nombre total de snapshots pour un satellite — utilisé par le garde-fou
     * {@code minHistorySize} avant le Z-score.
     *
     * @param noradId identifiant NORAD
     * @return nombre de lignes en base pour ce satellite
     */
    long countByNoradId(int noradId);

    /**
     * Purge TTL : supprime tous les snapshots antérieurs à la date limite.
     * Appelé par {@code OrbitalHistoryJob} pour maintenir la rétention configurable.
     *
     * @param cutoff instant limite (les lignes avec {@code fetched_at < cutoff} sont supprimées)
     * @return nombre de lignes supprimées
     */
    @Modifying
    @Query("DELETE FROM OrbitalHistory h WHERE h.fetchedAt < :cutoff")
    int deleteByFetchedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Liste des NORAD IDs distincts présents en base — utilisée par {@code AnomalyScanJob}
     * pour itérer sur tous les satellites connus.
     *
     * @return liste des identifiants NORAD distincts
     */
    @Query("SELECT DISTINCT h.noradId FROM OrbitalHistory h")
    List<Integer> findDistinctNoradIds();
}

