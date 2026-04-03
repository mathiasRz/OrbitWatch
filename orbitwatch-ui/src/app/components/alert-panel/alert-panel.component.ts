import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  inject,
  Input,
  Output
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { Router } from '@angular/router';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { ConjunctionService } from '../../services/conjunction.service';

/**
 * Panneau latéral (drawer) listant les alertes de rapprochement non acquittées.
 */
@Component({
  selector: 'app-alert-panel',
  standalone: true,
  imports: [CommonModule, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-panel.component.html',
  styleUrl: './alert-panel.component.scss'
})
export class AlertPanelComponent {
  @Input()  alerts: ConjunctionAlert[] = [];
  @Output() close         = new EventEmitter<void>();
  @Output() alertsChanged = new EventEmitter<void>();

  private readonly router             = inject(Router);
  private readonly conjunctionService = inject(ConjunctionService);

  severityClass(distanceKm: number): string {
    if (distanceKm < 1)   return 'critical';
    if (distanceKm < 5)   return 'warning';
    return 'caution';
  }

  openConjunction(alert: ConjunctionAlert): void {
    this.router.navigate(['/conjunction'], {
      queryParams: { sat1: alert.nameSat1, sat2: alert.nameSat2 }
    });
    this.close.emit();
  }

  ackAlert(alert: ConjunctionAlert): void {
    this.conjunctionService.acknowledge(alert.id).subscribe({
      next: () => this.alertsChanged.emit(),
      error: err => console.error('[AlertPanel] Erreur acquittement', err)
    });
  }
}
