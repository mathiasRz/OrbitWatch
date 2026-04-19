package projet.OrbitWatch.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import projet.OrbitWatch.client.CelesTrackClient;
import projet.OrbitWatch.dto.TleEntry;
import projet.OrbitWatch.service.TleService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du FetchDebrisTleJob.
 *
 * <p>Valide la fusion de plusieurs groupes CelesTrak sous l'alias "debris",
 * la gestion des erreurs par groupe, la publication de l'event
 * et les scénarios de dégradation (groupes indisponibles).
 *
 * Zéro Spring — Mockito uniquement.
 */
@ExtendWith(MockitoExtension.class)
class FetchDebrisTleJobTest {

    @Mock private CelesTrackClient          celestrackClient;
    @Mock private TleService                tleService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FetchDebrisTleJob job;

    // ── TLEs de référence ──────────────────────────────────────────────────────
    private static final String LINE1_FY =
            "1 29228U 06012A   26066.50000000  .00000100  00000+0  10000-4 0  9990";
    private static final String LINE2_FY =
            "2 29228  98.5600 200.0000 0001000  60.0000 300.0000 14.20000000999999";

    private static final String LINE1_IR =
            "1 33441U 09005C   26066.50000000  .00000200  00000+0  20000-4 0  9990";
    private static final String LINE2_IR =
            "2 33441  86.4000 200.0000 0002000  60.0000 300.0000 14.40000000999999";

    private static final String RAW_FY = "FENGYUN 1C DEB\n" + LINE1_FY + "\n" + LINE2_FY;
    private static final String RAW_IR = "IRIDIUM 33 DEB\n" + LINE1_IR + "\n" + LINE2_IR;

    private static TleEntry fyEntry() {
        return new TleEntry("FENGYUN 1C DEB", LINE1_FY, LINE2_FY, "debris", Instant.now());
    }

    private static TleEntry irEntry() {
        return new TleEntry("IRIDIUM 33 DEB", LINE1_IR, LINE2_IR, "debris", Instant.now());
    }

    @BeforeEach
    void setUp() {
        job = new FetchDebrisTleJob(celestrackClient, tleService, eventPublisher);
        // Sources par défaut : les 3 groupes réels
        ReflectionTestUtils.setField(job, "debrisSources",
                "fengyun-1c-debris,iridium-33-debris,cosmos-2251-debris");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cas nominal — fusion de plusieurs groupes
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2 groupes OK → TLEs fusionnés sous 'debris', 1 seul event publié")
    void refreshDebris_twoGroupsOk_mergesUnderDebrisAlias() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris,iridium-33-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris")).thenReturn(RAW_FY);
        when(celestrackClient.getCatalog("iridium-33-debris")).thenReturn(RAW_IR);
        when(tleService.parseTle3Lines(RAW_FY, "debris")).thenReturn(List.of(fyEntry()));
        when(tleService.parseTle3Lines(RAW_IR, "debris")).thenReturn(List.of(irEntry()));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.refreshDebris();

        // L'event doit être publié UNE seule fois avec tous les TLEs fusionnés
        ArgumentCaptor<TleCatalogRefreshedEvent> captor =
                ArgumentCaptor.forClass(TleCatalogRefreshedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());

        TleCatalogRefreshedEvent event = captor.getValue();
        assertThat(event.catalogName()).isEqualTo("debris");
        assertThat(event.entries()).hasSize(2);
    }

    @Test
    @DisplayName("Tous les TLEs sont parsés sous le catalogName 'debris' (pas le nom du groupe source)")
    void refreshDebris_allEntriesParsedWithDebrisAlias() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris")).thenReturn(RAW_FY);
        when(tleService.parseTle3Lines(RAW_FY, "debris")).thenReturn(List.of(fyEntry()));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.refreshDebris();

