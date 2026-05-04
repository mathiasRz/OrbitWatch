import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_ENDPOINTS } from '../config/api-endpoints';

export interface ChatMessageDto {
  role: string;
  content: string;
  createdAt: string | null;
}

/**
 * Service Angular pour le chat IA OrbitWatch.
 * Gère le streaming SSE, le sessionId persistant et l'historique.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {

  private static readonly SESSION_KEY = 'orbitwatch_session_id';

  constructor(private http: HttpClient) {}

  /** Retourne le sessionId existant ou en génère un nouveau (persisté dans localStorage). */
  getOrCreateSessionId(): string {
    let id = localStorage.getItem(ChatService.SESSION_KEY);
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem(ChatService.SESSION_KEY, id);
    }
    return id;
  }

  /** Génère et persiste un nouveau sessionId (nouvelle conversation). */
  newSessionId(): string {
    const id = crypto.randomUUID();
    localStorage.setItem(ChatService.SESSION_KEY, id);
    return id;
  }

  /**
   * Envoie une question au backend et retourne un Observable qui émet
   * les tokens de la réponse LLM au fur et à mesure du streaming SSE.
   */
  streamChat(question: string, sessionId: string): Observable<string> {
    return new Observable<string>(observer => {
      const controller = new AbortController();

      fetch(API_ENDPOINTS.ai.chat, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, sessionId }),
        signal: controller.signal
      })
        .then(response => {
          if (!response.ok) {
            observer.error(new Error(`HTTP ${response.status}`));
            return;
          }
          const reader  = response.body!.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          const pump = (): Promise<void> =>
            reader.read().then(({ done, value }) => {
              if (done) {
                flushBuffer(buffer);
                observer.complete();
                return;
              }
              buffer += decoder.decode(value, { stream: true });
              const events = buffer.split('\n\n');
              buffer = events.pop() ?? '';
              events.forEach(event => {
                const dataLines = event.split('\n').filter(l => l.startsWith('data:'));
                if (dataLines.length === 0) return;
                const token = dataLines.map(l => l.slice('data:'.length)).join('\n');
                if (token.length > 0) observer.next(token);
              });
              return pump();
            });

          function flushBuffer(remaining: string): void {
            if (!remaining.trim()) return;
            const dataLines = remaining.split('\n').filter(l => l.startsWith('data:'));
            if (dataLines.length === 0) return;
            const token = dataLines.map(l => l.slice('data:'.length)).join('\n');
            if (token.length > 0) observer.next(token);
          }

          pump().catch(err => {
            if (err?.name !== 'AbortError') observer.error(err);
          });
        })
        .catch(err => {
          if (err?.name !== 'AbortError') observer.error(err);
        });

      return () => controller.abort();
    });
  }

  /** Charge l'historique des messages d'une session. */
  loadHistory(sessionId: string): Observable<ChatMessageDto[]> {
    return this.http.get<ChatMessageDto[]>(
      `${API_ENDPOINTS.ai.history}?sessionId=${encodeURIComponent(sessionId)}`
    );
  }

  /** Efface l'historique d'une session côté serveur. */
  clearHistory(sessionId: string): Observable<void> {
    return this.http.delete<void>(
      `${API_ENDPOINTS.ai.history}?sessionId=${encodeURIComponent(sessionId)}`
    );
  }
}
