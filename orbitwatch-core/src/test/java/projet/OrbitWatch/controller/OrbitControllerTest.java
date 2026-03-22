package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;


import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.dto.SatellitePosition;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.PropagationService;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests du OrbitController en couche web (MockMvc).
 * Valide les endpoints GET /api/v1/orbit/position et /api/v1/orbit/groundtrack.
 *
 * Note : {@code TleService.parseEpoch} est une méthode statique pure, elle n'est
 * pas mockée — on utilise {@code any()} sur l'Instant dans les stubs du PropagationService.
 */
@WebMvcTest({OrbitController.class, GlobalExceptionHandler.class})
class OrbitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PropagationService propagationService;

    @MockitoBean
    private TleService tleService;

    private static final String TLE_LINE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String TLE_LINE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";
    private static final Instant EPOCH = Instant.parse("2026-03-17T12:00:00Z");

    private static final TleEntry ISS_ENTRY =
            new TleEntry("ISS (ZARYA)", TLE_LINE1, TLE_LINE2, "stations", EPOCH);

    private static final SatellitePosition ISS_POS =
            new SatellitePosition("ISS (ZARYA)", EPOCH, 45.0, 10.0, 410.0, 1000.0, 2000.0, 3000.0);


    @Test
    @DisplayName("GET /position : retourne 200 avec la position du satellite")
    void getPosition_returnsPosition() throws Exception {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(ISS_ENTRY);
        when(propagationService.propagate(eq(TLE_LINE1), eq(TLE_LINE2), eq("ISS (ZARYA)"), isNull()))
                .thenReturn(ISS_POS);

        mockMvc.perform(get("/api/v1/orbit/position").param("name", "ISS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("ISS (ZARYA)")))
                .andExpect(jsonPath("$.latitude", is(45.0)))
                .andExpect(jsonPath("$.longitude", is(10.0)))
                .andExpect(jsonPath("$.altitude", is(410.0)));
    }

    @Test
    @DisplayName("GET /position : transmet l'epoch parsé au PropagationService")
    void getPosition_forwardsEpochToPropagationService() throws Exception {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(ISS_ENTRY);
        when(propagationService.propagate(eq(TLE_LINE1), eq(TLE_LINE2), eq("ISS (ZARYA)"), eq(EPOCH)))
                .thenReturn(ISS_POS);

        mockMvc.perform(get("/api/v1/orbit/position")
                        .param("name", "ISS")
                        .param("epoch", "2026-03-17T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("ISS (ZARYA)")));
    }

    @Test
    @DisplayName("GET /position : retourne 404 si le satellite est introuvable")
    void getPosition_returns404WhenNotFound() throws Exception {
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour le nom : UNKNOWN"));

        mockMvc.perform(get("/api/v1/orbit/position").param("name", "UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /position : retourne 409 si plusieurs satellites correspondent")
    void getPosition_returns409WhenAmbiguous() throws Exception {
        when(tleService.resolveUniqueTle("ISS"))
                .thenThrow(new TleService.AmbiguousTleException("Plusieurs satellites correspondent à 'ISS'"));

        mockMvc.perform(get("/api/v1/orbit/position").param("name", "ISS"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /position : retourne 400 si le paramètre name est absent")
    void getPosition_returns400WhenNameMissing() throws Exception {
        mockMvc.perform(get("/api/v1/orbit/position"))
                .andExpect(status().isBadRequest());
    }


    @Test
    @DisplayName("GET /groundtrack : retourne 200 avec la liste de positions")
    void getGroundTrack_returnsList() throws Exception {
        List<SatellitePosition> track = List.of(
                new SatellitePosition("ISS (ZARYA)", EPOCH,               45.0, 10.0, 410.0, 1000.0, 2000.0, 3000.0),
                new SatellitePosition("ISS (ZARYA)", EPOCH.plusSeconds(60), 46.0, 11.0, 411.0, 1001.0, 2001.0, 3001.0)
        );
        when(tleService.resolveUniqueTle("ISS")).thenReturn(ISS_ENTRY);
        when(propagationService.groundTrack(eq(TLE_LINE1), eq(TLE_LINE2), eq("ISS (ZARYA)"),
                isNull(), eq(90), eq(60)))
                .thenReturn(track);

        mockMvc.perform(get("/api/v1/orbit/groundtrack").param("name", "ISS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("ISS (ZARYA)")))
                .andExpect(jsonPath("$[0].altitude", is(410.0)));
    }

    @Test
    @DisplayName("GET /groundtrack : utilise les paramètres duration et step transmis")
    void getGroundTrack_forwardsDurationAndStep() throws Exception {
        when(tleService.resolveUniqueTle("ISS")).thenReturn(ISS_ENTRY);
        when(propagationService.groundTrack(eq(TLE_LINE1), eq(TLE_LINE2), eq("ISS (ZARYA)"),
                isNull(), eq(120), eq(30)))
                .thenReturn(List.of(ISS_POS));

        mockMvc.perform(get("/api/v1/orbit/groundtrack")
                        .param("name", "ISS")
                        .param("duration", "120")
                        .param("step", "30"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /groundtrack : retourne 404 si le satellite est introuvable")
    void getGroundTrack_returns404WhenNotFound() throws Exception {
        when(tleService.resolveUniqueTle("UNKNOWN"))
                .thenThrow(new TleService.TleNotFoundException("Aucun satellite trouvé pour le nom : UNKNOWN"));

        mockMvc.perform(get("/api/v1/orbit/groundtrack").param("name", "UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /groundtrack : retourne 409 si la résolution est ambiguë")
    void getGroundTrack_returns409WhenAmbiguous() throws Exception {
        when(tleService.resolveUniqueTle("ISS"))
                .thenThrow(new TleService.AmbiguousTleException("Plusieurs satellites correspondent à 'ISS'"));

        mockMvc.perform(get("/api/v1/orbit/groundtrack").param("name", "ISS"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /groundtrack : retourne 400 si le paramètre name est absent")
    void getGroundTrack_returns400WhenNameMissing() throws Exception {
        mockMvc.perform(get("/api/v1/orbit/groundtrack"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /positions : retourne 200 avec la liste des positions du catalogue")
    void getPositions_returnsPositionList() throws Exception {
        List<SatellitePosition> positions = List.of(
                new SatellitePosition("ISS (ZARYA)", EPOCH, 45.0, 10.0, 410.0, 1000.0, 2000.0, 3000.0),
                new SatellitePosition("TIANGONG", EPOCH, 30.0, 20.0, 390.0, 1100.0, 2100.0, 3100.0)
        );
        when(tleService.findByCatalog("stations")).thenReturn(List.of(ISS_ENTRY, ISS_ENTRY));
        when(propagationService.snapshotCatalog(anyList())).thenReturn(positions);

        mockMvc.perform(get("/api/v1/orbit/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("ISS (ZARYA)")))
                .andExpect(jsonPath("$[1].name", is("TIANGONG")));
    }

    @Test
    @DisplayName("GET /positions : utilise le paramètre catalog transmis")
    void getPositions_forwardsCatalogParam() throws Exception {
        when(tleService.findByCatalog("active")).thenReturn(List.of(ISS_ENTRY));
        when(propagationService.snapshotCatalog(anyList())).thenReturn(List.of(ISS_POS));

        mockMvc.perform(get("/api/v1/orbit/positions").param("catalog", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("GET /positions : catalogue inconnu → retourne 200 avec liste vide")
    void getPositions_unknownCatalog_returnsEmptyList() throws Exception {
        when(tleService.findByCatalog("unknown")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbit/positions").param("catalog", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /positions : catalog par défaut est 'stations'")
    void getPositions_defaultCatalogIsStations() throws Exception {
        when(tleService.findByCatalog("stations")).thenReturn(List.of(ISS_ENTRY));
        when(propagationService.snapshotCatalog(anyList())).thenReturn(List.of(ISS_POS));

        mockMvc.perform(get("/api/v1/orbit/positions"))
                .andExpect(status().isOk());

        // Vérifie que findByCatalog a bien été appelé avec "stations"
        org.mockito.Mockito.verify(tleService).findByCatalog("stations");
    }

    @Test
    @DisplayName("GET /positions : erreur technique → retourne 500")
    void getPositions_technicalError_returns500() throws Exception {
        when(tleService.findByCatalog("stations")).thenReturn(List.of(ISS_ENTRY));
        when(propagationService.snapshotCatalog(anyList()))
                .thenThrow(new RuntimeException("Erreur Orekit inattendue"));

        mockMvc.perform(get("/api/v1/orbit/positions"))
                .andExpect(status().isInternalServerError());
    }
}

