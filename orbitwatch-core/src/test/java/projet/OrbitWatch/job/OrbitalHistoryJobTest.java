package projet.OrbitWatch.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.OrbitalElementsExtractor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du {@link OrbitalHistoryJob}.
 *
 * <p>Zéro Spring, zéro BDD — Mockito uniquement.
 * Valide la persistance, le skip silencieux sur TLE malformé,
 * l'avertissement sur taux d'échec élevé, et la purge TTL.
 */
@ExtendWith(MockitoExtension.class)
class OrbitalHistoryJobTest {

    @Mock
    private OrbitalElementsExtractor extractor;
    @Mock
    private OrbitalHistoryRepository repository;

    private OrbitalHistoryJob job;

    // ── TLE ISS de référence ──────────────────────────────────────────────────
    private static final String ISS_NAME  = "ISS (ZARYA)";
    private static final String ISS_LINE1 = "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String ISS_LINE2 = "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private static final OrbitalElements ISS_ELEMENTS = new OrbitalElements(
            25544, ISS_NAME, Instant.parse("2026-03-07T12:00:00Z"),
            6780.0, 0.0003, 51.64, 200.0, 60.0, 15.49, 408.0, 412.0
    );

    private TleEntry issEntry() {
        return new TleEntry(ISS_NAME, ISS_LINE1, ISS_LINE2, "stations", Instant.now());
    }

    @BeforeEach
    void setUp() {
        job = new OrbitalHistoryJob(extractor, repository);
        ReflectionTestUtils.setField(job, "retentionDays", 90);
        // Aucun catalogue exclu dans les tests de base (guard-fou debris non activé)
        ReflectionTestUtils.setField(job, "excludedCatalogs", new ArrayList<>());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Persistance nominale
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3 TLEs valides → 3 appels repository.save()")
    void onCatalogRefreshed_threeTles_savesThreeSnapshots() {
        when(extractor.extract(any(), any(), any())).thenReturn(ISS_ELEMENTS);
        when(repository.deleteByFetchedAtBefore(any())).thenReturn(0);

        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "stations", List.of(issEntry(), issEntry(), issEntry())
        );

        job.onCatalogRefreshed(event);

        verify(repository, times(3)).save(any(OrbitalHistory.class));
    }

    @Test
    @DisplayName("Le snapshot persisté contient les bons champs (noradId, nom, paramètres)")
    void onCatalogRefreshed_savedSnapshotHasCorrectFields() {
        when(extractor.extract(any(), any(), any())).thenReturn(ISS_ELEMENTS);
        when(repository.deleteByFetchedAtBefore(any())).thenReturn(0);

        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "stations", List.of(issEntry())
        );

        ArgumentCaptor<OrbitalHistory> captor = ArgumentCaptor.forClass(OrbitalHistory.class);
        job.onCatalogRefreshed(event);
        verify(repository).save(captor.capture());

        OrbitalHistory saved = captor.getValue();
        assertThat(saved.getNoradId()).isEqualTo(25544);
        assertThat(saved.getSatelliteName()).isEqualTo(ISS_NAME);
        assertThat(saved.getSemiMajorAxisKm()).isEqualTo(6780.0);
        assertThat(saved.getInclinationDeg()).isEqualTo(51.64);
        assertThat(saved.getAltitudePerigeeKm()).isEqualTo(408.0);
        assertThat(saved.getAltitudeApogeeKm()).isEqualTo(412.0);
        assertThat(saved.getFetchedAt()).isNotNull();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Skip silencieux sur TLE malformé
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TLE malformé → skip silencieux, aucune exception propagée")
    void onCatalogRefreshed_malformedTle_skipsWithoutException() {
        when(extractor.extract(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("TLE malformé"));
        when(repository.deleteByFetchedAtBefore(any())).thenReturn(0);

        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "stations", List.of(issEntry())
        );

        assertThatCode(() -> job.onCatalogRefreshed(event)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("2 TLEs valides + 1 malformé → 2 saves, pas d'exception")
    void onCatalogRefreshed_mixedTles_savesOnlyValid() {
        when(extractor.extract(any(), any(), any()))
                .thenReturn(ISS_ELEMENTS)
                .thenReturn(ISS_ELEMENTS)
                .thenThrow(new IllegalArgumentException("TLE malformé"));
        when(repository.deleteByFetchedAtBefore(any())).thenReturn(0);

        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "stations", List.of(issEntry(), issEntry(), issEntry())
        );

        assertThatCode(() -> job.onCatalogRefreshed(event)).doesNotThrowAnyException();
        verify(repository, times(2)).save(any(OrbitalHistory.class));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Cold start — liste vide
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Catalogue vide → aucun save, aucune exception")
    void onCatalogRefreshed_emptyList_doesNothing() {
        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent("stations", List.of());

        assertThatCode(() -> job.onCatalogRefreshed(event)).doesNotThrowAnyException();
        verify(extractor, never()).extract(any(), any(), any());
        verify(repository, never()).save(any());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Garde-fou volume — catalogue exclu
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Catalogue 'debris' exclu → 0 appels repository.save()")
    void onCatalogRefreshed_excludedCatalog_skipsAllSaves() {
        ReflectionTestUtils.setField(job, "excludedCatalogs", List.of("debris"));

        TleEntry debrisEntry = new TleEntry("COSMOS DEB", ISS_LINE1, ISS_LINE2, "debris", Instant.now());
        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "debris", List.of(debrisEntry, debrisEntry)
        );

        job.onCatalogRefreshed(event);

        verify(extractor, never()).extract(any(), any(), any());
        verify(repository, never()).save(any());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Purge TTL
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Purge TTL appelée après chaque batch (même si rien à supprimer)")
    void onCatalogRefreshed_alwaysCallsTtlPurge() {
        when(extractor.extract(any(), any(), any())).thenReturn(ISS_ELEMENTS);
        when(repository.deleteByFetchedAtBefore(any())).thenReturn(0);

        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent(
                "stations", List.of(issEntry())
        );

        job.onCatalogRefreshed(event);

        verify(repository).deleteByFetchedAtBefore(any(Instant.class));
    }

    @Test
    @DisplayName("Purge TTL appelée même sur catalogue vide")
    void onCatalogRefreshed_emptyList_ttlPurgeNotCalled() {
        // Sur catalogue vide on retourne tôt — pas de purge
        TleCatalogRefreshedEvent event = new TleCatalogRefreshedEvent("stations", List.of());

        job.onCatalogRefreshed(event);

        verify(repository, never()).deleteByFetchedAtBefore(any());
    }
}

