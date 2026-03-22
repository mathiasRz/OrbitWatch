import { Component, signal, computed, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { OrbitService, GroundTrackParams } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { MapComponent } from '../../components/map/map.component';
import { TleFormComponent } from '../../components/tle-form/tle-form.component';

@Component({
  selector: 'app-orbit-page',
  standalone: true,
  imports: [DecimalPipe, MapComponent, TleFormComponent, RouterLink],
  templateUrl: './orbit-page.component.html',
  styleUrl: './orbit-page.component.scss'
})
export class OrbitPageComponent implements OnInit {
  track    = signal<SatellitePosition[]>([]);
  error    = signal<string | null>(null);
  loading  = signal(false);
  avgAltitude = computed(() => {
    const t = this.track();
    if (!t.length) return 0;
    return t.reduce((sum, p) => sum + p.altitude, 0) / t.length;
  });

  constructor(private orbitService: OrbitService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    const name = this.route.snapshot.queryParamMap.get('name');
    if (name) {
      this.onPropagate({ name, duration: 90, step: 60 });
    }
  }

  onPropagate(params: GroundTrackParams): void {
    this.error.set(null);
    this.loading.set(true);

    this.orbitService.getGroundTrack(params).subscribe({
      next: (data) => {
        this.track.set([...data]);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(`Erreur lors de la propagation : ${err.status ?? ''} ${err.message}`);
        this.loading.set(false);
      }
    });
  }
}



