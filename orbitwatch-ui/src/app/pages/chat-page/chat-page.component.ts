import { AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef, Component, inject, ViewChild } from '@angular/core';
import { ActivatedRoute, RouterLink, RouterLinkActive } from '@angular/router';
import { combineLatest, catchError, of } from 'rxjs';
import { ChatComponent } from '../../components/chat/chat.component';
import { AlertBadgeComponent, CombinedAlerts } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert } from '../../models/satellite.model';

@Component({
  selector: 'app-chat-page',
  standalone: true,
  imports: [ChatComponent, RouterLink, RouterLinkActive, AlertBadgeComponent, AlertPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat-page.component.html',
  styleUrl:    './chat-page.component.scss'
})
export class ChatPageComponent implements AfterViewInit {

  @ViewChild('chatComp') chatComp!: ChatComponent;

  panelOpen    = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private readonly route              = inject(ActivatedRoute);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  ngAfterViewInit(): void {
    this.route.queryParams.subscribe(params => {
      const q = params['q'];
      if (q) { this.chatComp.prefill(decodeURIComponent(q)); }
    });
  }

  openPanel(alerts: CombinedAlerts): void {
    this.panelAlerts = alerts;
    this.panelOpen   = true;
    this.cdr.markForCheck();
  }

  closePanel(): void {
    this.panelOpen = false;
    this.cdr.markForCheck();
  }

  refreshPanel(): void {
    combineLatest([
      this.conjunctionService.getUnreadAlerts().pipe(catchError(() => of([] as ConjunctionAlert[]))),
      this.anomalyService.getUnreadAlerts().pipe(catchError(() => of([] as AnomalyAlert[])))
    ]).subscribe(([conjunctions, anomalies]) => {
      this.panelAlerts = { conjunctions, anomalies };
      this.cdr.markForCheck();
    });
  }
}
