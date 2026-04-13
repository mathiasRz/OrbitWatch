package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.dto.ConjunctionEvent;
import projet.OrbitWatch.dto.ConjunctionReport;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.model.ConjunctionAlert;
import projet.OrbitWatch.repository.ConjunctionAlertRepository;
import projet.OrbitWatch.service.ConjunctionService;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests MockMvc du ConjunctionController.
 */
@WebMvcTest({ConjunctionController.class, GlobalExceptionHandler.class})
class ConjunctionControllerTest {

    @Autowired private MockMvc      mockMvc;

    @MockitoBean private ConjunctionService         conjunctionService;
    @MockitoBean private ConjunctionAlertRepository repository;
    @MockitoBean private TleService                 tleService;

    private static final String TLE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String TLE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private static final Instant NOW = Instant.parse("2026-03-21T12:00:00Z");

    private static final SatellitePosition POS1 =
            new SatellitePosition("ISS", NOW, 45.0, 10.0, 410.0, 1000.0, 2000.0, 3000.0);
    private static final SatellitePosition POS2 =
            new SatellitePosition("CSS", NOW, 40.0, 15.0, 390.0, 1100.0, 2100.0, 3100.0);

    // ─────────────────────────────────────────────────────────────────────────
    // POST /analyze
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /analyze : retourne 200 avec un ConjunctionReport valide")
    void analyze_returns200WithReport() throws Exception {
        ConjunctionEvent  event  = new ConjunctionEvent(NOW, 3.5, POS1, POS2);
        ConjunctionReport report = new ConjunctionReport("ISS", "CSS", 5.0,
                NOW, NOW.plusSeconds(86400), List.of(event));

        when(conjunctionService.analyze(any())).thenReturn(report);

        String body = """
                {"nameSat1":"ISS","tle1Sat1":"%s","tle2Sat1":"%s",
                 "nameSat2":"CSS","tle1Sat2":"%s","tle2Sat2":"%s",
                 "durationHours":24.0,"stepSeconds":60,"thresholdKm":5.0}
                """.formatted(TLE1, TLE2, TLE1, TLE2);

        mockMvc.perform(post("/api/v1/conjunction/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameSat1", is("ISS")))
                .andExpect(jsonPath("$.nameSat2", is("CSS")))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].distanceKm", is(3.5)));
    }

    @Test
    @DisplayName("POST /analyze : nameSat1 absent → 400")
    void analyze_missingName_returns400() throws Exception {
        String body = """
                {"tle1Sat1":"%s","tle2Sat1":"%s",
                 "nameSat2":"CSS","tle1Sat2":"%s","tle2Sat2":"%s",
                 "durationHours":24.0,"stepSeconds":60,"thresholdKm":5.0}
                """.formatted(TLE1, TLE2, TLE1, TLE2);

        mockMvc.perform(post("/api/v1/conjunction/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /analyze : aucun événement → 200 avec liste vide")
    void analyze_noEvents_returns200WithEmptyList() throws Exception {
        ConjunctionReport empty = new ConjunctionReport("ISS", "CSS", 5.0,
                NOW, NOW.plusSeconds(86400), List.of());
        when(conjunctionService.analyze(any())).thenReturn(empty);

        String body = """
                {"nameSat1":"ISS","tle1Sat1":"%s","tle2Sat1":"%s",
                 "nameSat2":"CSS","tle1Sat2":"%s","tle2Sat2":"%s",
                 "durationHours":24.0,"stepSeconds":60,"thresholdKm":5.0}
                """.formatted(TLE1, TLE2, TLE1, TLE2);

        mockMvc.perform(post("/api/v1/conjunction/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(0)));
    }

    @Test
    @DisplayName("POST /analyze : erreur technique → 500")
    void analyze_technicalError_returns500() throws Exception {
        when(conjunctionService.analyze(any()))
                .thenThrow(new RuntimeException("Erreur Orekit inattendue"));

        String body = """
                {"nameSat1":"ISS","tle1Sat1":"%s","tle2Sat1":"%s",
                 "nameSat2":"CSS","tle1Sat2":"%s","tle2Sat2":"%s",
                 "durationHours":24.0,"stepSeconds":60,"thresholdKm":5.0}
                """.formatted(TLE1, TLE2, TLE1, TLE2);

        mockMvc.perform(post("/api/v1/conjunction/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /analyze-by-name
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /analyze-by-name : retourne 200 quand les deux satellites sont résolus")
    void analyzeByName_returns200() throws Exception {
        TleEntry iss = new TleEntry("ISS (ZARYA)", TLE1, TLE2, "stations", NOW);
        TleEntry css = new TleEntry("CSS (TIANHE)", TLE1, TLE2, "stations", NOW);
        ConjunctionReport report = new ConjunctionReport("ISS (ZARYA)", "CSS (TIANHE)", 5.0,
                NOW, NOW.plusSeconds(86400), List.of());

        when(tleService.resolveUniqueTle("ISS")).thenReturn(iss);
        when(tleService.resolveUniqueTle("CSS")).thenReturn(css);
        when(conjunctionService.analyze(any())).thenReturn(report);

        mockMvc.perform(post("/api/v1/conjunction/analyze-by-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameSat1\":\"ISS\",\"nameSat2\":\"CSS\"," +
                                 "\"durationHours\":24.0,\"stepSeconds\":60,\"thresholdKm\":5.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameSat1", is("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("POST /analyze-by-name : satellite introuvable → 404")
    void analyzeByName_notFound_returns404() throws Exception {
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour : UNKNOWN"));

        mockMvc.perform(post("/api/v1/conjunction/analyze-by-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameSat1\":\"UNKNOWN\",\"nameSat2\":\"CSS\"," +
                                 "\"durationHours\":24.0,\"stepSeconds\":60,\"thresholdKm\":5.0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /analyze-by-name : nameSat1 vide → 400")
    void analyzeByName_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/conjunction/analyze-by-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameSat1\":\"\",\"nameSat2\":\"CSS\"," +
                                 "\"durationHours\":24.0,\"stepSeconds\":60,\"thresholdKm\":5.0}"))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /alerts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /alerts : retourne 200 avec une page d'alertes")
    void getAlerts_returns200WithPage() throws Exception {
        ConjunctionAlert alert = new ConjunctionAlert(
                "ISS", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));

        mockMvc.perform(get("/api/v1/conjunction/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].nameSat1", is("ISS")))
                .andExpect(jsonPath("$.content[0].distanceKm", is(3.5)));
    }

    @Test
    @DisplayName("GET /alerts : liste vide → 200 avec page vide")
    void getAlerts_emptyList_returns200() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/conjunction/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /alerts?sat=ISS : filtre par nom de satellite → 200 avec résultats filtrés")
    void getAlerts_filterBySat_returns200() throws Exception {
        ConjunctionAlert issAlert = new ConjunctionAlert(
                "ISS (ZARYA)", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(issAlert)));

        mockMvc.perform(get("/api/v1/conjunction/alerts").param("sat", "ISS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].nameSat1", is("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("GET /alerts?maxKm=5 : filtre par distance maximale → 200 avec résultats filtrés")
    void getAlerts_filterByMaxKm_returns200() throws Exception {
        ConjunctionAlert closeAlert = new ConjunctionAlert(
                "ISS", "CSS", 25544, 48274, NOW, 2.1, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(closeAlert)));

        mockMvc.perform(get("/api/v1/conjunction/alerts").param("maxKm", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].distanceKm", is(2.1)));
    }

    @Test
    @DisplayName("GET /alerts?sat=ISS&maxKm=5 : filtres combinés sat + maxKm → 200")
    void getAlerts_filterBySatAndMaxKm_returns200() throws Exception {
        ConjunctionAlert alert = new ConjunctionAlert(
                "ISS (ZARYA)", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));

        mockMvc.perform(get("/api/v1/conjunction/alerts")
                        .param("sat", "ISS")
                        .param("maxKm", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("GET /alerts?from=...&to=... : filtre par fenêtre temporelle → 200")
    void getAlerts_filterByDateRange_returns200() throws Exception {
        ConjunctionAlert alert = new ConjunctionAlert(
                "ISS", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));

        mockMvc.perform(get("/api/v1/conjunction/alerts")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to",   "2026-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /alerts/unread
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /alerts/unread : retourne la liste des alertes non acquittées")
    void getUnreadAlerts_returnsUnreadList() throws Exception {
        ConjunctionAlert alert = new ConjunctionAlert(
                "ISS", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findByAcknowledgedFalseOrderByTcaAsc()).thenReturn(List.of(alert));

        mockMvc.perform(get("/api/v1/conjunction/alerts/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].acknowledged", is(false)));
    }

    @Test
    @DisplayName("GET /alerts/unread : aucune alerte → 200 liste vide")
    void getUnreadAlerts_empty_returns200() throws Exception {
        when(repository.findByAcknowledgedFalseOrderByTcaAsc()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/conjunction/alerts/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /alerts/{id}/ack
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /alerts/{id}/ack : alerte existante → 204 + save appelé")
    void acknowledge_existingAlert_returns204() throws Exception {
        ConjunctionAlert alert = new ConjunctionAlert(
                "ISS", "CSS", 25544, 48274, NOW, 3.5, 45.0, 10.0, 410.0, 40.0, 15.0, 390.0);
        when(repository.findById(1L)).thenReturn(Optional.of(alert));

        mockMvc.perform(put("/api/v1/conjunction/alerts/1/ack"))
                .andExpect(status().isNoContent());

        verify(repository).save(alert);
    }

    @Test
    @DisplayName("PUT /alerts/{id}/ack : alerte introuvable → 404")
    void acknowledge_notFound_returns404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/conjunction/alerts/99/ack"))
                .andExpect(status().isNotFound());
    }
}





