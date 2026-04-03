import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { MapLiveComponent } from '../../components/map-live/map-live.component';
import { AlertBadgeComponent } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { ConjunctionAlert } from '../../models/conjunction.model';
import { ConjunctionService } from '../../services/conjunction.service';

@Component({
  selector: 'app-map-page',
  standalone: true,
  imports: [CommonModule, MapLiveComponent, RouterLink, RouterLinkActive, AlertBadgeComponent, AlertPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './map-page.component.html',
  styleUrl: './map-page.component.scss'
})
export class MapPageComponent {
  panelOpen   = false;
  panelAlerts: ConjunctionAlert[] = [];

  private readonly router             = inject(Router);
  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);

  /** Navigue vers la vue ground track pour le satellite sélectionné */
  onSatelliteSelected(name: string): void {
    this.router.navigate(['/orbit'], { queryParams: { name } });
  }

  openPanel(alerts: ConjunctionAlert[]): void {
    this.panelAlerts = alerts;
    this.panelOpen   = true;
    this.cdr.markForCheck();
  }

  closePanel(): void {
    this.panelOpen = false;
    this.cdr.markForCheck();
  }

  refreshPanel(): void {
    this.conjunctionService.getUnreadAlerts().subscribe(alerts => {
      this.panelAlerts = alerts;
      this.cdr.markForCheck();
    });
  }
}
