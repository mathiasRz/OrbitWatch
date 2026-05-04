package projet.OrbitWatch.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import projet.OrbitWatch.service.JdbcChatMemory;
import projet.OrbitWatch.service.OrbitWatchAgent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Contrôleur REST exposant l'assistant IA OrbitWatch via Server-Sent Events (SSE).
 *
 * <p>Base URL : {@code /api/v1/ai}
 */
@RestController
@RequestMapping("/api/v1/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Value("${rag.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    private final OrbitWatchAgent agent;
    private final JdbcChatMemory  jdbcChatMemory;
    private final Executor        taskExecutor;

    public ChatController(OrbitWatchAgent agent,
                          JdbcChatMemory jdbcChatMemory,
                          @Qualifier("ragTaskExecutor") Executor taskExecutor) {
        this.agent          = agent;
        this.jdbcChatMemory = jdbcChatMemory;
        this.taskExecutor   = taskExecutor;
    }

    // ─── POST /api/v1/ai/chat ────────────────────────────────────────────────

    /**
     * Démarre une session de chat RAG et retourne un flux SSE des tokens générés.
     *
     * @param body Corps JSON {@code {"question": "..."}}
     * @return {@link SseEmitter} streamant les tokens de la réponse LLM
     */
    @PostMapping(value = "/chat", produces = "text/event-stream")
    public SseEmitter chat(@RequestBody @Valid ChatRequest body) {
        String sessionId = (body.sessionId() != null && !body.sessionId().isBlank())
                ? body.sessionId()
                : UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // Soumission explicite au pool — évite le problème de self-invocation
        // qui rendrait @Async inopérant (le proxy Spring est contourné).
        taskExecutor.execute(() -> streamAsync(emitter, body.question(), sessionId));
        return emitter;
    }

    /**
     * Souscrit au Flux RAG et envoie chaque token via le SseEmitter.
     * Exécuté dans un thread du pool {@code ragTaskExecutor}.
     */
    void streamAsync(SseEmitter emitter, String question, String sessionId) {
        try {
            agent.chat(question, sessionId)
                    // ── Gestion d'erreur dans la chaîne réactive ─────────────────────────
                    // onErrorResume AVANT doOnNext : convertit toute erreur (Ollama, PgVector)
                    // en message SSE lisible — empêche blockLast() de lever une exception
                    // qui remonterait au GlobalExceptionHandler via le dispatch async Tomcat.
                    .onErrorResume(err -> {
                        log.warn("[ChatController] Agent IA indisponible pour '{}' : {}", question, err.getMessage());
                        return Flux.just("Service IA temporairement indisponible. " +
                                "Vérifiez qu'Ollama est démarré (`ollama serve` puis `ollama pull llama3.2`).");
                    })
                    .doOnNext(token -> {
                        try {
                            emitter.send(SseEmitter.event().data(token));
                        } catch (Exception ex) {
                            throw new RuntimeException("SseEmitter.send() échoué", ex);
                        }
                    })
                    .doOnComplete(emitter::complete)
                    .doOnError(emitter::completeWithError)  // sécurité : erreurs SseEmitter.send()
                    .blockLast();
        } catch (Exception ex) {
            // Ce bloc ne devrait plus être atteint grâce à onErrorResume.
            // Garde-fou final : envoyer un message d'erreur et fermer proprement.
            log.error("[ChatController] Erreur inattendue SSE '{}' : {}", question, ex.getMessage());
            try {
                emitter.send(SseEmitter.event().data("Erreur interne du serveur."));
                emitter.complete();
            } catch (Exception ignored) { /* emitter déjà fermé */ }
        }
    }

    // ─── GET /api/v1/ai/chat/history ────────────────────────────────────────

    /**
     * Récupère l'historique des messages d'une session.
     *
     * @param sessionId ID de la session
     * @return Liste des messages de la session
     */
    @GetMapping("/chat/history")
    public List<ChatMessageDto> getHistory(@RequestParam String sessionId) {
        List<Message> messages = jdbcChatMemory.get(sessionId, 50);
        return messages.stream()
                .map(m -> new ChatMessageDto(
                        m.getMessageType().name(),
                        m.getText(),
                        null))
                .toList();
    }

    // ─── DELETE /api/v1/ai/chat/history ─────────────────────────────────────

    /**
     * Efface l'historique des messages d'une session.
     *
     * @param sessionId ID de la session
     */
    @DeleteMapping("/chat/history")
    public ResponseEntity<Void> clearHistory(@RequestParam String sessionId) {
        jdbcChatMemory.clear(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ─── GET /api/v1/ai/chat/health ──────────────────────────────────────────

    /**
     * Endpoint de santé du service IA — utilisé par le frontend pour vérifier
     * que le backend est prêt avant d'activer le chat.
     */
    @GetMapping("/chat/health")
    public String health() {
        return "ok";
    }

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    /**
     * Corps de la requête POST {@code /chat}.
     *
     * @param question Question en langage naturel (non vide)
     */
    public record ChatRequest(@NotBlank String question, String sessionId) {}

    public record ChatMessageDto(String role, String content, String createdAt) {}
}
