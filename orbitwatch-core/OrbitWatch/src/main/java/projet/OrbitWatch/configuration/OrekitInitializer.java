package projet.OrbitWatch.configuration;

import jakarta.annotation.PostConstruct;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;

/**
 * Initialise les données Orekit au démarrage de l'application.
 * Utilise ClassPathResource pour résoudre le chemin correctement
 * aussi bien en mode développement Maven qu'une fois packagé en JAR.
 */
@Configuration
public class OrekitInitializer {

    private static final Logger log = LoggerFactory.getLogger(OrekitInitializer.class);

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("orekit-data");
        File orekitData = resource.getFile();
        DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        manager.addProvider(new DirectoryCrawler(orekitData));
        log.info("Orekit initialisé depuis : {}", orekitData.getAbsolutePath());
    }
}
