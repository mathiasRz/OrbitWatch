package projet.OrbitWatch.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import projet.OrbitWatch.service.OrbitWatchRagService;
import reactor.core.publisher.Flux;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerTest {

    MockMvc mockMvc;

    @Mock
    OrbitWatchRagService ragService;

    @Mock
    Executor taskExecutor;

    ChatController chatController;

    @BeforeEach
    void setUp() {
        chatController = new ChatController(ragService, taskExecutor);
        mockMvc = MockMvcBuilders
                .standaloneSetup(chatController)
                .build();
        // Exécute les tâches soumises de façon synchrone dans le thread du test
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
                .when(taskExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("GET /api/v1/ai/chat/health → 200 ok")
    void health_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/ai/chat/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("POST /chat avec question valide → 200 + text/event-stream")
    void chat_validQuestion_returnsSse() throws Exception {
        when(ragService.chat(anyString())).thenReturn(Flux.just("L'ISS ", "est en orbite."));

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Où est l'ISS ?\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/event-stream")));
    }

    @Test
    @DisplayName("POST /chat avec corps vide → 400")
    void chat_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /chat avec question vide → 400")
    void chat_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /chat quand ragService lance une exception → 200 (SSE déjà ouvert)")
    void chat_serviceThrows_emitterCompleteWithError() throws Exception {
        when(ragService.chat(anyString()))
                .thenReturn(Flux.error(new RuntimeException("Ollama indisponible")));

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Test\"}"))
                .andExpect(status().isOk());
    }
}
