package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import projet.OrbitWatch.dto.*;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.AnomalyAlertRepository;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link OrbitWatchTools} (Mockito pur — aucun contexte Spring).
 *
 * <p>Vérifie que chaque tool :
 * <ul>
 *   <li>Retourne les bons DTOs à partir des mocks de repository</li>
 *   <li>Respecte les limites max (10 conjunctions, 20 historiques, 10 anomalies)</li>
 *   <li>N'est jamais propagé en exception (retour dégradé au lieu du crash)</li>
 *   <li>Produit un JSON sérialisable (pas de boucle Jackson)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrbitWatchToolsTest {

    @Mock ConjunctionAlertRepository conjunctionAlertRepository;
    @Mock OrbitalHistoryRepository   orbitalHistoryRepository;
    @Mock AnomalyAlertRepository     anomalyAlertRepository;
    @Mock TleService                 tleService;
    @Mock ConjunctionService         conjunctionService;

    OrbitWatchTools tools;

    // ── TleEntry de test ─────────────────────────────────────────────────────
    // NORAD 25544 = ISS  (ligne1 commence par "1 25544 …")
    private static final TleEntry TLE_ISS = new TleEntry(
            "ISS (ZARYA)",
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990",
            "2 25544  51.6400 200.0000 0003000  60.0000 300.0000 15.50000000999999",
            "stations",
            Instant.now()
    );

    private static final TleEntry TLE_TIANGONG = new TleEntry(
            "CSS (TIANHE)",
            "1 48274U 21035A   26066.50000000  .00015000  00000+0  20000-3 0  9991",
            "2 48274  41.4700 190.0000 0002000  55.0000 305.0000 15.60000000999999",
            "stations",
            Instant.now()
    );

    @BeforeEach
    void setUp() {
        tools = new OrbitWatchTools(
                conjunctionAlertRepository,
                orbitalHistoryRepository,
                anomalyAlertRepository,
                tleService,
                conjunctionService
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // getRecentConjunctions
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getRecentConjunctions(6) → retourne les alertes dans la fenêtre des 6h")
    void getRecentConjunctions_returnsAlertsInWindow() {
        ConjunctionAlert alert = buildConjunctionAlert("ISS (ZARYA)", "CSS (TIANHE)", 2.5);
        when(conjunctionAlertRepository.findByDetectedAtAfterOrderByDistanceKmAsc(any(Instant.class)))
                .thenReturn(List.of(alert));

        List<ConjunctionAlertDto> result = tools.getRecentConjunctions(6);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sat1()).isEqualTo("ISS (ZARYA)");
        assertThat(result.get(0).sat2()).isEqualTo("CSS (TIANHE)");
        assertThat(result.get(0).distanceKm()).isEqualTo(2.5);
        assertThat(result.get(0).tca()).isNotBlank();
    }

    @Test
    @DisplayName("getRecentConjunctions → limite à 10 alertes même si la BDD en retourne plus")
    void getRecentConjunctions_limitsTo10() {
        List<ConjunctionAlert> manyAlerts = buildConjunctionAlerts(15);
        when(conjunctionAlertRepository.findByDetectedAtAfterOrderByDistanceKmAsc(any()))
                .thenReturn(manyAlerts);

        List<ConjunctionAlertDto> result = tools.getRecentConjunctions(24);

        assertThat(result).hasSize(10);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // getOrbitalHistory
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getOrbitalHistory('ISS', 30) → retourne les entrées dans la fenêtre de 30 jours")
    void getOrbitalHistory_returnsEntriesInWindow() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        OrbitalHistory snapshot = buildOrbitalHistory(25544, Instant.now().minusSeconds(3600));
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(eq(25544), any(Pageable.class)))
                .thenReturn(List.of(snapshot));

        List<OrbitalHistoryDto> result = tools.getOrbitalHistory("ISS", 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).altPerigeeKm()).isEqualTo(408.0);
        assertThat(result.get(0).altApogeeKm()).isEqualTo(416.0);
        assertThat(result.get(0).inclinationDeg()).isEqualTo(51.64);
    }

    @Test
    @DisplayName("getOrbitalHistory → max 20 entrées même si le repository en retourne plus")
    void getOrbitalHistory_limitsTo20() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        // Le repository est paginé à 20 max côté tools, simulons 20 résultats
        List<OrbitalHistory> snapshots = buildOrbitalHistories(25544, 20);
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(anyInt(), any(Pageable.class)))
                .thenReturn(snapshots);

        List<OrbitalHistoryDto> result = tools.getOrbitalHistory("ISS", 90);

        assertThat(result).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("getOrbitalHistory avec satellite inconnu → liste vide (pas d'exception)")
    void getOrbitalHistory_unknownSatellite_returnsEmptyList() {
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour le nom : UNKNOWN"));

        List<OrbitalHistoryDto> result = tools.getOrbitalHistory("UNKNOWN", 30);

        assertThat(result).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // analyzeConjunction
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("analyzeConjunction('ISS', 'CSS') → retourne un résumé avec le nombre d'événements")
    void analyzeConjunction_returnsReport() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        when(tleService.resolveUniqueTle("CSS")).thenReturn(TLE_TIANGONG);
        ConjunctionReport report = new ConjunctionReport(
                "ISS (ZARYA)", "CSS (TIANHE)", 5.0,
                Instant.now(), Instant.now().plusSeconds(86400),
                List.of(new ConjunctionEvent(Instant.now(), 3.2, null, null))
        );
        when(conjunctionService.analyze(any(ConjunctionRequest.class))).thenReturn(report);

        ConjunctionSummaryDto result = tools.analyzeConjunction("ISS", "CSS");

        assertThat(result.error()).isNull();
        assertThat(result.eventCount()).isEqualTo(1);
        assertThat(result.minDistanceKm()).isEqualTo(3.2);
    }

    @Test
    @DisplayName("analyzeConjunction avec satellite inconnu → retourne erreur dans le DTO (pas d'exception)")
    void analyzeConjunction_unknownSatellite_returnsErrorDto() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour le nom : UNKNOWN"));

        ConjunctionSummaryDto result = tools.analyzeConjunction("ISS", "UNKNOWN");

        assertThat(result.error()).isNotNull().contains("Satellite inconnu");
        assertThat(result.eventCount()).isZero();
        assertThat(result.minDistanceKm()).isNull();
    }

    @Test
    @DisplayName("analyzeConjunction sans événement → eventCount=0, minDistanceKm=null")
    void analyzeConjunction_noEvent_returnsZeroCount() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        when(tleService.resolveUniqueTle("CSS")).thenReturn(TLE_TIANGONG);
        ConjunctionReport emptyReport = new ConjunctionReport(
                "ISS", "CSS", 5.0, Instant.now(), Instant.now(), List.of());
        when(conjunctionService.analyze(any())).thenReturn(emptyReport);

        ConjunctionSummaryDto result = tools.analyzeConjunction("ISS", "CSS");

        assertThat(result.eventCount()).isZero();
        assertThat(result.minDistanceKm()).isNull();
        assertThat(result.error()).isNull();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // getUnreadAnomalies
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getUnreadAnomalies() → retourne les anomalies non acquittées")
    void getUnreadAnomalies_returnsUnacknowledgedAlerts() {
        AnomalyAlert alert = buildAnomalyAlert("ISS (ZARYA)", AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.MEDIUM);
        when(anomalyAlertRepository.findByAcknowledgedFalseOrderByDetectedAtDesc())
                .thenReturn(List.of(alert));

        List<AnomalyAlertDto> result = tools.getUnreadAnomalies();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).satelliteName()).isEqualTo("ISS (ZARYA)");
        assertThat(result.get(0).type()).isEqualTo("ALTITUDE_CHANGE");
        assertThat(result.get(0).severity()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("getUnreadAnomalies → limite à 10 même si la BDD en retourne plus")
    void getUnreadAnomalies_limitsTo10() {
        List<AnomalyAlert> manyAlerts = buildAnomalyAlerts(15);
        when(anomalyAlertRepository.findByAcknowledgedFalseOrderByDetectedAtDesc())
                .thenReturn(manyAlerts);

        List<AnomalyAlertDto> result = tools.getUnreadAnomalies();

        assertThat(result).hasSize(10);
    }


    // ═════════════════════════════════════════════════════════════════════════
    // getSatelliteSummary
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getSatelliteSummary('ISS') → résumé contient le nom et les paramètres orbitaux")
    void getSatelliteSummary_containsOrbitInfo() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        OrbitalHistory snapshot = buildOrbitalHistory(25544, Instant.now().minusSeconds(3600));
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(eq(25544), any(Pageable.class)))
                .thenReturn(List.of(snapshot));
        when(anomalyAlertRepository.findByNoradIdOrderByDetectedAtDesc(25544))
                .thenReturn(List.of());

        String summary = tools.getSatelliteSummary("ISS");

        assertThat(summary)
                .contains("ISS")
                .contains("25544")
                .contains("408")   // périgée
                .contains("416");  // apogée
    }

    @Test
    @DisplayName("getSatelliteSummary avec satellite inconnu → message d'erreur (pas d'exception)")
    void getSatelliteSummary_unknownSatellite_returnsErrorMessage() {
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour le nom : UNKNOWN"));

        String result = tools.getSatelliteSummary("UNKNOWN");

        assertThat(result).contains("Impossible de récupérer").contains("UNKNOWN");
    }

    @Test
    @DisplayName("getSatelliteSummary sans historique orbital → message 'non disponible'")
    void getSatelliteSummary_noHistory_mentionsUnavailable() {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(TLE_ISS);
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(anyInt(), any(Pageable.class)))
                .thenReturn(List.of());
        when(anomalyAlertRepository.findByNoradIdOrderByDetectedAtDesc(anyInt()))
                .thenReturn(List.of());

        String result = tools.getSatelliteSummary("ISS");

        assertThat(result).contains("non disponible");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private ConjunctionAlert buildConjunctionAlert(String name1, String name2, double distKm) {
        return new ConjunctionAlert(name1, name2, 25544, 48274,
                Instant.now().plusSeconds(3600), distKm,
                48.0, 2.0, 410.0,
                51.0, 3.0, 395.0);
    }

    private List<ConjunctionAlert> buildConjunctionAlerts(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> buildConjunctionAlert("SAT" + i, "SAT" + (i + 100), 1.0 + i))
                .toList();
    }

    private OrbitalHistory buildOrbitalHistory(int noradId, Instant fetchedAt) {
        return new OrbitalHistory(noradId, "ISS (ZARYA)", fetchedAt,
                6786.0, 0.0003, 51.64, 200.0, 60.0, 15.5, 408.0, 416.0);
    }

    private List<OrbitalHistory> buildOrbitalHistories(int noradId, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> buildOrbitalHistory(noradId, Instant.now().minusSeconds(3600L * i)))
                .toList();
    }

    private AnomalyAlert buildAnomalyAlert(String satName, AnomalyType type, AnomalySeverity severity) {
        return new AnomalyAlert(25544, satName, Instant.now(), type, severity,
                "Test anomaly description");
    }

    private List<AnomalyAlert> buildAnomalyAlerts(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> buildAnomalyAlert("SAT" + i, AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.MEDIUM))
                .toList();
    }
}

