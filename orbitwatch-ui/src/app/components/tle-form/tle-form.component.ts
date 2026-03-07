import { Component, Output, EventEmitter, OnInit } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { GroundTrackParams } from '../../services/orbit.service';

// TLE ISS pré-rempli pour faciliter les tests
const ISS_TLE1 = '1 25544U 98067A   26066.50000000  .00020000  00000+0  35000-3 0  9990';
const ISS_TLE2 = '2 25544  51.6400 200.0000 0003000  60.0000 300.1476 15.49560000999999';

function tleLineValidator(lineNumber: 1 | 2) {
  return (control: AbstractControl): ValidationErrors | null => {
    const val: string = (control.value ?? '').trim();
    if (!val) return null; // géré par Validators.required
    return val.startsWith(`${lineNumber} `) ? null : { tleFormat: true };
  };
}

@Component({
  selector: 'app-tle-form',
  standalone: true,
  imports: [ReactiveFormsModule, CommonModule],
  templateUrl: './tle-form.component.html',
  styleUrl: './tle-form.component.scss'
})
export class TleFormComponent implements OnInit {
  @Output() propagate = new EventEmitter<GroundTrackParams>();

  form!: FormGroup;
  loading = false;

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      name:     [ISS_TLE1 ? 'ISS' : ''],
      tle1:     [ISS_TLE1, [Validators.required, tleLineValidator(1)]],
      tle2:     [ISS_TLE2, [Validators.required, tleLineValidator(2)]],
      duration: [90,  [Validators.required, Validators.min(1), Validators.max(1440)]],
      step:     [60,  [Validators.required, Validators.min(10), Validators.max(300)]]
    });
  }

  get f() { return this.form.controls; }

  onSubmit(): void {
    if (this.form.invalid) return;
    const { name, tle1, tle2, duration, step } = this.form.value;
    this.propagate.emit({ name, tle1, tle2, duration, step });
  }

  setLoading(v: boolean): void {
    this.loading = v;
    v ? this.form.disable() : this.form.enable();
  }
}

