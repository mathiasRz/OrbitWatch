package projet.OrbitWatch.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import projet.OrbitWatch.client.CelesTrackClient;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du FetchCelesTrackTLEJob.
 * Valide la délégation vers CelesTrackClient et TleService,
 * la gestion des erreurs par catalogue (fail-safe), et la configuration.
 */
@ExtendWith(MockitoExtension.class)
class FetchCelesTrackTLEJobTest {

    @Mock
    private CelesTrackClient celestrackClient;

    @Mock
    private TleService tleService;

    private FetchCelesTrackTLEJob job;


    private static final String ISS_NAME  = "ISS (ZARYA)";
    private static final String ISS_LINE1 = "1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990";
    private static final String ISS_LINE2 = "2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999";

    private static final String RAW_TLE =
            ISS_NAME + "\n" + ISS_LINE1 + "\n" + ISS_LINE2;

    private static final TleEntry ISS_ENTRY =
            new TleEntry(ISS_NAME, ISS_LINE1, ISS_LINE2, "stations", Instant.now());

    @BeforeEach
    void setUp() {
        job = new FetchCelesTrackTLEJob(celestrackClient, tleService);
        // Catalogs par défaut = "stations,active,visual"
        ReflectionTestUtils.setField(job, "catalogsConfig", "stations");
    }


    @Test
    @DisplayName("refreshAll : appelle getCatalog pour chaque catalogue configuré")
    void refreshAll_callsGetCatalogForEachCatalog() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "stations,active");
        when(celestrackClient.getCatalog(anyString())).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(anyString(), anyString())).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(2L);

        job.refreshAll();

        verify(celestrackClient).getCatalog("stations");
        verify(celestrackClient).getCatalog("active");
    }

    @Test
    @DisplayName("refreshAll : délègue le parsing au TleService avec le bon catalogName")
    void refreshAll_delegatesParsingWithCorrectCatalogName() {
        when(celestrackClient.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(1L);

        job.refreshAll();

        verify(tleService).parseTle3Lines(RAW_TLE, "stations");
    }

    @Test
    @DisplayName("refreshAll : stocke les TLE dans le catalogue via getCatalog().put()")
    void refreshAll_storesEntriesInCatalog() {
        ConcurrentHashMap<String, CopyOnWriteArrayList<TleEntry>> catalogMap = new ConcurrentHashMap<>();
        when(celestrackClient.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(catalogMap);
        when(tleService.countAll()).thenReturn(1L);

        job.refreshAll();

        assertThat(catalogMap).containsKey("stations");
        assertThat(catalogMap.get("stations")).containsExactly(ISS_ENTRY);
    }

    @Test
    @DisplayName("refreshAll : continue sur les autres catalogues si l'un échoue")
    void refreshAll_continuesOnCatalogError() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "failing-cat,stations");
        when(celestrackClient.getCatalog("failing-cat"))
                .thenThrow(new IllegalStateException("Network error"));
        when(celestrackClient.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(RAW_TLE, "stations")).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(1L);

        // Ne doit pas propager l'exception
        assertThatCode(() -> job.refreshAll()).doesNotThrowAnyException();

        // Le catalogue "stations" doit quand même avoir été traité
        verify(tleService).parseTle3Lines(RAW_TLE, "stations");
    }

    @Test
    @DisplayName("refreshAll : ne lève pas d'exception si tous les catalogues échouent")
    void refreshAll_doesNotThrowWhenAllFail() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "cat1,cat2");
        when(celestrackClient.getCatalog(anyString()))
                .thenThrow(new IllegalStateException("Network error"));

        assertThatCode(() -> job.refreshAll()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refreshAll : ignore les entrées vides dans la config de catalogues")
    void refreshAll_ignoresBlankCatalogNames() {
        ReflectionTestUtils.setField(job, "catalogsConfig", " , stations , , ");
        when(celestrackClient.getCatalog("stations")).thenReturn(RAW_TLE);
        when(tleService.parseTle3Lines(anyString(), anyString())).thenReturn(List.of(ISS_ENTRY));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());
        when(tleService.countAll()).thenReturn(1L);

        job.refreshAll();

        // Seul "stations" doit être appelé (pas les entrées vides)
        verify(celestrackClient, times(1)).getCatalog(anyString());
        verify(celestrackClient).getCatalog("stations");
    }

    @Test
    @DisplayName("refreshAll : ne contacte jamais CelesTrak si la config est vide")
    void refreshAll_doesNothingWhenConfigIsEmpty() {
        ReflectionTestUtils.setField(job, "catalogsConfig", "  ,  ,  ");
        when(tleService.countAll()).thenReturn(0L);

        job.refreshAll();

        // Aucun appel réseau ne doit être émis
        verifyNoInteractions(celestrackClient);
        // Seul countAll() est appelé en fin de méthode pour le log — aucun parseTle3Lines ni getCatalog
        verify(tleService, never()).parseTle3Lines(anyString(), anyString());
        verify(tleService, never()).getCatalog();
    }
}


