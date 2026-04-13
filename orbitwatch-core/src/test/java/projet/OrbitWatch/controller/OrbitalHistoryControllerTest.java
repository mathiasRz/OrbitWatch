package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.model.OrbitalHistory;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.repository.OrbitalHistoryRepository;
import projet.OrbitWatch.service.OrbitalElementsExtractor;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests MockMvc du {@link OrbitalHistoryController}.
 *
 * <p>Tous les accès BDD et services sont mockés — aucune dépendance externe.
 */
@WebMvcTest({OrbitalHistoryController.class, GlobalExceptionHandler.class})
class OrbitalHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private OrbitalHistoryRepository   orbitalHistoryRepository;
    @MockitoBean private ConjunctionAlertRepository conjunctionAlertRepository;
    @MockitoBean private TleService                 tleService;
    @MockitoBean private OrbitalElementsExtractor   extractor;

    private static final int    NORAD_ISS = 25544;
    private static final Instant T0       = Instant.parse("2026-04-03T12:00:00Z");

    /** Crée un snapshot ISS minimal pour les tests. */
    private OrbitalHistory issSnapshot(Instant fetchedAt) {
        return new OrbitalHistory(
                NORAD_ISS, "ISS (ZARYA)", fetchedAt,
                6780.0, 0.0003, 51.64, 200.0, 60.0, 15.49, 408.0, 412.0
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /orbital-history/{noradId}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /orbital-history/{noradId} : retourne 200 avec la liste des snapshots")
    void getHistory_returns200WithSnapshots() throws Exception {
        List<OrbitalHistory> snapshots = List.of(
                issSnapshot(T0.minus(12, ChronoUnit.HOURS)),
                issSnapshot(T0)
        );
        when(orbitalHistoryRepository.findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
                eq(NORAD_ISS), any(Instant.class), any(Instant.class)))
                .thenReturn(snapshots);

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}", NORAD_ISS)
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].noradId", is(NORAD_ISS)))
                .andExpect(jsonPath("$[0].inclinationDeg", is(51.64)))
                .andExpect(jsonPath("$[1].altitudePerigeeKm", is(408.0)));
    }

    @Test
    @DisplayName("GET /orbital-history/{noradId} : aucun snapshot → 200 avec liste vide")
    void getHistory_noSnapshots_returns200WithEmptyList() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
                anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}", 99999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /orbital-history/{noradId} : days par défaut est 30")
    void getHistory_defaultDaysIs30() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
                anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(issSnapshot(T0)));

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /orbital-history/{noradId}/latest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /orbital-history/{noradId}/latest : retourne 200 avec le dernier snapshot")
    void getLatest_returns200WithLatestSnapshot() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(issSnapshot(T0)));

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}/latest", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noradId", is(NORAD_ISS)))
                .andExpect(jsonPath("$.satelliteName", is("ISS (ZARYA)")))
                .andExpect(jsonPath("$.semiMajorAxisKm", is(6780.0)))
                .andExpect(jsonPath("$.eccentricity", is(0.0003)))
                .andExpect(jsonPath("$.inclinationDeg", is(51.64)))
                .andExpect(jsonPath("$.altitudePerigeeKm", is(408.0)))
                .andExpect(jsonPath("$.altitudeApogeeKm", is(412.0)));
    }

    @Test
    @DisplayName("GET /orbital-history/{noradId}/latest : aucun snapshot → 404")
    void getLatest_noSnapshot_returns404() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                anyInt(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}/latest", 99999))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /satellite/{noradId}/summary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /satellite/{noradId}/summary : retourne 200 avec le profil agrégé")
    void getSummary_returns200WithSummary() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(issSnapshot(T0)));
        when(tleService.findAll()).thenReturn(List.of());
        when(conjunctionAlertRepository.findByAcknowledgedFalseOrderByTcaAsc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/satellite/{noradId}/summary", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noradId", is(NORAD_ISS)))
                .andExpect(jsonPath("$.name", is("ISS (ZARYA)")))
                .andExpect(jsonPath("$.latestElements.inclinationDeg", is(51.64)))
                .andExpect(jsonPath("$.textSummary", containsString("ISS (ZARYA)")))
                .andExpect(jsonPath("$.textSummary", containsString("25544")));
    }

    @Test
    @DisplayName("GET /satellite/{noradId}/summary : conjunctions filtrées par nom du satellite")
    void getSummary_filtersConjunctionsByName() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(issSnapshot(T0)));
        when(tleService.findAll()).thenReturn(List.of());

        ConjunctionAlert issAlert = new ConjunctionAlert(
                "ISS (ZARYA)", "CSS", 25544, 48274, T0, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        ConjunctionAlert otherAlert = new ConjunctionAlert(
                "SENTINEL-1A", "CSS", 39634, 48274, T0, 5.0, 10.0, 5.0, 500.0, 15.0, 20.0, 510.0);
        when(conjunctionAlertRepository.findByAcknowledgedFalseOrderByTcaAsc())
                .thenReturn(List.of(issAlert, otherAlert));

        mockMvc.perform(get("/api/v1/satellite/{noradId}/summary", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentConjunctions", hasSize(1)))
                .andExpect(jsonPath("$.recentConjunctions[0].nameSat1", is("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("GET /satellite/{noradId}/summary : aucun snapshot → 404")
    void getSummary_noSnapshot_returns404() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                anyInt(), any(Pageable.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/satellite/{noradId}/summary", 99999))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /satellite/{noradId}/summary : TLE âgé → textSummary contient le warning")
    void getSummary_oldTle_textSummaryContainsWarning() throws Exception {
        // Snapshot avec une époque TLE vieille de 10 jours
        Instant oldEpoch = Instant.now().minus(10, ChronoUnit.DAYS);
        when(orbitalHistoryRepository.findByNoradIdOrderByFetchedAtDesc(
                eq(NORAD_ISS), any(Pageable.class)))
                .thenReturn(List.of(issSnapshot(oldEpoch)));
        when(tleService.findAll()).thenReturn(List.of());
        when(conjunctionAlertRepository.findByAcknowledgedFalseOrderByTcaAsc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/satellite/{noradId}/summary", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textSummary", containsString("TLE OBSOLÈTE")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /orbital-history/{noradId}/export
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /orbital-history/{noradId}/export : retourne 200 avec Content-Disposition CSV")
    void exportCsv_returns200WithCsvFile() throws Exception {
        List<OrbitalHistory> snapshots = List.of(
                issSnapshot(T0.minus(6, ChronoUnit.HOURS)),
                issSnapshot(T0)
        );
        when(orbitalHistoryRepository.findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
                eq(NORAD_ISS), any(Instant.class), any(Instant.class)))
                .thenReturn(snapshots);

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}/export", NORAD_ISS))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=orbital-history-25544.csv"))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("fetchedAt,noradId,satelliteName")))
                .andExpect(content().string(containsString("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("GET /orbital-history/{noradId}/export : aucun snapshot → CSV avec en-tête uniquement")
    void exportCsv_noSnapshots_returnsCsvHeaderOnly() throws Exception {
        when(orbitalHistoryRepository.findByNoradIdAndFetchedAtBetweenOrderByFetchedAtAsc(
                anyInt(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbital-history/{noradId}/export", 99999))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fetchedAt,noradId")))
                .andExpect(content().string(not(containsString("ISS"))));
    }
}


