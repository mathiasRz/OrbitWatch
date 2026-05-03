package projet.OrbitWatch.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import projet.OrbitWatch.service.OrbitWatchRagService;
import reactor.core.publisher.Flux;

import java.util.concurrent.Executor;

/**
 * Contrôleur REST exposant l'assistant RAG OrbitWatch via Server-Sent Events (SSE).
 *
 * <p>Base URL : {@code /api/v1/ai}
 *
 * <p>Implémentation SSE en Spring MVC (pas WebFlux) :
 * <ol>
 *   <li>{@code POST /chat} crée un {@link SseEmitter} et délègue le streaming à un thread {@code @Async}</li>
 *   <li>Le thread {@code @Async} souscrit au {@code Flux&lt;String&gt;} de {@link OrbitWatchRagService}
 *       et envoie chaque token via {@link SseEmitter#send}</li>
 *   <li>À la complétion ou en cas d'erreur, l'emitter est fermé proprement</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Value("${rag.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    private final OrbitWatchRagService ragService;
    private final Executor             taskExecutor;

    public ChatController(OrbitWatchRagService ragService,
                          @Qualifier("ragTaskExecutor") Executor taskExecutor) {
        this.ragService   = ragService;
        this.taskExecutor = taskExecutor;
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
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // Soumission explicite au pool — évite le problème de self-invocation
        // qui rendrait @Async inopérant (le proxy Spring est contourné).
        taskExecutor.execute(() -> streamAsync(emitter, body.question()));
        return emitter;
    }

    /**
     * Souscrit au Flux RAG et envoie chaque token via le SseEmitter.
     * Exécuté dans un thread du pool {@code ragTaskExecutor}.
     */
    void streamAsync(SseEmitter emitter, String question) {
        try {
            ragService.chat(question)
                    // ── Gestion d'erreur dans la chaîne réactive ─────────────────────────
                    // onErrorResume AVANT doOnNext : convertit toute erreur (Ollama, PgVector)
                    // en message SSE lisible — empêche blockLast() de lever une exception
                    // qui remonterait au GlobalExceptionHandler via le dispatch async Tomcat.
                    .onErrorResume(err -> {
                        log.warn("[ChatController] Service IA indisponible pour '{}' : {}", question, err.getMessage());
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

    // ─── GET /api/v1/ai/chat/health ──────────────────────────────────────────

    /**
     * Endpoint de santé du service IA — utilisé par le frontend pour vérifier
     * que le backend est prêt avant d'activer le chat.
     */
    @GetMapping("/chat/health")
    public String health() {
        return "ok";
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────

    /**
     * Corps de la requête POST {@code /chat}.
     *
     * @param question Question en langage naturel (non vide)
     */
    public record ChatRequest(@NotBlank String question) {}
}

