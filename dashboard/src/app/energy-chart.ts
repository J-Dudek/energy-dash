import type { ElementRef, OnDestroy } from '@angular/core';
import { Component, effect, input, viewChild } from '@angular/core';
import type { ChartDataset } from 'chart.js';
import {
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
} from 'chart.js';
import type { Energy } from './models';
import type { Point } from './stats';

Chart.register(
  BarController,
  BarElement,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Tooltip,
  Legend,
);

const nf = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });

/** Lit une variable CSS du thème (clair / sombre) au moment du rendu. */
function css(el: HTMLElement, name: string): string {
  return getComputedStyle(el).getPropertyValue(name).trim();
}

/**
 * Histogramme d'une énergie. Un seul axe Y, sauf pour le gaz quand le thermostat Netatmo fournit
 * le temps de chauffe : courbe sur un 2e axe (heures) à droite. La température du gaz et le détail
 * des périodes d'eau passent dans l'infobulle.
 */
@Component({
  selector: 'app-energy-chart',
  template: `<div class="chart-box">
    <canvas #canvas [attr.aria-label]="ariaLabel()" role="img"></canvas>
  </div>`,
  styles: `
    .chart-box {
      position: relative;
      height: 300px;
    }
  `,
})
export class EnergyChart implements OnDestroy {
  readonly energy = input.required<Energy>();
  readonly points = input.required<Point[]>();
  readonly ariaLabel = input('');

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart?: Chart;
  private readonly media = window.matchMedia('(prefers-color-scheme: dark)');
  private readonly onTheme = () => {
    this.render();
  };

  constructor() {
    effect(() => {
      this.render();
    });
    this.media.addEventListener('change', this.onTheme);
  }

  ngOnDestroy(): void {
    this.media.removeEventListener('change', this.onTheme);
    this.chart?.destroy();
  }

