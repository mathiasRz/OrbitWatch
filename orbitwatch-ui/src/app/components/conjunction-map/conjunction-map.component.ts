import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  inject,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import * as L from 'leaflet';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConjunctionReport } from '../../models/conjunction.model';
import { OrbitService } from '../../services/orbit.service';
import { SatellitePosition } from '../../models/satellite-position.model';
import { splitAtAntimeridian } from '../../utils/antimeridian';

/**
 * Carte Leaflet affichant les deux ground tracks complets d'une conjunction
 * (chargés via OrbitService.getGroundTrack) et un marqueur ⚠ sur le TCA le plus proche.
 */
@Component({
  selector: 'app-conjunction-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './conjunction-map.component.html',
  styleUrl: './conjunction-map.component.scss'
})
export class ConjunctionMapComponent implements AfterViewInit, OnChanges, OnDestroy {

  @Input() report: ConjunctionReport | null = null;
  @ViewChild('mapEl', { static: true }) mapEl!: ElementRef<HTMLDivElement>;

  loading = false;

  private map!: L.Map;
  private layerGroup = L.layerGroup();

  private readonly orbitService = inject(OrbitService);
  private readonly cdr          = inject(ChangeDetectorRef);

  ngAfterViewInit(): void {
    this.map = L.map(this.mapEl.nativeElement, {
      center: [20, 0], zoom: 2, zoomControl: true, attributionControl: false,
      worldCopyJump: true
    });
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(this.map);
    this.layerGroup.addTo(this.map);

    if (this.report) this.loadAndDraw(this.report);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['report'] && this.map) {
      this.layerGroup.clearLayers();
      if (this.report) this.loadAndDraw(this.report);
    }
  }

  ngOnDestroy(): void { this.map?.remove(); }

  // ── Charge les ground tracks des deux satellites puis dessine ──────────────
  private loadAndDraw(report: ConjunctionReport): void {
    this.loading = true;
    this.cdr.markForCheck();

    // Ground track limité à 90 min (≈ 1 orbite LEO) pour un affichage lisible
    const durationMin = 90;

    const gt1$ = this.orbitService.getGroundTrack({
      name: report.nameSat1, epoch: report.windowStart, duration: durationMin, step: 60
    }).pipe(catchError(() => of([] as SatellitePosition[])));

    const gt2$ = this.orbitService.getGroundTrack({
      name: report.nameSat2, epoch: report.windowStart, duration: durationMin, step: 60
    }).pipe(catchError(() => of([] as SatellitePosition[])));

    forkJoin([gt1$, gt2$]).subscribe(([track1, track2]) => {
      this.loading = false;
      this.layerGroup.clearLayers();
      this.drawTracks(report, track1, track2);
      this.cdr.markForCheck();
    });
  }

  // ── Dessin des polylines + marqueur TCA ────────────────────────────────────
  private drawTracks(
    report: ConjunctionReport,
    track1: SatellitePosition[],
    track2: SatellitePosition[]
  ): void {

    // Satellite 1 — cyan
    splitAtAntimeridian(track1).forEach(seg =>
      L.polyline(seg, { color: '#00d4ff', weight: 2, opacity: 0.9 })
        .bindTooltip(report.nameSat1, { sticky: true })
        .addTo(this.layerGroup)
    );

    // Satellite 2 — orange
    splitAtAntimeridian(track2).forEach(seg =>
      L.polyline(seg, { color: '#ff8800', weight: 2, opacity: 0.9 })
        .bindTooltip(report.nameSat2, { sticky: true })
        .addTo(this.layerGroup)
    );

    // Marqueurs de position au moment du TCA sur chaque ground track
    if (report.events.length > 0) {
      const closest = report.events[0];

      const icon1 = L.divIcon({
        className: '',
        html: `<div style="width:10px;height:10px;background:#00d4ff;border-radius:50%;border:2px solid #fff"></div>`,
        iconAnchor: [5, 5]
      });
      const icon2 = L.divIcon({
        className: '',
        html: `<div style="width:10px;height:10px;background:#ff8800;border-radius:50%;border:2px solid #fff"></div>`,
        iconAnchor: [5, 5]
      });
      const iconWarn = L.divIcon({
        className: '',
        html: `<div style="font-size:1.6rem;line-height:1;filter:drop-shadow(0 0 6px #ff0)">⚠</div>`,
        iconAnchor: [12, 12]
      });

      // Marqueur position sat1 au TCA
      L.marker([closest.sat1.latitude, closest.sat1.longitude], { icon: icon1 })
        .bindTooltip(`${report.nameSat1} @ TCA`)
        .addTo(this.layerGroup);

      // Marqueur position sat2 au TCA
      L.marker([closest.sat2.latitude, closest.sat2.longitude], { icon: icon2 })
        .bindTooltip(`${report.nameSat2} @ TCA`)
        .addTo(this.layerGroup);

      // Marqueur ⚠ au point médian
      const midLat = (closest.sat1.latitude  + closest.sat2.latitude)  / 2;
      const midLon = (closest.sat1.longitude + closest.sat2.longitude) / 2;
      L.marker([midLat, midLon], { icon: iconWarn })
        .bindPopup(`
          <b>⚠ TCA — Rapprochement le plus proche</b><br>
          <b>${report.nameSat1}</b> ↔ <b>${report.nameSat2}</b><br>
          Distance : <b style="color:#ff4444">${closest.distanceKm.toFixed(3)} km</b><br>
          TCA : ${new Date(closest.tca).toUTCString()}<br>
          Alt. sat1 : ${closest.sat1.altitude.toFixed(1)} km —
          Alt. sat2 : ${closest.sat2.altitude.toFixed(1)} km
        `, { maxWidth: 280 })
        .addTo(this.layerGroup)
        .openPopup();

      // Centre la carte sur le TCA
      this.map.setView([midLat, midLon], 3);
    } else if (track1.length > 0) {
      // Aucun TCA : centre sur le début du ground track de sat1
      this.map.setView([track1[0].latitude, track1[0].longitude], 2);
    }

    setTimeout(() => this.map.invalidateSize(), 100);
  }
}
