import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  inject,
  OnInit,
  Output
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ConjunctionService } from '../../services/conjunction.service';
import { OrbitService } from '../../services/orbit.service';
import { ConjunctionReport } from '../../models/conjunction.model';

/**
 * Formulaire d'analyse de conjunction.
 * Deux modes :
 *  - "catalogue" : saisie des noms, résolution côté serveur (analyze-by-name)
 *  - "manuel"    : saisie complète des TLEs bruts (analyze)
 */
@Component({
  selector: 'app-conjunction-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './conjunction-form.component.html',
  styleUrl: './conjunction-form.component.scss'
})
export class ConjunctionFormComponent implements OnInit {

  @Output() reportReady = new EventEmitter<ConjunctionReport>();
  @Output() loading     = new EventEmitter<boolean>();
  @Output() errorMsg    = new EventEmitter<string | null>();

  mode: 'catalogue' | 'manuel' = 'catalogue';
  form!: FormGroup;
  showAdvanced = false;
  satelliteNames: string[] = [];

  private readonly fb                 = inject(FormBuilder);
  private readonly conjunctionService = inject(ConjunctionService);
  private readonly orbitService       = inject(OrbitService);
  private readonly route              = inject(ActivatedRoute);
  private readonly cdr                = inject(ChangeDetectorRef);

  ngOnInit(): void {
    this.buildForm();
    this.loadNames();
    // Pré-remplissage depuis les query params (clic depuis le panel d'alertes)
    this.route.queryParams.subscribe(params => {
      if (params['sat1']) this.form.patchValue({ nameSat1: params['sat1'] });
      if (params['sat2']) this.form.patchValue({ nameSat2: params['sat2'] });
      if (params['sat1'] && params['sat2']) this.submit();
    });
  }

  switchMode(m: 'catalogue' | 'manuel'): void {
    this.mode = m;
    this.buildForm();
  }

  submit(): void {
    if (this.form.invalid) return;
    this.loading.emit(true);
    this.errorMsg.emit(null);

    const v = this.form.value;

    const obs$ = this.mode === 'catalogue'
      ? this.conjunctionService.analyzeByName({
          nameSat1: v.nameSat1, nameSat2: v.nameSat2,
          durationHours: v.durationHours, stepSeconds: v.stepSeconds, thresholdKm: v.thresholdKm
        })
      : this.conjunctionService.analyze({
          nameSat1: v.nameSat1, tle1Sat1: v.tle1Sat1, tle2Sat1: v.tle2Sat1,
          nameSat2: v.nameSat2, tle1Sat2: v.tle1Sat2, tle2Sat2: v.tle2Sat2,
          durationHours: v.durationHours, stepSeconds: v.stepSeconds, thresholdKm: v.thresholdKm
        });

    obs$.subscribe({
      next: report => {
        this.loading.emit(false);
        this.reportReady.emit(report);
      },
      error: err => {
        this.loading.emit(false);
        this.errorMsg.emit(err?.error?.message ?? 'Erreur lors de l\'analyse.');
      }
    });
  }

  private buildForm(): void {
    if (this.mode === 'catalogue') {
      this.form = this.fb.group({
        nameSat1:      ['', Validators.required],
        nameSat2:      ['', Validators.required],
        durationHours: [24,  [Validators.required, Validators.min(1), Validators.max(72)]],
        stepSeconds:   [60,  [Validators.required, Validators.min(10), Validators.max(300)]],
        thresholdKm:   [5.0, [Validators.required, Validators.min(0.1), Validators.max(200)]]
      });
    } else {
      this.form = this.fb.group({
        nameSat1:      ['', Validators.required],
        tle1Sat1:      ['', Validators.required],
        tle2Sat1:      ['', Validators.required],
        nameSat2:      ['', Validators.required],
        tle1Sat2:      ['', Validators.required],
        tle2Sat2:      ['', Validators.required],
        durationHours: [24,  [Validators.required, Validators.min(1), Validators.max(72)]],
        stepSeconds:   [60,  [Validators.required, Validators.min(10), Validators.max(300)]],
        thresholdKm:   [5.0, [Validators.required, Validators.min(0.1), Validators.max(200)]]
      });
    }
  }


  private loadNames(): void {
    this.orbitService.getSatelliteNames().subscribe({
      next: names => {
        this.satelliteNames = names;
        this.cdr.markForCheck();
      },
      error: () => {
        this.satelliteNames = [];
        this.cdr.markForCheck();
      }
    });
  }
}




