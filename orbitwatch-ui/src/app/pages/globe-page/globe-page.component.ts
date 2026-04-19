import { Component } from '@angular/core';
import { GlobeComponent } from '../../components/globe/globe.component';

@Component({
  selector: 'app-globe-page',
  standalone: true,
  imports: [GlobeComponent],
  template: `<app-globe></app-globe>`,
  styles: [`:host { display: flex; flex-direction: column; height: 100vh; }`]
})
export class GlobePageComponent {}

