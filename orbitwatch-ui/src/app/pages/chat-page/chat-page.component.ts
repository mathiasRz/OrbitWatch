import { AfterViewInit, ChangeDetectionStrategy, Component, inject, ViewChild } from '@angular/core';
import { ActivatedRoute, RouterLink, RouterLinkActive } from '@angular/router';
import { ChatComponent } from '../../components/chat/chat.component';

@Component({
  selector: 'app-chat-page',
  standalone: true,
  imports: [ChatComponent, RouterLink, RouterLinkActive],
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
        const decoded = decodeURIComponent(q);
        if (this.chatComp.messages.length === 0) {
          this.chatComp.prefillAndSend(decoded);
        } else {
          this.chatComp.prefill(decoded);
        }
      }
    });
  }
}
