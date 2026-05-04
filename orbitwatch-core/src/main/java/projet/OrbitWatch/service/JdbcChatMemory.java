package projet.OrbitWatch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implémentation JDBC de {@link ChatMemory} pour la persistance des échanges de chat
 * dans la table {@code chat_history} (migration V6).
 *
 * <p>Spring AI 2.0.0-M4 ne fournit qu'une implémentation {@code InMemoryChatMemory} —
 * cette classe persiste les messages en base de données via {@link JdbcTemplate}.
 *
 * <p>Chaque message est stocké avec un {@code session_id} (UUID navigateur) et un
 * {@code role} parmi : {@code USER}, {@code ASSISTANT}, {@code SYSTEM}.
 */
@Component
public class JdbcChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemory.class);

    /** Nombre maximum de messages chargés par session pour ne pas dépasser la fenêtre LLM. */
    private static final int MAX_MESSAGES = 50;

    private final JdbcTemplate jdbc;

    public JdbcChatMemory(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    // ── ChatMemory API ────────────────────────────────────────────────────────

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        for (Message msg : messages) {
            String role = toRole(msg);
            if (role == null) {
                log.warn("[ChatMemory] Type de message non supporté ignoré : {}", msg.getClass().getSimpleName());
                continue;
            }
            jdbc.update(
                "INSERT INTO chat_history (session_id, role, content, created_at) VALUES (?, ?, ?, now())",
                conversationId,
                role,
                msg.getText()
            );
        }
        log.debug("[ChatMemory] {} message(s) persisté(s) pour la session {}", messages.size(), conversationId);
    }

		@Override
		public List<Message> get(String s) {
			return List.of();
		}

    public List<Message> get(String conversationId, int lastN) {
        int limit = Math.min(lastN, MAX_MESSAGES);

        // Récupère les N derniers en ordre DESC puis inverse pour ordre chronologique
        List<Message> messages = jdbc.query(
            "SELECT role, content FROM chat_history WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            (rs, rowNum) -> toMessage(rs.getString("role"), rs.getString("content")),
            conversationId,
            limit
        );

        // Retirer les nulls (roles inconnus) et remettre en ordre chronologique
        List<Message> result = new ArrayList<>(messages.stream().filter(m -> m != null).toList());
        Collections.reverse(result);
        return result;
    }

    @Override
    public void clear(String conversationId) {
        int deleted = jdbc.update(
            "DELETE FROM chat_history WHERE session_id = ?",
            conversationId
        );
        log.info("[ChatMemory] Session {} effacée ({} message(s) supprimé(s))", conversationId, deleted);
    }

    // ── Conversion Message ↔ role ─────────────────────────────────────────────

    private String toRole(Message msg) {
        return switch (msg.getMessageType()) {
            case USER      -> "USER";
            case ASSISTANT -> "ASSISTANT";
            case SYSTEM    -> "SYSTEM";
            default        -> null;
        };
    }

    private Message toMessage(String role, String content) {
        return switch (role) {
            case "USER"      -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM"    -> new SystemMessage(content);
            default -> {
                log.warn("[ChatMemory] Rôle inconnu en base : '{}' — message ignoré", role);
                yield null;
            }
        };
    }
}

