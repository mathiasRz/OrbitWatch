package projet.OrbitWatch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration JDBC pour {@link JdbcChatMemory}.
 * Utilise @DataJpaTest (H2 en mémoire) qui expose JdbcTemplate.
 * La table chat_history est créée manuellement dans @BeforeEach
 * (Flyway ne tourne pas avec @DataJpaTest).
 */
@DataJpaTest
@Import(JdbcChatMemory.class)
@ActiveProfiles("test")
class JdbcChatMemoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcChatMemory chatMemory;

    private static final String SESSION_A = "session-aaa-111";
    private static final String SESSION_B = "session-bbb-222";

    @BeforeEach
    void setupTable() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_history (
                id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                session_id VARCHAR(36)  NOT NULL,
                role       VARCHAR(20)  NOT NULL,
                content    TEXT         NOT NULL,
                created_at TIMESTAMP    NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("DELETE FROM chat_history");
    }

    // ── add + get ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("add() 3 messages → get() retourne 3 messages en ordre chronologique")
    void add_threeMessages_getReturnsInChronologicalOrder() {
        chatMemory.add(SESSION_A, List.of(
            new UserMessage("Bonjour"),
            new AssistantMessage("Salut !"),
            new UserMessage("Où est l'ISS ?")
        ));

        List<Message> result = chatMemory.get(SESSION_A, 10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("Bonjour");
        assertThat(result.get(1).getText()).isEqualTo("Salut !");
        assertThat(result.get(2).getText()).isEqualTo("Où est l'ISS ?");
    }

    @Test
    @DisplayName("get() avec lastN=2 sur 5 messages → 2 derniers seulement")
    void get_withLastN2_returnsOnlyLast2() {
        chatMemory.add(SESSION_A, List.of(
            new UserMessage("1"),
            new AssistantMessage("2"),
            new UserMessage("3"),
            new AssistantMessage("4"),
            new UserMessage("5")
        ));

        List<Message> result = chatMemory.get(SESSION_A, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("4");
        assertThat(result.get(1).getText()).isEqualTo("5");
    }

    @Test
    @DisplayName("get() sur session inconnue → liste vide (pas d'exception)")
    void get_unknownSession_returnsEmptyList() {
        List<Message> result = chatMemory.get("session-inconnue", 10);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Sessions distinctes → isolation complète des messages")
    void add_twoSessions_isolatedHistories() {
        chatMemory.add(SESSION_A, List.of(new UserMessage("Message session A")));
        chatMemory.add(SESSION_B, List.of(new UserMessage("Message session B")));

        List<Message> resultA = chatMemory.get(SESSION_A, 10);
        List<Message> resultB = chatMemory.get(SESSION_B, 10);

        assertThat(resultA).hasSize(1);
        assertThat(resultA.get(0).getText()).isEqualTo("Message session A");
        assertThat(resultB).hasSize(1);
        assertThat(resultB.get(0).getText()).isEqualTo("Message session B");
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("clear() → get() retourne liste vide")
    void clear_thenGetReturnsEmpty() {
        chatMemory.add(SESSION_A, List.of(
            new UserMessage("message avant clear"),
            new AssistantMessage("réponse avant clear")
        ));

        chatMemory.clear(SESSION_A);

        assertThat(chatMemory.get(SESSION_A, 10)).isEmpty();
    }

    @Test
    @DisplayName("clear() sur session A n'affecte pas session B")
    void clear_sessionA_doesNotAffectSessionB() {
        chatMemory.add(SESSION_A, List.of(new UserMessage("A")));
        chatMemory.add(SESSION_B, List.of(new UserMessage("B")));

        chatMemory.clear(SESSION_A);

        assertThat(chatMemory.get(SESSION_A, 10)).isEmpty();
        assertThat(chatMemory.get(SESSION_B, 10)).hasSize(1);
    }

    // ── Types de messages ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Les 3 rôles USER / ASSISTANT / SYSTEM sont correctement persistés et reconstruits")
    void add_allRoles_reconstructedCorrectly() {
        chatMemory.add(SESSION_A, List.of(
            new SystemMessage("Tu es un assistant spatial."),
            new UserMessage("Quelle est l'altitude de l'ISS ?"),
            new AssistantMessage("L'ISS orbite à environ 408 km.")
        ));

        List<Message> result = chatMemory.get(SESSION_A, 10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(1)).isInstanceOf(UserMessage.class);
        assertThat(result.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("add() avec liste vide → aucune insertion, get() retourne vide")
    void add_emptyList_noInsert() {
        chatMemory.add(SESSION_A, List.of());

        assertThat(chatMemory.get(SESSION_A, 10)).isEmpty();
    }
}
