package projet.OrbitWatch.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import projet.OrbitWatch.model.ConjunctionAlert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory statique de {@link Specification} pour les filtres dynamiques
 * sur les alertes de conjonction.
 *
 * <p>Utilisation :
 * <pre>
 *   Specification&lt;ConjunctionAlert&gt; spec = ConjunctionSpecification.build(sat, from, to, maxKm);
 *   repository.findAll(spec, pageable);
 * </pre>
 */
public final class ConjunctionSpecification {

    private ConjunctionSpecification() {}

    /**
     * Construit une {@link Specification} combinant tous les filtres optionnels.
     *
     * @param sat    fragment de nom de satellite — filtre LIKE (insensible à la casse)
     *               sur {@code nameSat1} OU {@code nameSat2} ; {@code null} = pas de filtre
     * @param from   borne inférieure du TCA (inclusif) ; {@code null} = pas de filtre
     * @param to     borne supérieure du TCA (inclusif) ; {@code null} = pas de filtre
     * @param maxKm  distance maximale en km ; {@code null} = pas de filtre
     * @return {@link Specification} combinée (ET logique entre les critères actifs)
     */
    public static Specification<ConjunctionAlert> build(
            String sat, Instant from, Instant to, Double maxKm) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // ── Filtre satellite (LIKE insensible à la casse sur sat1 OU sat2) ─
            if (sat != null && !sat.isBlank()) {
                String pattern = "%" + sat.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("nameSat1")), pattern),
                        cb.like(cb.lower(root.get("nameSat2")), pattern)
                ));
            }

            // ── Filtre borne inférieure TCA ────────────────────────────────────
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("tca"), from));
            }

            // ── Filtre borne supérieure TCA ────────────────────────────────────
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("tca"), to));
            }

            // ── Filtre distance maximale ───────────────────────────────────────
            if (maxKm != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("distanceKm"), maxKm));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

