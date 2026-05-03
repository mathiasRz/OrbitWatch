import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component,
  inject, OnDestroy
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ChatService } from '../../services/chat.service';

/** Représente un message dans la conversation. */
interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/**
 * Composant de chat RAG OrbitWatch.
 *
 * Fonctionnement :
 * - L'utilisateur saisit une question et appuie sur "Envoyer"
 * - Le composant appelle `ChatService.streamChat()` qui consomme le flux SSE
 * - Les tokens sont accumulés dans `currentToken` et affichés en temps réel
 * - À la complétion du stream, le message assistant est consolidé dans `messages`
 */
@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChatComponent implements OnDestroy {

  messages:     ChatMessage[] = [];
  question      = '';
  currentToken  = '';  // tokens du message assistant en cours de stream
  isStreaming   = false;
  errorMessage  = '';

  private streamSub?: Subscription;
  private readonly chatService = inject(ChatService);
  private readonly cdr         = inject(ChangeDetectorRef);

  /** Pré-remplit la question (utilisé depuis ChatPageComponent via query param). */
  prefill(q: string): void {
    this.question = q;
    this.cdr.markForCheck();
  }

  send(): void {
    const q = this.question.trim();
    if (!q || this.isStreaming) return;

    // Ajout du message utilisateur
    this.messages.push({ role: 'user', content: q });
    this.question     = '';
    this.currentToken = '';
    this.isStreaming  = true;
    this.errorMessage = '';
    this.cdr.markForCheck();

    this.streamSub = this.chatService.streamChat(q).subscribe({
      next: (token: string) => {
        this.currentToken += token;
        this.cdr.markForCheck();
      },
      complete: () => {
        // Consolide le message assistant
        if (this.currentToken.trim()) {
          this.messages.push({ role: 'assistant', content: this.currentToken });
        }
        this.currentToken = '';
        this.isStreaming  = false;
        this.cdr.markForCheck();
      },
      error: (err: Error) => {
        this.errorMessage = `Erreur : ${err.message ?? 'Service IA indisponible'}`;
        this.currentToken = '';
        this.isStreaming  = false;
        this.cdr.markForCheck();
      }
    });
  }

  /** Permet d'envoyer avec la touche Entrée (sans Shift). */
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  clearHistory(): void {
    this.messages     = [];
    this.currentToken = '';
    this.errorMessage = '';
    this.cdr.markForCheck();
  }

  ngOnDestroy(): void {
    this.streamSub?.unsubscribe();
  }
}


