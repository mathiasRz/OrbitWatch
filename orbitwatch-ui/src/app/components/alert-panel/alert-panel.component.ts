import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  inject,
  Input,
  Output
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { Router } from '@angular/router';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert } from '../../models/satellite.model';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { CombinedAlerts } from '../alert-badge/alert-badge.component';

/**
 * Panneau latéral (drawer) avec onglets Conjunctions / Anomalies.
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

  /** Rétro-compatibilité : accepte aussi un tableau plat de ConjunctionAlert */
  @Input() set alerts(val: ConjunctionAlert[] | CombinedAlerts) {
    if (Array.isArray(val)) {
      this._conjunctions = val;
      this._anomalies    = [];
    } else {
      this._conjunctions = val.conjunctions;
      this._anomalies    = val.anomalies;
    }
  }

  @Output() close         = new EventEmitter<void>();
  @Output() alertsChanged = new EventEmitter<void>();

  activeTab: 'conjunctions' | 'anomalies' = 'conjunctions';

  _conjunctions: ConjunctionAlert[] = [];
  _anomalies: AnomalyAlert[] = [];

  private readonly router             = inject(Router);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  get conjunctionCount(): number { return this._conjunctions.length; }
  get anomalyCount():     number { return this._anomalies.length; }

  // ── Helpers UI ──────────────────────────────────────────────────────────────

  conjSeverityClass(distanceKm: number): string {
    if (distanceKm < 1)  return 'critical';
    if (distanceKm < 5)  return 'warning';
    return 'caution';
  }

  anomalySeverityClass(severity: string): string {
    switch (severity) {
      case 'HIGH':   return 'critical';
      case 'MEDIUM': return 'warning';
      default:       return 'caution';
    }
  }

  anomalyTypeLabel(type: string): string {
    switch (type) {
      case 'ALTITUDE_CHANGE':     return 'Δ Altitude';
      case 'INCLINATION_CHANGE':  return 'Δ Inclinaison';
      case 'RAAN_DRIFT':          return 'Dérive RAAN';
      case 'ECCENTRICITY_CHANGE': return 'Δ Excentricité';
      case 'STATISTICAL':         return 'Z-score';
      default: return type;
    }
  }

  // ── Actions ─────────────────────────────────────────────────────────────────

  openConjunction(alert: ConjunctionAlert): void {
    this.router.navigate(['/conjunction'], {
      queryParams: { sat1: alert.nameSat1, sat2: alert.nameSat2 }
    });
    this.close.emit();
  }

  openSatelliteProfile(noradId: number): void {
    this.router.navigate(['/satellite', noradId]);
    this.close.emit();
  }

  ackConjunction(alert: ConjunctionAlert): void {
    this.conjunctionService.acknowledge(alert.id).subscribe({
      next: () => this.alertsChanged.emit(),
      error: err => console.error('[AlertPanel] Erreur acquittement conjunction', err)
    });
  }

  ackAnomaly(alert: AnomalyAlert): void {
    this.anomalyService.acknowledge(alert.id).subscribe({
      next: () => this.alertsChanged.emit(),
      error: err => console.error('[AlertPanel] Erreur acquittement anomalie', err)
    });
  }
}
