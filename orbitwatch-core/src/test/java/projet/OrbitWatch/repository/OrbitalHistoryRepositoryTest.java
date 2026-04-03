package projet.OrbitWatch.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import projet.OrbitWatch.model.OrbitalHistory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration JPA pour {@link OrbitalHistoryRepository}.
 *
 * <p>Utilise H2 en mémoire via le profil "test" — aucune dépendance externe.
 * La table est créée/détruite par {@code ddl-auto=create-drop}.
 */
@DataJpaTest
@ActiveProfiles("test")
class OrbitalHistoryRepositoryTest {

    @Autowired
    private OrbitalHistoryRepository repository;

    // ── Instant de référence et constantes ────────────────────────────────────
    private static final Instant T0 = Instant.parse("2026-04-03T12:00:00Z");
    private static final int NORAD_ISS = 25544;
    private static final int NORAD_CSS = 48274;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrbitalHistory issSnapshot(Instant fetchedAt) {
        return new OrbitalHistory(
                NORAD_ISS, "ISS (ZARYA)", fetchedAt,
                6780.0, 0.0003, 51.64, 200.0, 60.0, 15.49, 408.0, 412.0
        );
    }

    private OrbitalHistory cssSnapshot(Instant fetchedAt) {
        return new OrbitalHistory(
                NORAD_CSS, "CSS (TIANHE)", fetchedAt,
                6750.0, 0.0005, 41.47, 175.0, 80.0, 15.60, 378.0, 384.0
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // save / findAll
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("save() persiste un snapshot et lui attribue un ID")
    void save_persistsSnapshotWithId() {
        OrbitalHistory saved = repository.save(issSnapshot(T0));
        assertThat(saved.getId()).isNotNull().isPositive();
    }

    @Test
    @DisplayName("save() préserve tous les champs correctement")
    void save_preservesAllFields() {
        OrbitalHistory saved = repository.save(issSnapshot(T0));
        assertThat(saved.getNoradId()).isEqualTo(NORAD_ISS);
        assertThat(saved.getSatelliteName()).isEqualTo("ISS (ZARYA)");
        assertThat(saved.getFetchedAt()).isEqualTo(T0);
        assertThat(saved.getSemiMajorAxisKm()).isEqualTo(6780.0);
        assertThat(saved.getEccentricity()).isEqualTo(0.0003);
        assertThat(saved.getInclinationDeg()).isEqualTo(51.64);
        assertThat(saved.getAltitudePerigeeKm()).isEqualTo(408.0);
        assertThat(saved.getAltitudeApogeeKm()).isEqualTo(412.0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // findByNoradIdOrderByFetchedAtDesc
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findByNoradId : retourne les snapshots du bon satellite uniquement")
    void findByNoradId_returnsOnlyMatchingSatellite() {
        repository.save(issSnapshot(T0));
        repository.save(cssSnapshot(T0));

        List<OrbitalHistory> results = repository.findByNoradIdOrderByFetchedAtDesc(
                NORAD_ISS, PageRequest.of(0, 10));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getNoradId()).isEqualTo(NORAD_ISS);
    }

    @Test
    @DisplayName("findByNoradId : ordre décroissant par fetchedAt respecté")
    void findByNoradId_orderedByFetchedAtDesc() {
        Instant t1 = T0;
        Instant t2 = T0.plus(6, ChronoUnit.HOURS);
        Instant t3 = T0.plus(12, ChronoUnit.HOURS);

        repository.save(issSnapshot(t1));
        repository.save(issSnapshot(t2));
        repository.save(issSnapshot(t3));

        List<OrbitalHistory> results = repository.findByNoradIdOrderByFetchedAtDesc(
                NORAD_ISS, PageRequest.of(0, 10));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getFetchedAt()).isEqualTo(t3); // le plus récent en premier
        assertThat(results.get(2).getFetchedAt()).isEqualTo(t1); // le plus ancien en dernier
    }

    @Test
    @DisplayName("findByNoradId : Pageable limite le nombre de résultats")
    void findByNoradId_pageableLimitsResults() {
        repository.save(issSnapshot(T0));
        repository.save(issSnapshot(T0.plus(6, ChronoUnit.HOURS)));
        repository.save(issSnapshot(T0.plus(12, ChronoUnit.HOURS)));

        List<OrbitalHistory> results = repository.findByNoradIdOrderByFetchedAtDesc(
                NORAD_ISS, PageRequest.of(0, 2));

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("findByNoradId : satellite inconnu → liste vide")
    void findByNoradId_unknownSatellite_returnsEmpty() {
        repository.save(issSnapshot(T0));

        List<OrbitalHistory> results = repository.findByNoradIdOrderByFetchedAtDesc(
                99999, PageRequest.of(0, 10));

        assertThat(results).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // findByNoradIdAndFetchedAtBetween
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findByNoradIdAndFetchedAtBetween : retourne uniquement les snapshots dans la fenêtre")
    void findByFetchedAtBetween_returnsOnlySnapshotsInWindow() {
        Instant t1 = T0.minus(25, ChronoUnit.HOURS);
        Instant t2 = T0.minus(12, ChronoUnit.HOURS);
        Instant t3 = T0;

        repository.save(issSnapshot(t1));
        repository.save(issSnapshot(t2));
        repository.save(issSnapshot(t3));

        // Fenêtre : les 24 dernières heures
        Instant from = T0.minus(24, ChronoUnit.HOURS);
        List<OrbitalHistory> results = repository
                .findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(NORAD_ISS, from, T0);

        assertThat(results).hasSize(2); // t2 et t3 seulement, t1 est hors fenêtre
        assertThat(results.get(0).getFetchedAt()).isEqualTo(t2);
        assertThat(results.get(1).getFetchedAt()).isEqualTo(t3);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // countByNoradId
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("countByNoradId : retourne le bon compte pour un satellite")
    void countByNoradId_returnsCorrectCount() {
        repository.save(issSnapshot(T0));
        repository.save(issSnapshot(T0.plus(6, ChronoUnit.HOURS)));
        repository.save(cssSnapshot(T0));

        assertThat(repository.countByNoradId(NORAD_ISS)).isEqualTo(2L);
        assertThat(repository.countByNoradId(NORAD_CSS)).isEqualTo(1L);
        assertThat(repository.countByNoradId(99999)).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // deleteByFetchedAtBefore (purge TTL)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("deleteByFetchedAtBefore : supprime les snapshots antérieurs à la date limite")
    void deleteByFetchedAtBefore_removesOldSnapshots() {
        Instant old1    = T0.minus(100, ChronoUnit.DAYS);
        Instant old2    = T0.minus(91, ChronoUnit.DAYS);
        Instant recent  = T0.minus(1, ChronoUnit.DAYS);
        Instant cutoff  = T0.minus(90, ChronoUnit.DAYS);

        repository.save(issSnapshot(old1));
        repository.save(issSnapshot(old2));
        repository.save(issSnapshot(recent));

        int deleted = repository.deleteByFetchedAtBefore(cutoff);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findAll().get(0).getFetchedAt()).isEqualTo(recent);
    }

    @Test
    @DisplayName("deleteByFetchedAtBefore : aucune suppression si tout est récent")
    void deleteByFetchedAtBefore_noDeleteIfAllRecent() {
        repository.save(issSnapshot(T0.minus(1, ChronoUnit.DAYS)));
        repository.save(issSnapshot(T0.minus(2, ChronoUnit.DAYS)));

        int deleted = repository.deleteByFetchedAtBefore(T0.minus(90, ChronoUnit.DAYS));

        assertThat(deleted).isZero();
        assertThat(repository.count()).isEqualTo(2L);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // findDistinctNoradIds
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findDistinctNoradIds : retourne tous les NORAD IDs distincts")
    void findDistinctNoradIds_returnsAllDistinctIds() {
        repository.save(issSnapshot(T0));
        repository.save(issSnapshot(T0.plus(6, ChronoUnit.HOURS)));
        repository.save(cssSnapshot(T0));

        List<Integer> noradIds = repository.findDistinctNoradIds();

        assertThat(noradIds)
                .hasSize(2)
                .containsExactlyInAnyOrder(NORAD_ISS, NORAD_CSS);
    }

    @Test
    @DisplayName("findDistinctNoradIds : table vide → liste vide")
    void findDistinctNoradIds_emptyTable_returnsEmpty() {
        assertThat(repository.findDistinctNoradIds()).isEmpty();
    }
}


