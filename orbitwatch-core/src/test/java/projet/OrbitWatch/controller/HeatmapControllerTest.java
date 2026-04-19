package projet.OrbitWatch.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.OrbitalElementsExtractor;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests du HeatmapController en couche web (MockMvc).
 *
 * Valide :
 * - La réponse HTTP et le format JSON
 * - Le filtrage par altMin/altMax
 * - Le tri par count décroissant
 * - Le calcul latBand pour orbites directes ET rétrogrades
 * - Les cas dégradés (catalogue inconnu, TLE malformé)
 */
@WebMvcTest({HeatmapController.class, GlobalExceptionHandler.class})
class HeatmapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TleService tleService;

    @MockitoBean
    private OrbitalElementsExtractor extractor;

    // ── Données de test ───────────────────────────────────────────────────────

    private static final String LINE1 =
            "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String LINE2 =
            "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private TleEntry entry(String name) {
        return new TleEntry(name, LINE1, LINE2, "debris", Instant.now());
    }

    /** Crée un OrbitalElements avec l'inclinaison et l'altitude périgée données. */
    private OrbitalElements elements(double inclinationDeg, double altPerigeeKm) {
        return new OrbitalElements(
                99999, "SAT", Instant.now(),
                6780.0, 0.0003,
                inclinationDeg,   // inclinaison
                200.0, 60.0, 15.49,
                altPerigeeKm,     // altPérigée
                altPerigeeKm + 4.0
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cas nominaux
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /heatmap catalogue non vide → 200 + JSON array non vide")
    void getHeatmap_nonEmptyCatalog_returns200WithCells() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("DEB-1"), entry("DEB-2")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(74.0, 490.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].count", greaterThan(0)))
                .andExpect(jsonPath("$[0].latBandDeg", notNullValue()))
                .andExpect(jsonPath("$[0].altBandKm",  notNullValue()));
    }

    @Test
    @DisplayName("GET /heatmap catalogue inconnu → 200 + liste vide (pas de 404)")
    void getHeatmap_unknownCatalog_returnsEmptyList() throws Exception {
        when(tleService.findByCatalog("unknown")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /heatmap sans paramètre → catalog=debris par défaut")
    void getHeatmap_noParams_usesDebrisDefault() throws Exception {
        when(tleService.findByCatalog("debris")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orbit/heatmap"))
                .andExpect(status().isOk());

        verify(tleService).findByCatalog("debris");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Filtrage par altitude
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /heatmap?altMin=400&altMax=600 → seules les cellules dans la bande")
    void getHeatmap_altFilter_onlyCellsInRange() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("IN-RANGE"), entry("OUT-OF-RANGE")));
        // 1er appel : dans la bande → altBand = round(490/50)*50 = 500
        // 2ème appel : hors bande → filtré
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(51.0, 490.0))
                .thenReturn(elements(51.0, 900.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris&altMin=400&altMax=600"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].altBandKm", is(500.0)));
    }

    @Test
    @DisplayName("GET /heatmap?altMin=900&altMax=1000 → vide si aucun objet dans la bande")
    void getHeatmap_altFilterNoMatch_returnsEmpty() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("LOW-ORBIT")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(74.0, 400.0)); // périgée à 400 km, hors bande 900-1000

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris&altMin=900&altMax=1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Calcul latBand — orbites directes et rétrogrades
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Orbite directe 74° → latBand = 75°")
    void getHeatmap_directOrbit_latBandEqualsInclination() throws Exception {
        when(tleService.findByCatalog("debris")).thenReturn(List.of(entry("COSMOS DEB")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(74.0, 800.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latBandDeg", is(75.0))); // round(74/5)*5 = 75
    }

    @Test
    @DisplayName("Orbite rétrograde 98° (Fengyun) → latBand = 80° (180-98=82 → round=80)")
    void getHeatmap_retrogradeOrbit_latBandIs180MinusInclination() throws Exception {
        when(tleService.findByCatalog("debris")).thenReturn(List.of(entry("FENGYUN DEB")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(98.0, 800.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                // 180 - 98 = 82° → round(82/5)*5 = 80°
                .andExpect(jsonPath("$[0].latBandDeg", is(80.0)));
    }

    @Test
    @DisplayName("Orbite à 86° (Iridium) → latBand = 85° (cap à 85°)")
    void getHeatmap_highInclinationOrbit_cappedAt85() throws Exception {
        when(tleService.findByCatalog("debris")).thenReturn(List.of(entry("IRIDIUM DEB")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(86.0, 780.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                // round(86/5)*5 = 85 → déjà dans la limite
                .andExpect(jsonPath("$[0].latBandDeg", is(85.0)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tri et agrégation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Résultats triés par count décroissant")
    void getHeatmap_resultsAreSortedByCountDesc() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("A"), entry("B"), entry("C"), entry("D"), entry("E")));
        // 1 objet dans la cellule (75°, 800) et 4 dans la cellule (85°, 800)
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(74.0,  800.0))  // → cellule (75, 800)
                .thenReturn(elements(86.0,  800.0))  // → cellule (85, 800)
                .thenReturn(elements(86.0,  800.0))  // → cellule (85, 800)
                .thenReturn(elements(86.0,  800.0))  // → cellule (85, 800)
                .thenReturn(elements(86.0,  800.0)); // → cellule (85, 800)

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count", is(4)))   // cellule la plus dense en premier
                .andExpect(jsonPath("$[1].count", is(1)));
    }

    @Test
    @DisplayName("2 objets dans la même cellule → count = 2 (agrégation correcte)")
    void getHeatmap_sameCell_aggregatesCount() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("DEB-1"), entry("DEB-2")));
        // Les deux tombent dans la même cellule (75°, 800 km)
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenReturn(elements(74.0, 800.0))
                .thenReturn(elements(76.0, 820.0)); // round(76/5)*5=75, round(820/50)*50=800

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].count", is(2)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Robustesse
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TLE malformé → skip silencieux, le reste du catalogue est traité")
    void getHeatmap_malformedTle_skipsGracefully() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("BAD"), entry("GOOD")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("TLE malformé"))
                .thenReturn(elements(74.0, 800.0));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Tous les TLEs malformés → 200 + liste vide (pas d'erreur 500)")
    void getHeatmap_allMalformed_returnsEmptyNotError() throws Exception {
        when(tleService.findByCatalog("debris"))
                .thenReturn(List.of(entry("BAD-1"), entry("BAD-2")));
        when(extractor.extract(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("TLE malformé"));

        mockMvc.perform(get("/api/v1/orbit/heatmap?catalog=debris"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}

