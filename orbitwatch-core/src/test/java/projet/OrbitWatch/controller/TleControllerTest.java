package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests du TleController en couche web (MockMvc).
 * Valide les endpoints GET /api/v1/tle/names et /api/v1/tle/status.
 */
@WebMvcTest({TleController.class, GlobalExceptionHandler.class})
class TleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TleService tleService;

    private static final Instant NOW = Instant.parse("2026-03-17T12:00:00Z");

    private TleEntry entry(String name, String source) {
        return new TleEntry(name,
                "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990",
                "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999",
                source, NOW);
    }

    @Test
    @DisplayName("GET /names : retourne 200 avec la liste triée des noms distincts")
    void getNames_returnsSortedDistinctNames() throws Exception {
        when(tleService.findAll()).thenReturn(List.of(
                entry("ISS (ZARYA)", "stations"),
                entry("HST", "active"),
                entry("ISS (ZARYA)", "visual")   // doublon intentionnel
        ));

        mockMvc.perform(get("/api/v1/tle/names"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", is("HST")))
                .andExpect(jsonPath("$[1]", is("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("GET /names : retourne une liste vide si le catalogue est vide")
    void getNames_returnsEmptyListWhenNoCatalog() throws Exception {
        when(tleService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/tle/names"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /status : retourne 200 avec les catalogues et le total TLE")
    void getStatus_returnsStatusPayload() throws Exception {
        when(tleService.getCatalogNames()).thenReturn(Set.of("stations", "active"));
        when(tleService.countAll()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/tle/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTle", is(42)))
                .andExpect(jsonPath("$.catalogs", hasSize(2)));
    }

    @Test
    @DisplayName("GET /status : totalTle = 0 si aucun catalogue chargé")
    void getStatus_returnsZeroWhenEmpty() throws Exception {
        when(tleService.getCatalogNames()).thenReturn(Set.of());
        when(tleService.countAll()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/tle/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTle", is(0)))
                .andExpect(jsonPath("$.catalogs", hasSize(0)));
    }
}





