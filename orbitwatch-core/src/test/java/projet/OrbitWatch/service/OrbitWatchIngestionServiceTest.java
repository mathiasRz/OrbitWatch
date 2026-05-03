package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import projet.OrbitWatch.dto.OrbitalElements;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.job.TleCatalogRefreshedEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrbitWatchIngestionServiceTest {

    @Mock VectorStore              vectorStore;
    @Mock OrbitalElementsExtractor extractor;
    @Mock JdbcTemplate             jdbcTemplate;

    OrbitWatchIngestionService service;

    @BeforeEach
    void setUp() {
        service = new OrbitWatchIngestionService(vectorStore, extractor, jdbcTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "allowedCatalogs", List.of("stations", "active"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private TleEntry tle(String name) {
        return new TleEntry(name,
                "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990",
                "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999",
                "stations", Instant.now());
    }

    private OrbitalElements elements(String name, int noradId) {
        return new OrbitalElements(noradId, name, Instant.now(),
                6778.0, 0.0003, 51.64, 200.0, 60.0, 15.49, 408.0, 416.0);
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ingestion désactivée → aucun appel vectorStore")
    void ingestionDisabled_noInteraction() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.onCatalogRefreshed(new TleCatalogRefreshedEvent("stations", List.of(tle("ISS"))));
        verifyNoInteractions(vectorStore, jdbcTemplate);
    }

    @Test
    @DisplayName("Catalogue 'debris' → ignoré, aucun appel vectorStore")
    void debrisCatalog_ignored() {
        service.onCatalogRefreshed(new TleCatalogRefreshedEvent("debris", List.of(tle("COSMOS-DEB"))));
        verifyNoInteractions(vectorStore, jdbcTemplate);
    }

    @Test
    @DisplayName("Liste vide → aucun appel vectorStore")
    void emptyEntries_noInteraction() {
        service.onCatalogRefreshed(new TleCatalogRefreshedEvent("stations", List.of()));
        verifyNoInteractions(vectorStore, jdbcTemplate);
    }

    @Test
    @DisplayName("3 TLEs catalogue 'stations' → 3 delete + 3 add")
    void threeEntries_threeUpserts() throws Exception {
        TleEntry e1 = tle("ISS (ZARYA)");
        TleEntry e2 = tle("TIANGONG");
        TleEntry e3 = tle("HUBBLE");

        when(extractor.extract(eq("ISS (ZARYA)"), any(), any())).thenReturn(elements("ISS (ZARYA)", 25544));
        when(extractor.extract(eq("TIANGONG"),    any(), any())).thenReturn(elements("TIANGONG",    48274));
        when(extractor.extract(eq("HUBBLE"),      any(), any())).thenReturn(elements("HUBBLE",      20580));

        service.onCatalogRefreshed(new TleCatalogRefreshedEvent("stations", List.of(e1, e2, e3)));

        verify(jdbcTemplate, times(3)).update(anyString(), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(captor.capture());

        // Vérifie que le document ISS contient "ISS" dans son contenu
        boolean issFound = captor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(doc -> doc.getText().contains("ISS"));
        assertThat(issFound).isTrue();
    }

    @Test
    @DisplayName("Erreur extraction TLE → satellite ignoré, les autres sont indexés")
    void extractionError_skipsEntry() throws Exception {
        TleEntry ok  = tle("ISS (ZARYA)");
        TleEntry bad = tle("INVALID");

        when(extractor.extract(eq("ISS (ZARYA)"), any(), any())).thenReturn(elements("ISS (ZARYA)", 25544));
        when(extractor.extract(eq("INVALID"), any(), any())).thenThrow(new RuntimeException("TLE malformé"));

        service.onCatalogRefreshed(new TleCatalogRefreshedEvent("stations", List.of(ok, bad)));

        verify(vectorStore, times(1)).add(anyList());
    }
}

