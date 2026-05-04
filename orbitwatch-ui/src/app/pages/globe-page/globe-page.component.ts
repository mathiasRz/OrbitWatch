import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { GlobeComponent } from '../../components/globe/globe.component';

@Component({
  selector: 'app-globe-page',
  standalone: true,
  imports: [GlobeComponent],
  template: `<app-globe [targetSatellite]="targetSatellite"></app-globe>`,
  styles: [`:host { display: flex; flex-direction: column; height: 100vh; }`]
})
export class GlobePageComponent {
  private readonly route = inject(ActivatedRoute);
  readonly targetSatellite = this.route.snapshot.queryParamMap.get('satellite') ?? undefined;
}

