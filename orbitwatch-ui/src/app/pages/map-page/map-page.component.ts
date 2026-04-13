import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { combineLatest, catchError, of } from 'rxjs';
import { MapLiveComponent } from '../../components/map-live/map-live.component';
import { AlertBadgeComponent, CombinedAlerts } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { ConjunctionService } from '../../services/conjunction.service';
import { AnomalyService } from '../../services/anomaly.service';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { AnomalyAlert } from '../../models/satellite.model';

@Component({
  selector: 'app-map-page',
  standalone: true,
  imports: [CommonModule, MapLiveComponent, RouterLink, RouterLinkActive, AlertBadgeComponent, AlertPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './map-page.component.html',
  styleUrl: './map-page.component.scss'
})
export class MapPageComponent {
  panelOpen    = false;
  panelAlerts: CombinedAlerts = { conjunctions: [], anomalies: [] };

  private readonly router             = inject(Router);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly anomalyService     = inject(AnomalyService);

  onSatelliteSelected(name: string): void {
    this.router.navigate(['/orbit'], { queryParams: { name } });
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
