package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrbitWatchRagServiceTest {

    @Mock VectorStore        vectorStore;
    @Mock ChatClient.Builder chatClientBuilder;
    @Mock ChatClient         chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec    streamSpec;

    OrbitWatchRagService service;

    @BeforeEach
    void setUp() throws Exception {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);

        service = new OrbitWatchRagService(vectorStore, chatClientBuilder);
    }

    @Test
    @DisplayName("2 documents trouvés → leur contenu apparaît dans le prompt envoyé au LLM")
    void twoDocuments_contextInjectedInPrompt() {
        Document doc1 = Document.builder().text("ISS, NORAD 25544, altitude 408–416 km").build();
        Document doc2 = Document.builder().text("TIANGONG, NORAD 48274, altitude 390–400 km").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1, doc2));
        when(streamSpec.content()).thenReturn(Flux.just("ISS est en orbite."));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        service.chat("Où est l'ISS ?").blockLast();

        verify(requestSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt)
                .contains("ISS, NORAD 25544")
                .contains("TIANGONG, NORAD 48274")
                .contains("Où est l'ISS ?");
    }

    @Test
    @DisplayName("Question vide → Flux vide, aucun appel vectorStore")
    void emptyQuestion_returnsEmptyFlux() {
        List<String> tokens = service.chat("").collectList().block();
        assertThat(tokens).isEmpty();
        verifyNoInteractions(vectorStore, chatClient);
    }

    @Test
    @DisplayName("Question nulle → Flux vide, aucun appel vectorStore")
    void nullQuestion_returnsEmptyFlux() {
        List<String> tokens = service.chat(null).collectList().block();
        assertThat(tokens).isEmpty();
        verifyNoInteractions(vectorStore, chatClient);
    }

    @Test
    @DisplayName("Aucun document trouvé → contexte vide dans le prompt, LLM toujours appelé")
    void noDocuments_fallbackContext() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(streamSpec.content()).thenReturn(Flux.just("Je ne dispose pas de cette information."));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        service.chat("Où est Hubble ?").blockLast();

        verify(requestSpec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        // Quand aucun doc pertinent n'est trouvé, le contexte est vide :
        // le LLM est quand même appelé mais sans données satellites injectées
        assertThat(prompt)
                .contains("Où est Hubble ?")
                .doesNotContain("Voici les données satellites");
    }
}
