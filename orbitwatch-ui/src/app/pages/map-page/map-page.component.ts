import { Component } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { MapLiveComponent } from '../../components/map-live/map-live.component';

@Component({
  selector: 'app-map-page',
  standalone: true,
  imports: [MapLiveComponent, RouterLink, RouterLinkActive],
  templateUrl: './map-page.component.html',
  styleUrl: './map-page.component.scss'
})
export class MapPageComponent {
  constructor(private router: Router) {}

  /** Navigue vers la vue ground track pour le satellite sélectionné */
  onSatelliteSelected(name: string): void {
    this.router.navigate(['/orbit'], { queryParams: { name } });
  }
}


