package projet.OrbitWatch.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory statique de {@link Specification} pour les filtres dynamiques
 * sur les alertes d'anomalies.
 */
public final class AnomalySpecification {

    private AnomalySpecification() {}

    /**
     * Construit une {@link Specification} combinant tous les filtres optionnels.
     *
     * @param noradId  filtre exact sur le NORAD ID ; {@code null} = pas de filtre
     * @param type     filtre sur le type d'anomalie ; {@code null} = pas de filtre
     * @param severity filtre sur la sévérité ; {@code null} = pas de filtre
     * @param from     borne inférieure de {@code detectedAt} ; {@code null} = pas de filtre
     * @param to       borne supérieure de {@code detectedAt} ; {@code null} = pas de filtre
     * @return {@link Specification} combinée (ET logique entre critères actifs)
     */
    public static Specification<AnomalyAlert> build(
            Integer noradId, AnomalyType type, AnomalySeverity severity,
            Instant from, Instant to) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (noradId != null) {
                predicates.add(cb.equal(root.get("noradId"), noradId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("detectedAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

