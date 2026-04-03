import {
  Component, input, effect, OnDestroy,
  AfterViewInit, ElementRef, ViewChild
} from '@angular/core';
import * as L from 'leaflet';
import { SatellitePosition } from '../../models/satellite-position.model';
import { splitAtAntimeridian } from '../../utils/antimeridian';

@Component({
  selector: 'app-map',
  standalone: true,
  templateUrl: './map.component.html',
  styleUrl: './map.component.scss'
})
export class MapComponent implements AfterViewInit, OnDestroy {
  @ViewChild('mapContainer', { static: true }) mapContainer!: ElementRef<HTMLDivElement>;
  track = input<SatellitePosition[]>([]);

  private map?: L.Map;
  private polylines: L.Polyline[] = [];
  private marker?: L.Marker;

  constructor() {
    effect(() => {
      const t = this.track();
      if (this.map) {
        this.renderTrack(t);
      }
    });
  }

  ngAfterViewInit(): void {
    this.initMap();
    const t = this.track();
    if (t.length) {
      this.renderTrack(t);
    }
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  private initMap(): void {
    this.map = L.map(this.mapContainer.nativeElement, {
      center: [0, 0],
      zoom: 2,
      worldCopyJump: true
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 18
    }).addTo(this.map);
  }

  private renderTrack(track: SatellitePosition[]): void {
    if (!this.map) return;

    this.polylines.forEach(pl => pl.remove());
    this.polylines = [];
    this.marker?.remove();

    if (!track.length) return;

    // Découpe robuste à l'anti-méridien avec interpolation
    const segments = splitAtAntimeridian(track);

    for (const seg of segments) {
      const pl = L.polyline(seg, {
        color: '#00d4ff',
        weight: 2,
        opacity: 0.85
      }).addTo(this.map);
      this.polylines.push(pl);
    }

    const first = track[0];
    this.marker = L.marker([first.latitude, first.longitude], {
      icon: L.divIcon({
        className: '',
        html: `<div style="
          width:12px;height:12px;
          background:#ff4b4b;border:2px solid #fff;
          border-radius:50%;box-shadow:0 0 6px #ff4b4b;
        "></div>`,
        iconSize: [12, 12],
        iconAnchor: [6, 6]
      })
    })
    .bindPopup(`
      <strong>${first.name}</strong><br>
      Lat : ${first.latitude.toFixed(4)}°<br>
      Lon : ${first.longitude.toFixed(4)}°<br>
      Alt : ${first.altitude.toFixed(1)} km<br>
      <small>${first.epoch}</small>
    `)
    .addTo(this.map);

    if (this.polylines.length > 0) {
      const group = L.featureGroup(this.polylines);
      this.map.fitBounds(group.getBounds(), { padding: [20, 20] });
    }
  }
}

