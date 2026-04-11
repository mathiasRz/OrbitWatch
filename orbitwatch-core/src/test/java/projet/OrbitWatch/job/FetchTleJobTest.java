package projet.OrbitWatch.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import projet.OrbitWatch.client.TleSourceClient;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du FetchTleJob (Chain of Responsibility).
 * Valide le fallback entre sources, la gestion des erreurs et la publication d'events.
 */
@ExtendWith(MockitoExtension.class)
class FetchTleJobTest {

    @Mock private TleSourceClient primarySource;
    @Mock private TleSourceClient fallbackSource;
    @Mock private TleService tleService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FetchTleJob job;

    private static final String RAW_TLE =
            "ISS (ZARYA)\n" +
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990\n" +
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private static final TleEntry ISS_ENTRY = new TleEntry(
            "ISS (ZARYA)",
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990",
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999",
            "stations", Instant.now());

    @BeforeEach
    void setUp() {
        when(primarySource.sourceName()).thenReturn("celestrak");
        lenient().when(fallbackSource.sourceName()).thenReturn("spacetrack");
        job = new FetchTleJob(List.of(primarySource, fallbackSource), tleService, eventPublisher);
        ReflectionTestUtils.setField(job, "catalogsConfig", "stations");
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : source primaire OK — fallback non appelé")
    void fetchCatalogWithFallback_primaryOk_fallbackNotCalled() {
        when(primarySource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.fetchCatalogWithFallback("stations");

        verify(primarySource).getCatalog("stations");
        verifyNoInteractions(fallbackSource);
        verify(eventPublisher).publishEvent(any(TleCatalogRefreshedEvent.class));
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : primaire KO → fallback utilisé avec succès")
    void fetchCatalogWithFallback_primaryFails_fallbackSucceeds() {
        when(primarySource.getCatalog("stations"))
                .thenThrow(new IllegalStateException("CelesTrak down"));
        when(fallbackSource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.fetchCatalogWithFallback("stations");

        verify(fallbackSource).getCatalog("stations");
        verify(eventPublisher).publishEvent(any(TleCatalogRefreshedEvent.class));
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : primaire vide → fallback utilisé")
    void fetchCatalogWithFallback_primaryEmpty_fallbackUsed() {
        when(primarySource.getCatalog("stations")).thenReturn("   ");
        when(fallbackSource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.fetchCatalogWithFallback("stations");

        verify(fallbackSource).getCatalog("stations");
        verify(eventPublisher).publishEvent(any(TleCatalogRefreshedEvent.class));
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : primaire parse 0 TLE → fallback utilisé")
    void fetchCatalogWithFallback_primaryParsesZeroTle_fallbackUsed() {
        when(primarySource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(fallbackSource.getCatalog("stations")).thenReturn(RAW_TLE);
        // 1er appel (source primaire) → 0 TLE, 2ème appel (fallback) → 1 TLE
        when(tleService.parseTle3Lines(RAW_TLE, "stations"))
                .thenReturn(List.of())
                .thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.fetchCatalogWithFallback("stations");

        verify(fallbackSource).getCatalog("stations");
        verify(eventPublisher).publishEvent(any(TleCatalogRefreshedEvent.class));
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : toutes sources KO — pas d'exception propagée, aucun event")
    void fetchCatalogWithFallback_allSourcesFail_noExceptionNoEvent() {
        when(primarySource.getCatalog("stations"))
                .thenThrow(new IllegalStateException("CelesTrak KO"));
        when(fallbackSource.getCatalog("stations"))
                .thenThrow(new IllegalStateException("SpaceTrack KO"));

        assertThatCode(() -> job.fetchCatalogWithFallback("stations"))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("refreshAll : itère sur tous les catalogues configurés")
    void refreshAll_iteratesAllCatalogs() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "stations,active");
        when(primarySource.getCatalog(anyString())).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(anyString(), anyString())).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(2L);

        job.refreshAll();

        verify(primarySource).getCatalog("stations");
        verify(primarySource).getCatalog("active");
    }

    @Test
    @DisplayName("refreshAll : un catalogue KO n'empêche pas les autres d'être traités")
    void refreshAll_oneCatalogFails_othersStillProcessed() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "failing,stations");
        when(primarySource.getCatalog("failing")).thenThrow(new IllegalStateException("KO"));
        when(fallbackSource.getCatalog("failing")).thenThrow(new IllegalStateException("KO"));
        when(primarySource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(1L);

        assertThatCode(() -> job.refreshAll()).doesNotThrowAnyException();

        verify(tleService).parseTle3Lines(RAW_TLE, "stations");
    }

    @Test
    @DisplayName("fetchCatalogWithFallback : l'event contient le bon catalogName et les entries")
    void fetchCatalogWithFallback_publishesEventWithCorrectData() {
        when(primarySource.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.fetchCatalogWithFallback("stations");

        // Vérifie que publishEvent est appelé avec un objet de type TleCatalogRefreshedEvent
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Object captured = captor.getValue();
        assertThat(captured).isInstanceOf(TleCatalogRefreshedEvent.class);
        TleCatalogRefreshedEvent event = (TleCatalogRefreshedEvent) captured;
        assertThat(event.catalogName()).isEqualTo("stations");
        assertThat(event.entries()).containsExactly(ISS_ENTRY);
    }
}



