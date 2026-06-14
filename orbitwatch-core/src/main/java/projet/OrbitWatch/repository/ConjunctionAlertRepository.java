package projet.OrbitWatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import projet.OrbitWatch.model.ConjunctionAlert;

import java.time.Instant;
import java.util.List;

/**
 * Repository JPA pour les alertes de rapprochement.
 */
@Repository
public interface ConjunctionAlertRepository extends JpaRepository<ConjunctionAlert, Long>,
        JpaSpecificationExecutor<ConjunctionAlert> {

    /**
     * Alertes détectées après un instant donné, triées par distance croissante.
     */
    List<ConjunctionAlert> findByDetectedAtAfterOrderByDistanceKmAsc(java.time.Instant after);

    /**
     * Déduplication par NORAD ID : vérifie si une alerte existe déjà pour cette paire
     * autour d'un TCA donné (fenêtre ±{@code marginMinutes} minutes).
     */
    boolean existsByNoradId1AndNoradId2AndTcaBetween(
            int noradId1, int noradId2,
            Instant tcaFrom, Instant tcaTo);

    /**
     * Supprime toutes les alertes de rapprochement détectées avant la borne donnée.
     */
    int deleteByDetectedAtBefore(Instant cutoff);
}

