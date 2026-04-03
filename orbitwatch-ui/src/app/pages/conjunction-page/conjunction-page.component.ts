import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject
} from '@angular/core';
import { CommonModule, DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { ConjunctionFormComponent } from '../../components/conjunction-form/conjunction-form.component';
import { ConjunctionMapComponent } from '../../components/conjunction-map/conjunction-map.component';
import { AlertBadgeComponent } from '../../components/alert-badge/alert-badge.component';
import { AlertPanelComponent } from '../../components/alert-panel/alert-panel.component';
import { ConjunctionAlert, ConjunctionReport } from '../../models/conjunction.model';
import { ConjunctionService } from '../../services/conjunction.service';

@Component({
  selector: 'app-conjunction-page',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    DecimalPipe,
    RouterLink,
    RouterLinkActive,
    ConjunctionFormComponent,
    ConjunctionMapComponent,
    AlertBadgeComponent,
    AlertPanelComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './conjunction-page.component.html',
  styleUrl: './conjunction-page.component.scss'
})
export class ConjunctionPageComponent {

  report: ConjunctionReport | null = null;
  isLoading   = false;
  errorMessage: string | null = null;
  panelOpen   = false;
  panelAlerts: ConjunctionAlert[] = [];

  private readonly cdr                = inject(ChangeDetectorRef);
  private readonly conjunctionService = inject(ConjunctionService);

  onReportReady(report: ConjunctionReport): void {
    this.report = report;
    this.cdr.markForCheck();
  }

  onLoading(v: boolean): void {
    this.isLoading = v;
    this.cdr.markForCheck();
  }

  onError(msg: string | null): void {
    this.errorMessage = msg;
    this.cdr.markForCheck();
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

  severityClass(distanceKm: number): string {
    if (distanceKm < 1)  return 'critical';
    if (distanceKm < 5)  return 'warning';
    return 'caution';
  }
}




