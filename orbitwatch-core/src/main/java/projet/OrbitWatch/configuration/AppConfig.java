package projet.OrbitWatch.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;

/**
 * Configuration Spring pour les beans applicatifs transverses.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * Bean RestTemplate PROD — vérification SSL active, timeouts raisonnables.
     */
    @Bean
    @Profile("!dev")
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    /**
     * Bean RestTemplate DEV — SSL bypass total (certificat CelesTrak non reconnu en dev).
     */
    @Bean
    @Profile("dev")
    public RestTemplate restTemplateDev() {
        log.warn("[AppConfig] RestTemplate DEV : vérification SSL désactivée. Ne pas utiliser en production");
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(15_000);
            return new RestTemplate(factory);

        } catch (Exception e) {
            throw new IllegalStateException("Impossible d'initialiser le RestTemplate DEV sans SSL", e);
        }
    }
}


