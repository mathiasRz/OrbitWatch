package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires pour {@link OrbitWatchAgent} (Mockito pur).
 *
 * <p>Vérifie que l'agent :
 * <ul>
 *   <li>Appelle la recherche vectorielle RAG et injecte le contexte</li>
 *   <li>Charge la mémoire JDBC via {@link JdbcChatMemory}</li>
 *   <li>Délègue le streaming au ChatClient</li>
 *   <li>Retourne un Flux vide sur question vide/nulle sans crasher</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrbitWatchAgentTest {

    // ── Mocks chaîne ChatClient ───────────────────────────────────────────────
    @Mock VectorStore        vectorStore;
    @Mock ChatClient.Builder chatClientBuilder;
    @Mock ChatClient         chatClient;

    // Spec intermédiaires de la chaîne fluent ChatClient
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec    streamSpec;

    @Mock JdbcChatMemory  jdbcChatMemory;
    @Mock OrbitWatchTools orbitWatchTools;

    OrbitWatchAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        // Chaîne fluent : chatClient.prompt().system().user().advisors().tools().stream().content()
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(MessageChatMemoryAdvisor.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(OrbitWatchTools.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("token1", "token2"));

        agent = new OrbitWatchAgent(vectorStore, chatClientBuilder, jdbcChatMemory, orbitWatchTools);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RAG — recherche vectorielle
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Question valide → vectorStore.similaritySearch() est appelé")
    void chat_validQuestion_callsVectorStore() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        agent.chat("Où est l'ISS ?", "session-123").blockLast();

        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("Documents RAG trouvés → le contexte est injecté dans le system prompt")
    void chat_documentsFound_contextInjectedInSystemPrompt() {
        Document doc = Document.builder().text("ISS altitude 408 km").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        agent.chat("Donne-moi l'altitude de l'ISS", "session-123").blockLast();

        // Le system() doit être appelé avec un texte contenant le document
        verify(requestSpec).system(argThat((String s) -> s.contains("ISS altitude 408 km")));
    }

    @Test
    @DisplayName("Aucun document RAG → LLM appelé quand même (contexte vide)")
    void chat_noDocuments_llmStillCalled() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        agent.chat("Test", "session-abc").blockLast();

        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Erreur vectorStore → LLM toujours appelé (contexte vide par fallback)")
    void chat_vectorStoreError_fallbackToEmptyContext() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("PgVector indisponible"));

        // Ne doit pas lever d'exception — le fallback remplace le contexte par ""
        List<String> tokens = agent.chat("Test", "session-abc").collectList().block();

        assertThat(tokens).isNotNull();
        verify(chatClient).prompt();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Mémoire conversationnelle
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sessionId fourni → MessageChatMemoryAdvisor configuré avec ce sessionId")
    void chat_withSessionId_advisorAttached() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        agent.chat("Bonjour", "my-session-uuid").blockLast();

        // L'advisor mémoire doit être ajouté dans la chaîne
        verify(requestSpec).advisors(any(MessageChatMemoryAdvisor.class));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tool Calling
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("OrbitWatchTools est bien transmis au ChatClient via .tools()")
    void chat_toolsAttachedToChatClient() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        agent.chat("Anomalies en cours ?", "session-xyz").blockLast();

        verify(requestSpec).tools(orbitWatchTools);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Questions vides / nulles
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Question vide → Flux vide, aucun appel au LLM ni au vectorStore")
    void chat_emptyQuestion_returnsEmptyFlux() {
        List<String> tokens = agent.chat("", "session-123").collectList().block();

        assertThat(tokens).isEmpty();
        verifyNoInteractions(vectorStore, chatClient);
    }

    @Test
    @DisplayName("Question nulle → Flux vide, aucun appel au LLM ni au vectorStore")
    void chat_nullQuestion_returnsEmptyFlux() {
        List<String> tokens = agent.chat(null, "session-123").collectList().block();

        assertThat(tokens).isEmpty();
        verifyNoInteractions(vectorStore, chatClient);
    }

    @Test
    @DisplayName("Question espace uniquement → Flux vide (question considérée vide)")
    void chat_blankQuestion_returnsEmptyFlux() {
        List<String> tokens = agent.chat("   ", "session-123").collectList().block();

        assertThat(tokens).isEmpty();
        verifyNoInteractions(vectorStore, chatClient);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Streaming
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Réponse LLM streamée → les tokens sont retournés dans l'ordre")
    void chat_streamsTokensInOrder() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(streamSpec.content()).thenReturn(Flux.just("L'ISS ", "orbite à ", "408 km."));

        List<String> tokens = agent.chat("Altitude ISS ?", "session-abc").collectList().block();

        assertThat(tokens).containsExactly("L'ISS ", "orbite à ", "408 km.");
    }
}


