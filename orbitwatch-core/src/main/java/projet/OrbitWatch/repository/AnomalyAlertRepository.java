package projet.OrbitWatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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
public interface AnomalyAlertRepository extends JpaRepository<AnomalyAlert, Long> {

    /**
     * Alertes non acquittées, triées par date de détection décroissante
     * — utilisé pour le badge IHM et le compteur non-lus.
     */
    List<AnomalyAlert> findByAcknowledgedFalseOrderByDetectedAtDesc();

    /**
     * Déduplication : vérifie si une anomalie du même type existe déjà
     * pour ce satellite après un instant donné (fenêtre de ±6h).
     *
     * @param noradId    identifiant NORAD du satellite
     * @param type       type d'anomalie
     * @param after      borne inférieure de la fenêtre de déduplication
     * @return {@code true} si une alerte similaire existe déjà dans la fenêtre
     */
    boolean existsByNoradIdAndTypeAndDetectedAtAfter(
            int noradId, AnomalyType type, Instant after);

    /**
     * Alertes d'un satellite donné, triées par date décroissante.
     *
     * @param noradId identifiant NORAD
     * @return liste des alertes pour ce satellite
     */
    List<AnomalyAlert> findByNoradIdOrderByDetectedAtDesc(int noradId);

    /**
     * Liste des NORAD IDs distincts ayant au moins une alerte non acquittée
     * — utile pour le batch de scan.
     */
    @Query("SELECT DISTINCT a.noradId FROM AnomalyAlert a WHERE a.acknowledged = false")
    List<Integer> findDistinctNoradIdsWithUnreadAlerts();
}

