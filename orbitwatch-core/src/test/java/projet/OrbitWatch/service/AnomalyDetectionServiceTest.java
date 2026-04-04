package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires purs du {@link AnomalyDetectionService}.
 *
 * <p>Zéro dépendance Spring, zéro BDD — données synthétiques injectées via Mockito.
 * Patron identique à {@code ConjunctionServiceTest}.
 */
class AnomalyDetectionServiceTest {

    private OrbitalHistoryRepository repository;
    private AnomalyDetectionService  service;

    private static final int NORAD_ISS = 25544;
    private static final Instant T0    = Instant.parse("2026-04-04T12:00:00Z");

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OrbitalHistoryRepository.class);
        service    = new AnomalyDetectionService(repository);
        // Injecte les seuils par défaut via réflexion (les @Value ne sont pas gérés hors Spring)
        injectThresholds(service);
    }

    /** Injecte les valeurs par défaut des @Value via réflexion. */
    private void injectThresholds(AnomalyDetectionService svc) {
        try {
            setField(svc, "thresholdAltitudeKm",    10.0);
            setField(svc, "thresholdInclinationDeg",  0.5);
            setField(svc, "thresholdRaanDeg",          1.0);
            setField(svc, "thresholdEccentricity",  0.001);
            setField(svc, "zscoreThreshold",           3.0);
            setField(svc, "minHistory",                 30);
        } catch (Exception e) {
            throw new RuntimeException("Injection des seuils échouée", e);
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrbitalHistory snapshot(Instant fetchedAt, double perigeeKm, double apogeeKm,
                                    double inclDeg, double raanDeg, double ecc) {
        return new OrbitalHistory(
                NORAD_ISS, "ISS (ZARYA)", fetchedAt,
                6780.0, ecc, inclDeg, raanDeg, 60.0, 15.49, perigeeKm, apogeeKm
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // detectRuleBased() — Phase 1
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("detectRuleBased : 0 snapshot → cold start safe, liste vide, aucune exception")
    void detectRuleBased_noSnapshots_returnsEmpty() {
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of());

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("detectRuleBased : 1 snapshot (< 2) → cold start safe, liste vide")
    void detectRuleBased_oneSnapshot_returnsEmpty() {
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(snapshot(T0, 408.0, 412.0, 51.64, 200.0, 0.0003)));

        assertThat(service.detectRuleBased(NORAD_ISS)).isEmpty();
    }

    @Test
    @DisplayName("detectRuleBased : delta altitude périgée > seuil → ALTITUDE_CHANGE détecté")
    void detectRuleBased_altitudeChange_detected() {
        OrbitalHistory recent = snapshot(T0,            408.0 + 15.0, 412.0, 51.64, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64, 200.0, 0.0003);

        // recent en premier (order by fetched_at DESC)
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.ALTITUDE_CHANGE);
        assertThat(alerts.get(0).getNoradId()).isEqualTo(NORAD_ISS);
        assertThat(alerts.get(0).getDescription()).contains("Perigee");
        assertThat(alerts.get(0).isAcknowledged()).isFalse();
    }

    @Test
    @DisplayName("detectRuleBased : delta altitude apogée > seuil → ALTITUDE_CHANGE détecté")
    void detectRuleBased_apogeeChange_detected() {
        OrbitalHistory recent = snapshot(T0,            408.0, 412.0 + 20.0, 51.64, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0,        51.64, 200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.ALTITUDE_CHANGE);
        assertThat(alerts.get(0).getDescription()).contains("Apogee");
    }

    @Test
    @DisplayName("detectRuleBased : delta inclinaison > seuil → INCLINATION_CHANGE détecté")
    void detectRuleBased_inclinationChange_detected() {
        OrbitalHistory recent = snapshot(T0,            408.0, 412.0, 51.64 + 1.0, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64,       200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.INCLINATION_CHANGE);
    }

    @Test
    @DisplayName("detectRuleBased : delta RAAN > seuil → RAAN_DRIFT détecté")
    void detectRuleBased_raanDrift_detected() {
        OrbitalHistory recent = snapshot(T0,            408.0, 412.0, 51.64, 200.0 + 2.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64, 200.0,       0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.RAAN_DRIFT);
    }

    @Test
    @DisplayName("detectRuleBased : delta excentricité > seuil → ECCENTRICITY_CHANGE détecté")
    void detectRuleBased_eccentricityChange_detected() {
        OrbitalHistory recent = snapshot(T0,            408.0, 412.0, 51.64, 200.0, 0.0003 + 0.002);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64, 200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.ECCENTRICITY_CHANGE);
    }

    @Test
    @DisplayName("detectRuleBased : aucune variation > seuil → liste vide")
    void detectRuleBased_noAnomaly_returnsEmpty() {
        OrbitalHistory recent = snapshot(T0,            408.1, 412.1, 51.641, 200.1, 0.00031);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.640, 200.0, 0.00030);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        assertThat(service.detectRuleBased(NORAD_ISS)).isEmpty();
    }

    @Test
    @DisplayName("detectRuleBased : multiple anomalies simultanées → toutes détectées")
    void detectRuleBased_multipleAnomalies_allDetected() {
        OrbitalHistory recent = snapshot(T0,            408.0 + 15.0, 412.0, 51.64 + 1.0, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0,        412.0, 51.64,        200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        List<AnomalyAlert> alerts = service.detectRuleBased(NORAD_ISS);

        assertThat(alerts).hasSize(2);
        assertThat(alerts).extracting(AnomalyAlert::getType)
                .containsExactlyInAnyOrder(AnomalyType.ALTITUDE_CHANGE, AnomalyType.INCLINATION_CHANGE);
    }

    @Test
    @DisplayName("detectRuleBased : sévérité LOW pour ratio < 2")
    void detectRuleBased_severity_low() {
        // delta = 12 km, seuil = 10 km → ratio = 1.2 → LOW
        OrbitalHistory recent = snapshot(T0,            408.0 + 12.0, 412.0, 51.64, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64, 200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        assertThat(service.detectRuleBased(NORAD_ISS).get(0).getSeverity())
                .isEqualTo(AnomalySeverity.LOW);
    }

    @Test
    @DisplayName("detectRuleBased : sévérité HIGH pour ratio >= 5")
    void detectRuleBased_severity_high() {
        // delta = 55 km, seuil = 10 km → ratio = 5.5 → HIGH
        OrbitalHistory recent = snapshot(T0,            408.0 + 55.0, 412.0, 51.64, 200.0, 0.0003);
        OrbitalHistory prev   = snapshot(T0.minus(6, ChronoUnit.HOURS), 408.0, 412.0, 51.64, 200.0, 0.0003);

        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(recent, prev));

        assertThat(service.detectRuleBased(NORAD_ISS).get(0).getSeverity())
                .isEqualTo(AnomalySeverity.HIGH);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // detectZScore() — Phase 2
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("detectZScore : < minHistory points → cold start safe, liste vide")
    void detectZScore_insufficientHistory_returnsEmpty() {
        when(repository.countByNoradId(NORAD_ISS)).thenReturn(10L); // < 30

        List<AnomalyAlert> alerts = service.detectZScore(NORAD_ISS, 30);

        assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("detectZScore : outlier injecté avec Z-score > 3.0 → STATISTICAL détecté")
    void detectZScore_outlier_detected() {
        when(repository.countByNoradId(NORAD_ISS)).thenReturn(50L); // >= 30

        // 30 valeurs normales autour de 408 km, + 1 outlier à 458 km en premier (le plus récent)
        List<OrbitalHistory> snapshots = buildWindowWithOutlier(30, 408.0, 2.0, 458.0);
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(snapshots);

        List<AnomalyAlert> alerts = service.detectZScore(NORAD_ISS, 30);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo(AnomalyType.STATISTICAL);
        assertThat(alerts.get(0).getDescription()).contains("Z-score");
    }

    @Test
    @DisplayName("detectZScore : série normale (pas d'outlier) → liste vide")
    void detectZScore_normalSeries_returnsEmpty() {
        when(repository.countByNoradId(NORAD_ISS)).thenReturn(50L);

        // 30 valeurs avec faible variation → Z-score du dernier point < 3.0
        List<OrbitalHistory> snapshots = buildNormalWindow(30, 408.0, 0.5);
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(snapshots);

        assertThat(service.detectZScore(NORAD_ISS, 30)).isEmpty();
    }

    @Test
    @DisplayName("detectZScore : série constante (stddev=0) → liste vide, aucune exception")
    void detectZScore_constantSeries_returnsEmpty() {
        when(repository.countByNoradId(NORAD_ISS)).thenReturn(50L);

        List<OrbitalHistory> snapshots = buildConstantWindow(30, 408.0);
        when(repository.findByNoradIdOrderByFetchedAtDesc(eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(snapshots);

        assertThat(service.detectZScore(NORAD_ISS, 30)).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Utilitaires statiques — mean / stddev / severity
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("mean : valeurs connues → résultat exact")
    void mean_knownValues() {
        assertThat(AnomalyDetectionService.mean(new double[]{1.0, 2.0, 3.0})).isEqualTo(2.0);
    }

    @Test
    @DisplayName("stddev : valeurs connues → résultat exact")
    void stddev_knownValues() {
        double[] values = {2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0};
        double mean = AnomalyDetectionService.mean(values);
        assertThat(AnomalyDetectionService.stddev(values, mean)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("severity : ratio < 2 → LOW")
    void severity_low() {
        assertThat(AnomalyDetectionService.severity(15.0, 10.0)).isEqualTo(AnomalySeverity.LOW);
    }

    @Test
    @DisplayName("severity : ratio ∈ [2, 5) → MEDIUM")
    void severity_medium() {
        assertThat(AnomalyDetectionService.severity(30.0, 10.0)).isEqualTo(AnomalySeverity.MEDIUM);
    }

    @Test
    @DisplayName("severity : ratio >= 5 → HIGH")
    void severity_high() {
        assertThat(AnomalyDetectionService.severity(60.0, 10.0)).isEqualTo(AnomalySeverity.HIGH);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Builders de fenêtres synthétiques
    // ═════════════════════════════════════════════════════════════════════════

    /** 30 snapshots normaux avec petit bruit, + outlier en index 0 (le plus récent). */
    private List<OrbitalHistory> buildWindowWithOutlier(int n, double mean, double noise, double outlierValue) {
        List<OrbitalHistory> list = new java.util.ArrayList<>();
        // index 0 = plus récent = outlier
        list.add(snapshot(T0, outlierValue, outlierValue + 4.0, 51.64, 200.0, 0.0003));
        for (int i = 1; i < n; i++) {
            double val = mean + (i % 2 == 0 ? noise : -noise);
            list.add(snapshot(T0.minus(i * 6L, ChronoUnit.HOURS), val, val + 4.0, 51.64, 200.0, 0.0003));
        }
        return list;
    }

    /** n snapshots avec faible bruit — tous dans la norme. */
    private List<OrbitalHistory> buildNormalWindow(int n, double mean, double noise) {
        List<OrbitalHistory> list = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double val = mean + (i % 2 == 0 ? noise * 0.3 : -noise * 0.3);
            list.add(snapshot(T0.minus(i * 6L, ChronoUnit.HOURS), val, val + 4.0, 51.64, 200.0, 0.0003));
        }
        return list;
    }

    /** n snapshots tous identiques (série constante). */
    private List<OrbitalHistory> buildConstantWindow(int n, double value) {
        List<OrbitalHistory> list = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(snapshot(T0.minus(i * 6L, ChronoUnit.HOURS), value, value + 4.0, 51.64, 200.0, 0.0003));
        }
        return list;
    }
}

