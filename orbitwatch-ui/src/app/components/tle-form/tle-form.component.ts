import { Component, Output, EventEmitter, OnInit, input, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { OrbitService, GroundTrackParams } from '../../services/orbit.service';

@Component({
  selector: 'app-tle-form',
  standalone: true,
  imports: [ReactiveFormsModule, CommonModule],
  templateUrl: './tle-form.component.html',
  styleUrl: './tle-form.component.scss'
})
export class TleFormComponent implements OnInit {
  @Output() propagate = new EventEmitter<GroundTrackParams>();
  loading = input(false);

  form!: FormGroup;
  satelliteNames = signal<string[]>([]);
  namesLoading   = signal(true);
  namesError     = signal<string | null>(null);

  constructor(private fb: FormBuilder, private orbitService: OrbitService) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      name:     ['', [Validators.required]],
      duration: [90, [Validators.required, Validators.min(1), Validators.max(1440)]],
      step:     [60, [Validators.required, Validators.min(10), Validators.max(300)]]
    });

    this.orbitService.getSatelliteNames().subscribe({
      next: (names) => {
        this.satelliteNames.set(names);
        // Pré-sélectionne le premier satellite disponible
        if (names.length > 0) {
          this.form.get('name')!.setValue(names[0]);
        }
        this.namesLoading.set(false);
      },
      error: (err) => {
        this.namesError.set('Impossible de charger la liste des satellites.');
        this.namesLoading.set(false);
        console.error('[TleForm] Erreur chargement noms satellites', err);
      }
    });
  }

  get f() { return this.form.controls; }

  onSubmit(): void {
    if (this.form.invalid) return;
    const { name, duration, step } = this.form.value;
    this.propagate.emit({ name, duration, step });
  }
}
