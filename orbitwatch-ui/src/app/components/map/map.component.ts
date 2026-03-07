import {
  Component, Input, OnChanges, OnDestroy,
  SimpleChanges, AfterViewInit, ElementRef, ViewChild
} from '@angular/core';
import * as L from 'leaflet';
import { SatellitePosition } from '../../models/satellite-position.model';

@Component({
  selector: 'app-map',
  standalone: true,
  templateUrl: './map.component.html',
  styleUrl: './map.component.scss'
})
export class MapComponent implements AfterViewInit, OnChanges, OnDestroy {
  @ViewChild('mapContainer', { static: true }) mapContainer!: ElementRef<HTMLDivElement>;
  @Input() track: SatellitePosition[] = [];

  private map?: L.Map;
  private polyline?: L.Polyline;
  private marker?: L.Marker;

  ngAfterViewInit(): void {
    this.initMap();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['track'] && this.map) {
      this.renderTrack();
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

    if (this.track.length) {
      this.renderTrack();
    }
  }

  private renderTrack(): void {
    if (!this.map) return;

    // Nettoyer les couches précédentes
    this.polyline?.remove();
    this.marker?.remove();

    if (!this.track.length) return;

    const latlngs: L.LatLngTuple[] = this.track.map(p => [p.latitude, p.longitude]);

    this.polyline = L.polyline(latlngs, {
      color: '#00d4ff',
      weight: 2,
      opacity: 0.85
    }).addTo(this.map);

    // Marqueur sur la position initiale (époque du TLE)
    const first = this.track[0];
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

    this.map.fitBounds(this.polyline.getBounds(), { padding: [20, 20] });
  }
}

