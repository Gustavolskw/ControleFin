import { Pipe, PipeTransform } from '@angular/core';

const moneyFmt = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dateTimeFmt = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });

@Pipe({ name: 'brl' })
export class BrlPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return moneyFmt.format(value ?? 0);
  }
}

/** Data AAAA-MM-DD (sem fuso) para DD/MM/AAAA. */
@Pipe({ name: 'dateBr' })
export class DateBrPipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    if (!iso) return '—';
    const [y, m, d] = iso.slice(0, 10).split('-');
    return `${d}/${m}/${y}`;
  }
}

/** Instante ISO (com fuso) para data e hora locais. */
@Pipe({ name: 'dateTimeBr' })
export class DateTimeBrPipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    return iso ? dateTimeFmt.format(new Date(iso)) : '—';
  }
}

/** Data local no formato AAAA-MM-DD (sem conversão para UTC). */
export function isoDate(d: Date = new Date()): string {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

export const FORMAT_PIPES = [BrlPipe, DateBrPipe, DateTimeBrPipe];
