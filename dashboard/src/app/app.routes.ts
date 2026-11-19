import type { Routes } from '@angular/router';
import { Dashboard } from './dashboard';
import { ProfilePage } from './profile-page';

export const routes: Routes = [
  { path: '', component: Dashboard, pathMatch: 'full' },
  { path: 'profil', component: ProfilePage },
  { path: '**', redirectTo: '' },
];
