package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.model.AnomalyAlert;
import projet.OrbitWatch.model.AnomalySeverity;
import projet.OrbitWatch.model.AnomalyType;
import projet.OrbitWatch.repository.AnomalyAlertRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests MockMvc du {@link AnomalyController}.
 */
@WebMvcTest({AnomalyController.class, GlobalExceptionHandler.class})
class AnomalyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AnomalyAlertRepository repository;

    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private AnomalyAlert alert(Long id, AnomalyType type, AnomalySeverity severity) {
        AnomalyAlert a = new AnomalyAlert(
                25544, "ISS (ZARYA)", NOW, type, severity,
                "Test anomaly description");
        // Simule l'id via réflexion (l'entité n'a pas de setter id)
        try {
            var field = AnomalyAlert.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(a, id);
        } catch (Exception e) { throw new RuntimeException(e); }
        return a;
    }

    // ── GET /alerts ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /alerts : retourne 200 avec une page d'alertes")
    void getAlerts_returns200() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        alert(1L, AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.MEDIUM))));

        mockMvc.perform(get("/api/v1/anomaly/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].noradId", is(25544)))
                .andExpect(jsonPath("$.content[0].type", is("ALTITUDE_CHANGE")))
                .andExpect(jsonPath("$.content[0].severity", is("MEDIUM")));
    }

    @Test
    @DisplayName("GET /alerts : liste vide → 200 avec page vide")
    void getAlerts_empty_returns200() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/anomaly/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /alerts?noradId=25544 : filtre par noradId → 200")
    void getAlerts_filterByNoradId_returns200() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        alert(1L, AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.LOW))));

        mockMvc.perform(get("/api/v1/anomaly/alerts").param("noradId", "25544"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].noradId", is(25544)));
    }

    @Test
    @DisplayName("GET /alerts?type=ALTITUDE_CHANGE&severity=HIGH : filtres combinés → 200")
    void getAlerts_filterByTypeAndSeverity_returns200() throws Exception {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        alert(1L, AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.HIGH))));

        mockMvc.perform(get("/api/v1/anomaly/alerts")
                        .param("type", "ALTITUDE_CHANGE")
                        .param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type", is("ALTITUDE_CHANGE")))
                .andExpect(jsonPath("$.content[0].severity", is("HIGH")));
    }

    // ── GET /alerts/unread ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /alerts/unread : retourne la liste des alertes non acquittées")
    void getUnreadAlerts_returnsList() throws Exception {
        when(repository.findByAcknowledgedFalseOrderByDetectedAtDesc())
                .thenReturn(List.of(
                        alert(1L, AnomalyType.RAAN_DRIFT, AnomalySeverity.LOW)));

        mockMvc.perform(get("/api/v1/anomaly/alerts/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].acknowledged", is(false)));
    }

    @Test
    @DisplayName("GET /alerts/unread : aucune alerte → 200 liste vide")
    void getUnreadAlerts_empty_returns200() throws Exception {
        when(repository.findByAcknowledgedFalseOrderByDetectedAtDesc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/anomaly/alerts/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── PUT /alerts/{id}/ack ──────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /alerts/{id}/ack : alerte existante → 204 + save appelé")
    void acknowledge_existing_returns204() throws Exception {
        AnomalyAlert a = alert(1L, AnomalyType.ALTITUDE_CHANGE, AnomalySeverity.MEDIUM);
        when(repository.findById(1L)).thenReturn(Optional.of(a));

        mockMvc.perform(put("/api/v1/anomaly/alerts/1/ack"))
                .andExpect(status().isNoContent());

        verify(repository).save(a);
    }

    @Test
    @DisplayName("PUT /alerts/{id}/ack : alerte introuvable → 404")
    void acknowledge_notFound_returns404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/anomaly/alerts/99/ack"))
                .andExpect(status().isNotFound());
    }
}

