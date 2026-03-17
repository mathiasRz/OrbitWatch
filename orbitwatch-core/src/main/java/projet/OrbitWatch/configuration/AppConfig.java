package projet.OrbitWatch.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration Spring pour les beans applicatifs transverses.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    /**
     * Bean RestTemplate avec timeouts raisonnables pour les appels vers CelesTrak.
     * Timeout de connexion : 5 s — Timeout de lecture : 15 s.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }
}


