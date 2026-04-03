package projet.OrbitWatch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import projet.OrbitWatch.model.ConjunctionAlert;

import java.time.Instant;
import java.util.List;

/**
 * Repository JPA pour les alertes de rapprochement.
 */
@Repository
public interface ConjunctionAlertRepository extends JpaRepository<ConjunctionAlert, Long> {

    /**
     * Alertes non acquittées, triées par TCA croissant — utilisé pour le badge IHM.
     */
    List<ConjunctionAlert> findByAcknowledgedFalseOrderByTcaAsc();

    /**
     * Déduplication : vérifie si une alerte existe déjà pour cette paire de satellites
     * autour d'un TCA donné (fenêtre ±{@code marginMinutes} minutes).
     *
     * @param nameSat1  Nom du satellite 1
     * @param nameSat2  Nom du satellite 2
     * @param tcaFrom   Début de la fenêtre de déduplication
     * @param tcaTo     Fin de la fenêtre de déduplication
     * @return {@code true} si une alerte similaire existe déjà
     */
    boolean existsByNameSat1AndNameSat2AndTcaBetween(
            String nameSat1, String nameSat2,
            Instant tcaFrom, Instant tcaTo);
}