        // parseTle3Lines doit être appelé avec "debris" et NON "fengyun-1c-debris"
        verify(tleService).parseTle3Lines(RAW_FY, "debris");
        verify(tleService, never()).parseTle3Lines(anyString(), eq("fengyun-1c-debris"));
    }

    @Test
    @DisplayName("Le TleService est mis à jour avec la clé 'debris'")
    void refreshDebris_putsCatalogUnderDebrisKey() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris")).thenReturn(RAW_FY);
        when(tleService.parseTle3Lines(RAW_FY, "debris")).thenReturn(List.of(fyEntry()));

        ConcurrentHashMap<String, ?> catalog = new ConcurrentHashMap<>();
        when(tleService.getCatalog()).thenReturn((ConcurrentHashMap) catalog);

        job.refreshDebris();

        assertThat(catalog).containsKey("debris");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Résilience — un groupe KO n'empêche pas les autres
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Un groupe KO → les autres groupes sont quand même chargés")
    void refreshDebris_oneGroupFails_othersStillLoaded() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris,iridium-33-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris"))
                .thenThrow(new IllegalStateException("CelesTrak indisponible"));
        when(celestrackClient.getCatalog("iridium-33-debris")).thenReturn(RAW_IR);
        when(tleService.parseTle3Lines(RAW_IR, "debris")).thenReturn(List.of(irEntry()));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        assertThatCode(() -> job.refreshDebris()).doesNotThrowAnyException();

        // L'event est quand même publié avec les TLEs du groupe valide
        ArgumentCaptor<TleCatalogRefreshedEvent> captor =
                ArgumentCaptor.forClass(TleCatalogRefreshedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().entries()).hasSize(1);
    }

    @Test
    @DisplayName("Un groupe répond vide → ignoré, les autres groupes sont chargés")
    void refreshDebris_oneGroupEmpty_othersLoaded() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris,iridium-33-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris")).thenReturn("   "); // vide
        when(celestrackClient.getCatalog("iridium-33-debris")).thenReturn(RAW_IR);
        when(tleService.parseTle3Lines(RAW_IR, "debris")).thenReturn(List.of(irEntry()));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.refreshDebris();

        verify(tleService, never()).parseTle3Lines("   ", "debris");
        verify(eventPublisher).publishEvent(any(TleCatalogRefreshedEvent.class));
    }

    @Test
    @DisplayName("Tous les groupes KO → pas d'exception, pas d'event, catalogue 'debris' non mis à jour")
    void refreshDebris_allGroupsFail_noEventNoException() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris,iridium-33-debris");

        when(celestrackClient.getCatalog(anyString()))
                .thenThrow(new IllegalStateException("CelesTrak KO"));

        assertThatCode(() -> job.refreshDebris()).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
        verify(tleService, never()).getCatalog();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Garde-fous 5.11 — l'event publié est bien filtrable par les autres jobs
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("L'event publié a pour catalogName 'debris' (filtrable par OrbitalHistoryJob)")
    void refreshDebris_eventCatalogNameIsDebris() {
        ReflectionTestUtils.setField(job, "debrisSources", "fengyun-1c-debris");

        when(celestrackClient.getCatalog("fengyun-1c-debris")).thenReturn(RAW_FY);
        when(tleService.parseTle3Lines(RAW_FY, "debris")).thenReturn(List.of(fyEntry()));
        when(tleService.getCatalog()).thenReturn(new ConcurrentHashMap<>());

        job.refreshDebris();

        ArgumentCaptor<TleCatalogRefreshedEvent> captor =
                ArgumentCaptor.forClass(TleCatalogRefreshedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        // OrbitalHistoryJob filtre sur "debris" → ce catalogName doit correspondre exactement
        assertThat(captor.getValue().catalogName()).isEqualTo(FetchDebrisTleJob.DEBRIS_CATALOG_NAME);
        assertThat(FetchDebrisTleJob.DEBRIS_CATALOG_NAME).isEqualTo("debris");
    }

    @Test
    @DisplayName("Sources vides (config '') → pas d'appel CelesTrak, pas d'event")
    void refreshDebris_emptySources_noCallsNoEvent() {
        ReflectionTestUtils.setField(job, "debrisSources", "");

        assertThatCode(() -> job.refreshDebris()).doesNotThrowAnyException();

        verifyNoInteractions(celestrackClient);
        verifyNoInteractions(eventPublisher);
    }
}