  private render(): void {
    const canvasEl = this.canvas().nativeElement;
    const host = canvasEl.parentElement;
    if (!host) return;
    const energy = this.energy();
    const pts = this.points();
    this.chart?.destroy();

    const surface = css(host, '--surface-1');
    const text2 = css(host, '--text-secondary');
    const grid = css(host, '--grid');
    const hasHpHc = energy === 'electricity' && pts.some((p) => p.hp !== null);
    const hasHeating = energy === 'gas' && pts.some((p) => p.heatingHours !== null);

    const bar = {
      borderRadius: { topLeft: 4, topRight: 4 },
      borderSkipped: 'bottom' as const,
      borderColor: surface,
      borderWidth: { top: 0, left: 1, right: 1, bottom: 0 }, // espace de 2px entre barres
      maxBarThickness: 28,
    };

    const datasets = hasHpHc
      ? [
          {
            label: 'Heures creuses',
            data: pts.map((p) => p.hc ?? 0),
            backgroundColor: css(host, '--series-2'),
            stack: 'e',
            ...bar,
            borderRadius: 0,
            borderWidth: { top: 2, left: 1, right: 1, bottom: 0 },
          },
          {
            label: 'Heures pleines',
            data: pts.map((p) => p.hp ?? 0),
            backgroundColor: css(host, '--series-1'),
            stack: 'e',
            ...bar,
          },
        ]
      : [
          {
            label: energy === 'water' ? 'Litres / jour' : 'kWh',
            data: pts.map((p) => p.value),
            backgroundColor: css(host, energy === 'gas' ? '--series-3' : '--series-1'),
            ...bar,
          },
        ];

    const heatColor = css(host, '--series-2');
    const partial = (i: number): boolean => {
      const p = pts[i];
      return p !== undefined && p.heatingDays < p.monthDays; // mois incomplet : point creux
    };
    const allDatasets: ChartDataset<'bar' | 'line'>[] = hasHeating
      ? [
          ...datasets,
          {
            type: 'line',
            label: 'Chauffe (h)',
            data: pts.map((p) => p.heatingHours),
            yAxisID: 'y2',
            borderColor: heatColor,
            backgroundColor: heatColor,
            borderWidth: 2,
            tension: 0.25,
            spanGaps: false,
            pointRadius: 4,
            pointHoverRadius: 5,
            pointBorderColor: heatColor,
            pointBackgroundColor: (ctx: { dataIndex: number }) =>
              partial(ctx.dataIndex) ? surface : heatColor,
            order: -1, // au-dessus des barres
          },
        ]
      : datasets;

    const unit = energy === 'water' ? 'L/j' : 'kWh';
    // Deux axes avec leurs unités mangent la moitié d'un écran de téléphone : la légende porte alors les unités.
    const compact = () => hasHeating && canvasEl.clientWidth < 480;
    this.chart = new Chart(canvasEl, {
      type: 'bar',
      data: { labels: pts.map((p) => p.label), datasets: allDatasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        scales: {
          x: {
            stacked: hasHpHc,
            grid: { display: false },
            ticks: { color: text2, maxRotation: 0, autoSkip: true },
          },
          y: {
            stacked: hasHpHc,
            beginAtZero: true,
            border: { display: false },
            grid: { color: grid },
            ticks: {
              color: text2,
              callback: (v) =>
                compact() ? nf.format(Number(v)) : `${nf.format(Number(v))} ${unit}`,
            },
          },
          ...(hasHeating
            ? {
                y2: {
                  position: 'right' as const,
                  beginAtZero: true,
                  border: { display: false },
                  grid: { display: false }, // une seule grille : celle des kWh
                  ticks: {
                    color: text2,
                    callback: (v: string | number) =>
                      compact() ? nf.format(Number(v)) : `${nf.format(Number(v))} h`,
                  },
                },
              }
            : {}),
        },
        plugins: {
          legend: {
            display: hasHpHc || hasHeating,
            position: 'top',
            align: 'start',
            reverse: hasHpHc,
            labels: {
              color: text2,
              boxWidth: 10,
              boxHeight: 10,
              useBorderRadius: true,
              borderRadius: 2,
            },
          },
          tooltip: {
            backgroundColor: css(host, '--surface-tooltip'),
            titleColor: css(host, '--text-primary'),
            bodyColor: css(host, '--text-primary'),
            footerColor: text2,
            borderColor: grid,
            borderWidth: 1,
            padding: 10,
            callbacks: {
              title: (items) => {
                const first = items[0];
                const p = first ? pts[first.dataIndex] : undefined;
                if (energy !== 'water' || !p) return first?.label ?? '';
                const start = new Date(p.date.getTime() - (p.days - 1) * 86_400_000);
                return `Du ${start.toLocaleDateString('fr-FR')} au ${p.date.toLocaleDateString('fr-FR')}`;
              },
              label: (item) =>
                item.dataset.yAxisID === 'y2'
                  ? ` ${item.dataset.label} : ${nf.format(Number(item.raw))} h`
                  : ` ${item.dataset.label} : ${nf.format(Number(item.raw))} ${unit}`,
              footer: (items) => {
                const first = items[0];
                const p = first ? pts[first.dataIndex] : undefined;
                const lines: string[] = [];
                if (!p) return lines;
                if (hasHpHc) lines.push(`Total : ${nf.format(p.raw)} kWh`);
                if (energy === 'water')
                  lines.push(`${nf.format(p.raw)} m³ facturés sur ${p.days} jours`);
                if (p.heatingHours !== null) {
                  if (p.heatingDays < p.monthDays) {
                    lines.push(`Chauffe partielle : ${p.heatingDays} jours sur ${p.monthDays}`);
                  } else if (p.kwhPerHeatingHour !== null) {
                    lines.push(`${nf.format(p.kwhPerHeatingHour)} kWh par heure de chauffe`);
                  }
                }
                if (p.temperature !== null)
                  lines.push(`Température moyenne : ${nf.format(p.temperature)} °C`);
                return lines;
              },
            },
          },
        },
      },
    });
  }
}
