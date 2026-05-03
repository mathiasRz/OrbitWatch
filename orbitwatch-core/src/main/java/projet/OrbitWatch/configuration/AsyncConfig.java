package projet.OrbitWatch.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration du pool de threads pour les tâches asynchrones.
 *
 * <p>Le bean {@code ragTaskExecutor} est utilisé par {@link projet.OrbitWatch.controller.ChatController}
 * pour le streaming SSE des réponses LLM — chaque session RAG peut prendre jusqu'à 60 s sur Ollama local.
 *
 * <p>{@code @EnableAsync} active le support de {@code @Async} dans le contexte Spring.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Pool dédié aux sessions RAG/SSE.
     * <ul>
     *   <li>Core : 5 threads (sessions simultanées attendues en dev)</li>
     *   <li>Max  : 20 threads (pic de charge)</li>
     *   <li>Queue : 50 requêtes en attente</li>
     * </ul>
     */
    @Bean("ragTaskExecutor")
    public Executor ragTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rag-sse-");
        executor.initialize();
        return executor;
    }
}

