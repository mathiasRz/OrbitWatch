package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
 * Agent Spring AI orchestrant RAG + Tool Calling + mémoire conversationnelle.
 *
 * <p>Remplace {@link OrbitWatchRagService} dans {@code ChatController} —
 * l'ancienne classe est conservée sans modification.
 *
 * <p>Pipeline par requête :
 * <ol>
 *   <li>Recherche vectorielle top-k pour construire le contexte RAG</li>
 *   <li>Appel LLM streamé avec {@link MessageChatMemoryAdvisor} (mémoire JDBC)
 *       et {@link OrbitWatchTools} (tool calling)</li>
 * </ol>
 */
@Service
public class OrbitWatchAgent {

    private static final Logger log = LoggerFactory.getLogger(OrbitWatchAgent.class);

    private static final int    TOP_K               = 5;
    private static final double SIMILARITY_THRESHOLD = 0.55;

    private final VectorStore     vectorStore;
    private final ChatClient      chatClient;
    private final JdbcChatMemory  jdbcChatMemory;
    private final OrbitWatchTools orbitWatchTools;
    private final String          promptTemplate;

    public OrbitWatchAgent(VectorStore vectorStore,
                           ChatClient.Builder chatClientBuilder,
                           JdbcChatMemory jdbcChatMemory,
                           OrbitWatchTools orbitWatchTools) throws Exception {
        this.vectorStore     = vectorStore;
        this.chatClient      = chatClientBuilder.build();
        this.jdbcChatMemory  = jdbcChatMemory;
        this.orbitWatchTools = orbitWatchTools;
        this.promptTemplate  = new ClassPathResource("prompts/system-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Exécute la chaîne RAG + Tool Calling avec mémoire conversationnelle.
     *
     * @param question  question en langage naturel
     * @param sessionId UUID de session (localStorage navigateur)
     * @return {@link Flux}&lt;String&gt; des tokens générés (streaming)
     */
    public Flux<String> chat(String question, String sessionId) {
        if (question == null || question.isBlank()) {
            log.warn("[OrbitWatchAgent] Question vide — retour Flux vide.");
            return Flux.empty();
        }

        // 1. Recherche vectorielle RAG
        String context;
        try {
            List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(question)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .build()
            );
            if (docs == null || docs.isEmpty()) {
                log.info("[OrbitWatchAgent] Aucun document RAG pertinent pour : {}", question);
                context = "";
            } else {
                log.info("[OrbitWatchAgent] {} doc(s) RAG trouvé(s)", docs.size());
                context = "Voici les données satellites disponibles pour répondre à cette question :\n\n"
                        + docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"))
                        + "\n\nBase ta réponse sur ces données. Si l'information demandée n'y figure pas, dis-le clairement.";
            }
        } catch (Exception ex) {
            log.error("[OrbitWatchAgent] Erreur recherche vectorielle : {}", ex.getMessage());
            context = "";
        }

        // 2. Construction du prompt système avec le contexte RAG
        String systemPrompt = promptTemplate
                .replace("{context}", context)
                .replace("{question}", "");

        // 3. Appel LLM streamé avec advisor mémoire + tools
        log.info("[OrbitWatchAgent] Appel LLM (session={}) : {}", sessionId, question);
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(MessageChatMemoryAdvisor.builder(jdbcChatMemory)
                        .conversationId(sessionId)
                        .build())
                .tools(orbitWatchTools)
                .stream()
                .content();
    }
}


