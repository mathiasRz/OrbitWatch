package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service RAG (Retrieval-Augmented Generation) OrbitWatch.
 *
 * <p>Pipeline :
 * <ol>
 *   <li>Embed la question de l'utilisateur et recherche les top-k documents pertinents dans PgVector</li>
 *   <li>Construit un prompt contextualisé à partir du template {@code prompts/system-prompt.txt}</li>
 *   <li>Appelle le LLM configuré (Ollama en dev, OpenAI en prod) et retourne un {@link Flux}&lt;String&gt;</li>
 * </ol>
 *
 * <p>Le contrôleur {@code ChatController} souscrit à ce {@code Flux} dans un thread {@code @Async}
 * et délègue les tokens au {@code SseEmitter} Spring MVC.
 */
@Service
public class OrbitWatchRagService {

    private static final Logger log = LoggerFactory.getLogger(OrbitWatchRagService.class);

    /** Nombre de documents contextuels retournés par la recherche vectorielle. */
    private static final int TOP_K = 5;

    /** Seuil de similarité cosinus minimum pour qu'un document soit considéré pertinent. */
    private static final double SIMILARITY_THRESHOLD = 0.55;

    private final VectorStore  vectorStore;
    private final ChatClient   chatClient;
    private final String       promptTemplate;

    public OrbitWatchRagService(VectorStore vectorStore,
                                ChatClient.Builder chatClientBuilder) throws Exception {
        this.vectorStore    = vectorStore;
        this.chatClient     = chatClientBuilder.build();
        this.promptTemplate = new ClassPathResource("prompts/system-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Exécute la chaîne RAG et retourne un flux de tokens LLM.
     *
     * @param userQuestion question en langage naturel
     * @return {@link Flux}&lt;String&gt; des tokens générés (streaming)
     */
    public Flux<String> chat(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            log.warn("[RAG] Question vide reçue — retour d'un Flux vide.");
            return Flux.empty();
        }

        // 1. Recherche vectorielle top-k avec seuil de pertinence
        List<Document> docs;
        try {
            docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(userQuestion)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .build()
            );
        } catch (Exception ex) {
            log.error("[RAG] Erreur lors de la recherche vectorielle : {}", ex.getMessage());
            return Flux.error(ex);
        }

        // 2. Construction du contexte (injecté seulement si des docs pertinents sont trouvés)
        String context;
        if (docs == null || docs.isEmpty()) {
            log.info("[RAG] Aucun document pertinent trouvé (seuil={}) pour : {}", SIMILARITY_THRESHOLD, userQuestion);
            context = "";
        } else {
            log.info("[RAG] {} document(s) pertinent(s) trouvé(s) pour la question.", docs.size());
            context = "Voici les données satellites disponibles pour répondre à cette question :\n\n"
                    + docs.stream()
                            .map(Document::getText)
                            .collect(Collectors.joining("\n---\n"))
                    + "\n\nBase ta réponse sur ces données. Si l'information demandée n'y figure pas, dis-le clairement.";
        }

        // 3. Injection contexte + question dans le template
        String prompt = promptTemplate
                .replace("{context}",  context)
                .replace("{question}", userQuestion);

        // 4. Appel LLM streamé
        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}

