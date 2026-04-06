import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';
import { OrbitalElements } from '../../models/satellite.model';

Chart.register(...registerables);

@Component({
  selector: 'app-orbital-chart',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './orbital-chart.component.html',
  styleUrl: './orbital-chart.component.scss'
})
export class OrbitalChartComponent implements AfterViewInit, OnChanges, OnDestroy {

  @Input() history: OrbitalElements[] = [];

  @ViewChild('altChart')  altChartRef!:  ElementRef<HTMLCanvasElement>;
  @ViewChild('incChart')  incChartRef!:  ElementRef<HTMLCanvasElement>;
  @ViewChild('raanChart') raanChartRef!: ElementRef<HTMLCanvasElement>;
  @ViewChild('eccChart')  eccChartRef!:  ElementRef<HTMLCanvasElement>;

  private altChart?:  Chart;
  private incChart?:  Chart;
  private raanChart?: Chart;
  private eccChart?:  Chart;

  extraOpen = false;

  ngAfterViewInit(): void {
    this.buildCharts();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['history'] && !changes['history'].firstChange) {
      this.destroyCharts();
      this.buildCharts();
    }
  }

  ngOnDestroy(): void {
    this.destroyCharts();
  }

  private get sorted(): OrbitalElements[] {
    return [...this.history].sort((a, b) => a.epochTle.localeCompare(b.epochTle));
  }

  private buildCharts(): void {
    if (!this.altChartRef) return;
    const data = this.sorted;
    const labels = data.map(e => {
      const d = new Date(e.epochTle);
      return `${d.getUTCMonth() + 1}/${d.getUTCDate()} ${String(d.getUTCHours()).padStart(2,'0')}h`;
    });

    // Graphe 1 — altitude périgée / apogée
    this.altChart = this.createChart(this.altChartRef.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Périgée (km)',
            data: data.map(e => e.altitudePerigeeKm),
            borderColor: '#00d4ff',
            backgroundColor: 'rgba(0,212,255,0.08)',
            pointRadius: 3,
            tension: 0.3,
            fill: false
          },
          {
            label: 'Apogée (km)',
            data: data.map(e => e.altitudeApogeeKm),
            borderColor: '#ff9900',
            backgroundColor: 'rgba(255,153,0,0.08)',
            pointRadius: 3,
            tension: 0.3,
            fill: false
          }
        ]
      },
      options: this.chartOptions('Altitude (km)')
    });

    // Graphe 2 — inclinaison
    this.incChart = this.createChart(this.incChartRef.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [{
          label: 'Inclinaison (°)',
          data: data.map(e => e.inclinationDeg),
          borderColor: '#b388ff',
          backgroundColor: 'rgba(179,136,255,0.08)',
          pointRadius: 3,
          tension: 0.3,
          fill: false
        }]
      },
      options: this.chartOptions('Inclinaison (°)')
    });

    // Graphe 3 — RAAN (accordéon)
    this.raanChart = this.createChart(this.raanChartRef.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [{
          label: 'RAAN (°)',
          data: data.map(e => e.raanDeg),
          borderColor: '#69f0ae',
          backgroundColor: 'rgba(105,240,174,0.08)',
          pointRadius: 3,
          tension: 0.3,
          fill: false
        }]
      },
      options: this.chartOptions('RAAN (°)')
    });

    // Graphe 4 — excentricité (accordéon)
    this.eccChart = this.createChart(this.eccChartRef.nativeElement, {
      type: 'line',
      data: {
        labels,
        datasets: [{
          label: 'Excentricité',
          data: data.map(e => e.eccentricity),
          borderColor: '#ff5252',
          backgroundColor: 'rgba(255,82,82,0.08)',
          pointRadius: 3,
          tension: 0.3,
          fill: false
        }]
      },
      options: this.chartOptions('Excentricité')
    });
  }

  private createChart(canvas: HTMLCanvasElement, config: ChartConfiguration): Chart {
    return new Chart(canvas, config);
  }

  private chartOptions(yLabel: string): ChartConfiguration['options'] {
    return {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: {
          labels: { color: '#c0c0d0', font: { size: 11 } }
        },
        tooltip: {
          mode: 'index',
          intersect: false
        }
      },
      scales: {
        x: {
          ticks: { color: '#888', maxRotation: 45, font: { size: 10 } },
          grid:  { color: 'rgba(255,255,255,0.05)' }
        },
        y: {
          title: { display: true, text: yLabel, color: '#888', font: { size: 10 } },
          ticks: { color: '#888', font: { size: 10 } },
          grid:  { color: 'rgba(255,255,255,0.05)' }
        }
      }
    };
  }

  private destroyCharts(): void {
    this.altChart?.destroy();
    this.incChart?.destroy();
    this.raanChart?.destroy();
    this.eccChart?.destroy();
    this.altChart = this.incChart = this.raanChart = this.eccChart = undefined;
  }
}

