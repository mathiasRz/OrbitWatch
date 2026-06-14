package projet.OrbitWatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalyType;

import java.time.Instant;
import java.util.List;

/**
 * Repository JPA pour les alertes d'anomalies orbitales.
 */
@Repository
public interface AnomalyAlertRepository extends JpaRepository<AnomalyAlert, Long>,
        JpaSpecificationExecutor<AnomalyAlert> {

    /**
     * Déduplication : vérifie si une anomalie du même type existe déjà
     * pour ce satellite après un instant donné (fenêtre de ±6h).
     */
    boolean existsByNoradIdAndTypeAndDetectedAtAfter(
            int noradId, AnomalyType type, Instant after);

    /**
     * Alertes d'un satellite donné, triées par date décroissante.
     */
    List<AnomalyAlert> findByNoradIdOrderByDetectedAtDesc(int noradId);

    /**
     * Liste des NORAD IDs distincts ayant au moins une alerte — utile pour le batch de scan.
     */
    @Query("SELECT DISTINCT a.noradId FROM AnomalyAlert a")
    List<Integer> findDistinctNoradIdsWithAlerts();

    /**
     * Supprime toutes les alertes d'anomalie détectées avant la borne donnée.
     */
    int deleteByDetectedAtBefore(Instant cutoff);
}

