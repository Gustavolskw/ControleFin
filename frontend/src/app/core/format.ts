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

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro',
  'outubro', 'novembro', 'dezembro'];

/** AAAA-MM para "novembro de 2026". */
@Pipe({ name: 'monthBr' })
export class MonthBrPipe implements PipeTransform {
  transform(ym: string | null | undefined): string {
    if (!ym) return '';
    const [y, m] = ym.split('-');
    return `${MONTHS[Number(m) - 1]} de ${y}`;
  }
}

/** Soma meses a um AAAA-MM. */
export function addMonths(ym: string, delta: number): string {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Primeiro e último dia de um AAAA-MM. */
export function monthRange(ym: string): { from: string; to: string } {
  const [y, m] = ym.split('-').map(Number);
  return { from: isoDate(new Date(y, m - 1, 1)), to: isoDate(new Date(y, m, 0)) };
}

/** Data local no formato AAAA-MM-DD (sem conversão para UTC). */
export function isoDate(d: Date = new Date()): string {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

export function currentMonth(): string {
  return isoDate().slice(0, 7);
}

export const FORMAT_PIPES = [BrlPipe, DateBrPipe, DateTimeBrPipe, MonthBrPipe];
