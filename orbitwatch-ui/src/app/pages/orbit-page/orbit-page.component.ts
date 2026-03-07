import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { OrbitService, GroundTrackParams } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { MapComponent } from '../../components/map/map.component';
import { TleFormComponent } from '../../components/tle-form/tle-form.component';

@Component({
  selector: 'app-orbit-page',
  standalone: true,
  imports: [CommonModule, MapComponent, TleFormComponent],
  templateUrl: './orbit-page.component.html',
  styleUrl: './orbit-page.component.scss'
})
export class OrbitPageComponent {
  @ViewChild('tleForm') tleForm?: TleFormComponent;

  track: SatellitePosition[] = [];
  error: string | null = null;
  avgAltitude = 0;

  constructor(private orbitService: OrbitService) {}

  onPropagate(params: GroundTrackParams): void {
    this.error = null;
    this.tleForm?.setLoading(true);

    this.orbitService.getGroundTrack(params).subscribe({
      next: (data) => {
        this.track = data;
        this.avgAltitude = data.reduce((sum, p) => sum + p.altitude, 0) / data.length;
        this.tleForm?.setLoading(false);
      },
      error: (err) => {
        this.error = `Erreur lors de la propagation : ${err.status ?? ''} ${err.message}`;
        this.tleForm?.setLoading(false);
      }
    });
  }
}

