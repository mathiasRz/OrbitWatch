import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_ENDPOINTS } from '../config/api-endpoints';

/**
 * Service Angular pour le chat RAG OrbitWatch.
 * Utilise l'API Fetch avec streaming pour consommer le flux SSE du backend Spring.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {

  /**
   * Envoie une question au backend et retourne un Observable qui émet
   * les tokens de la réponse LLM au fur et à mesure du streaming SSE.
   *
   * @param question Question en langage naturel
   * @returns Observable<string> émettant les tokens un à un
   */
  streamChat(question: string): Observable<string> {
    return new Observable<string>(observer => {
      const controller = new AbortController();

      fetch(API_ENDPOINTS.ai.chat, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
        signal: controller.signal
      })
        .then(response => {
          if (!response.ok) {
            observer.error(new Error(`HTTP ${response.status}`));
            return;
          }
          const reader  = response.body!.getReader();
          const decoder = new TextDecoder();
          // Buffer pour reconstituer les événements SSE qui peuvent être fragmentés
          // sur plusieurs chunks TCP.
          let buffer = '';

          const pump = (): Promise<void> =>
            reader.read().then(({ done, value }) => {
              if (done) {
                // Traite le reste du buffer si non vide
                flushBuffer(buffer);
                observer.complete();
                return;
              }
              buffer += decoder.decode(value, { stream: true });

              // Un événement SSE se termine par une ligne vide (\n\n)
              const events = buffer.split('\n\n');
              // Le dernier élément est potentiellement incomplet — on le conserve
              buffer = events.pop() ?? '';

              events.forEach(event => {
                // Reconstitue le token à partir des lignes "data:" (gère les tokens multi-lignes)
                const dataLines = event.split('\n').filter(l => l.startsWith('data:'));
                if (dataLines.length === 0) return;
                // Retire exactement le préfixe "data:" sans toucher aux espaces du token
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

      // Nettoyage : annulation du fetch si l'Observable est unsubscribed
      return () => controller.abort();
    });
  }
}

