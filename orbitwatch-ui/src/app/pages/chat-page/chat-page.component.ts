import { AfterViewInit, ChangeDetectionStrategy, Component, inject, ViewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ChatComponent } from '../../components/chat/chat.component';

/**
 * Page dédiée à l'assistant IA RAG OrbitWatch.
 * Route : /chat
 *
 * Lit le query param `?q=` au démarrage pour préremplir la question
 * (utilisé depuis SatelliteProfilePage : "🤖 Demander à l'IA").
 */
@Component({
  selector: 'app-chat-page',
  standalone: true,
  imports: [ChatComponent, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat-page.component.html',
  styleUrl:    './chat-page.component.scss'
})
export class ChatPageComponent implements AfterViewInit {

  @ViewChild('chatComp') chatComp!: ChatComponent;

  private readonly route = inject(ActivatedRoute);

  ngAfterViewInit(): void {
    this.route.queryParams.subscribe(params => {
      const q = params['q'];
      if (q) {
        this.chatComp.prefill(decodeURIComponent(q));
      }
    });
  }
}


