package projet.OrbitWatch.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.AnomalyDetectionService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du {@link AnomalyScanJob}.
 * Zéro Spring, zéro BDD — Mockito uniquement.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyScanJobTest {

    @Mock private AnomalyDetectionService  detectionService;
    @Mock private AnomalyAlertRepository   alertRepository;
    @Mock private OrbitalHistoryRepository historyRepository;

    private AnomalyScanJob job;

    private static final int NORAD_ISS = 25544;
    private static final int NORAD_CSS = 48274;

    @BeforeEach
    void setUp() {
        job = new AnomalyScanJob(detectionService, alertRepository, historyRepository);
    }

    private AnomalyAlert anomaly(int noradId, AnomalyType type) {
        return new AnomalyAlert(noradId, "SAT-" + noradId, Instant.now(),
                type, AnomalySeverity.MEDIUM, "Test anomaly");
    }

    // ── scan() ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan : table vide → cold start safe, aucun appel au service")
    void scan_emptyHistory_coldStartSafe() {
        when(historyRepository.findDistinctNoradIds()).thenReturn(List.of());
        job.scan();
        verifyNoInteractions(detectionService);
        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("scan : 1 satellite, 1 alerte règle métier → 1 save")
    void scan_oneAlertRuleBased_persisted() {
        when(historyRepository.findDistinctNoradIds()).thenReturn(List.of(NORAD_ISS));
        when(detectionService.detectRuleBased(NORAD_ISS))
                .thenReturn(List.of(anomaly(NORAD_ISS, AnomalyType.ALTITUDE_CHANGE)));
        when(detectionService.detectZScore(eq(NORAD_ISS), anyInt())).thenReturn(List.of());
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.ALTITUDE_CHANGE), any())).thenReturn(false);

        job.scan();

        verify(alertRepository, times(1)).save(any(AnomalyAlert.class));
    }

    @Test
    @DisplayName("scan : alerte dupliquée → save non appelé")
    void scan_duplicateAlert_notPersisted() {
        when(historyRepository.findDistinctNoradIds()).thenReturn(List.of(NORAD_ISS));
        when(detectionService.detectRuleBased(NORAD_ISS))
                .thenReturn(List.of(anomaly(NORAD_ISS, AnomalyType.ALTITUDE_CHANGE)));
        when(detectionService.detectZScore(eq(NORAD_ISS), anyInt())).thenReturn(List.of());
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.ALTITUDE_CHANGE), any())).thenReturn(true);

        job.scan();

        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("scan : 2 satellites, alertes distinctes → 2 saves")
    void scan_twoSatellites_bothPersisted() {
        when(historyRepository.findDistinctNoradIds()).thenReturn(List.of(NORAD_ISS, NORAD_CSS));
        when(detectionService.detectRuleBased(NORAD_ISS))
                .thenReturn(List.of(anomaly(NORAD_ISS, AnomalyType.ALTITUDE_CHANGE)));
        when(detectionService.detectRuleBased(NORAD_CSS))
                .thenReturn(List.of(anomaly(NORAD_CSS, AnomalyType.INCLINATION_CHANGE)));
        when(detectionService.detectZScore(anyInt(), anyInt())).thenReturn(List.of());
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(anyInt(), any(), any()))
                .thenReturn(false);

        job.scan();

        verify(alertRepository, times(2)).save(any(AnomalyAlert.class));
    }

    @Test
    @DisplayName("scan : alerte règle + alerte Z-score → 2 saves")
    void scan_ruleAndZScore_bothPersisted() {
        when(historyRepository.findDistinctNoradIds()).thenReturn(List.of(NORAD_ISS));
        when(detectionService.detectRuleBased(NORAD_ISS))
                .thenReturn(List.of(anomaly(NORAD_ISS, AnomalyType.ALTITUDE_CHANGE)));
        when(detectionService.detectZScore(eq(NORAD_ISS), anyInt()))
                .thenReturn(List.of(anomaly(NORAD_ISS, AnomalyType.STATISTICAL)));
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(anyInt(), any(), any()))
                .thenReturn(false);

        job.scan();

        verify(alertRepository, times(2)).save(any(AnomalyAlert.class));
    }

    // ── persist() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("persist : liste vide → 0 saves, retourne 0")
    void persist_emptyList_returnsZero() {
        int count = job.persist(List.of());
        assertThat(count).isZero();
        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("persist : nouvelle alerte → 1 save, retourne 1")
    void persist_newAlert_savesAndReturnsOne() {
        AnomalyAlert alert = anomaly(NORAD_ISS, AnomalyType.RAAN_DRIFT);
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.RAAN_DRIFT), any())).thenReturn(false);

        int count = job.persist(List.of(alert));

        assertThat(count).isEqualTo(1);
        verify(alertRepository).save(alert);
    }

    @Test
    @DisplayName("persist : alerte dupliquée → 0 save, retourne 0")
    void persist_duplicateAlert_returnsZero() {
        AnomalyAlert alert = anomaly(NORAD_ISS, AnomalyType.RAAN_DRIFT);
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.RAAN_DRIFT), any())).thenReturn(true);

        int count = job.persist(List.of(alert));

        assertThat(count).isZero();
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("persist : 2 alertes dont 1 dupliquée → 1 save, retourne 1")
    void persist_oneNewOneDuplicate_savesOne() {
        AnomalyAlert newAlert = anomaly(NORAD_ISS, AnomalyType.ALTITUDE_CHANGE);
        AnomalyAlert dupAlert = anomaly(NORAD_ISS, AnomalyType.RAAN_DRIFT);

        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.ALTITUDE_CHANGE), any())).thenReturn(false);
        when(alertRepository.existsByNoradIdAndTypeAndDetectedAtAfter(
                eq(NORAD_ISS), eq(AnomalyType.RAAN_DRIFT), any())).thenReturn(true);

        int count = job.persist(List.of(newAlert, dupAlert));

        assertThat(count).isEqualTo(1);
        verify(alertRepository, times(1)).save(newAlert);
        verify(alertRepository, never()).save(dupAlert);
    }
}

