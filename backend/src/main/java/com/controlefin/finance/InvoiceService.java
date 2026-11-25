package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.InvoiceDetail;
import com.controlefin.finance.FinanceDtos.InvoiceSummary;
import com.controlefin.finance.FinanceEnums.InvoiceStatus;

/**
 * Faturas de cartão de crédito.
 *
 * <p>Calendário: a fatura é identificada pelo mês do vencimento. Uma compra feita <b>no dia do fechamento ou depois</b>
 * entra na fatura seguinte (como nos bancos: o dia do fechamento é o "melhor dia de compra"). Dias inexistentes no
 * mês (ex.: 31 em fevereiro) viram o último dia do mês.
 *
 * <p>Limite: cada fatura ocupa max(0, a pagar − compras que não reservam limite). Pagamentos liberam limite.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final CreditLineRepository creditLines;

    public InvoiceService(InvoiceRepository invoices, CreditLineRepository creditLines) {
        this.invoices = invoices;
        this.creditLines = creditLines;
    }

    // ---------------------------------------------------------------- calendário (regras puras)

    static LocalDate dayIn(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /** Mês em que fecha a fatura que vence em {@code ref}. */
    static YearMonth closingMonth(YearMonth ref, int closingDay, int dueDay) {
        return dueDay > closingDay ? ref : ref.minusMonths(1);
    }

    /** Fechamento sempre antes do vencimento (ex.: fecha 30 e vence 31 → em fevereiro fecha dia 27). */
    static LocalDate closingDate(YearMonth ref, int closingDay, int dueDay) {
        LocalDate closing = dayIn(closingMonth(ref, closingDay, dueDay), closingDay);
        LocalDate due = dueDate(ref, dueDay);
        return closing.isBefore(due) ? closing : due.minusDays(1);
    }

    static LocalDate dueDate(YearMonth ref, int dueDay) {
        return dayIn(ref, dueDay);
    }

    /** Mês de vencimento da fatura em que cai uma compra feita em {@code date}. */
    static YearMonth referenceFor(LocalDate date, int closingDay, int dueDay) {
        YearMonth closing = YearMonth.from(date);
        if (!date.isBefore(dayIn(closing, closingDay))) {
            closing = closing.plusMonths(1);
        }
        return dueDay > closingDay ? closing : closing.plusMonths(1);
    }

    // ---------------------------------------------------------------- obter / criar

    /** Fatura do mês {@code ref} (cria se ainda não existe). */
    Invoice invoiceAt(CreditLine line, YearMonth ref) {
        LocalDate refDate = ref.atDay(1);
        return invoices.findByCreditLineIdAndReferenceMonth(line.getId(), refDate).orElseGet(() -> {
            invoices.createIfMissing(line.getUserId(), line.getId(), refDate,
                    closingDate(ref, line.getClosingDay(), line.getDueDay()), dueDate(ref, line.getDueDay()));
            return invoices.findByCreditLineIdAndReferenceMonth(line.getId(), refDate).orElseThrow();
        });
    }

    /**
     * Fatura em que cai uma compra na data. Respeita as datas já gravadas nas faturas vizinhas (que podem ter sido
     * criadas antes de o fechamento do cartão mudar).
     */
    Invoice invoiceFor(CreditLine line, LocalDate date) {
        YearMonth ref = referenceFor(date, line.getClosingDay(), line.getDueDay());
        var previous = invoices.findByCreditLineIdAndReferenceMonth(line.getId(), ref.minusMonths(1).atDay(1));
        if (previous.isPresent() && date.isBefore(previous.get().getClosingDate())) {
            return previous.get();
        }
        Invoice inv = invoiceAt(line, ref);
        return date.isBefore(inv.getClosingDate()) ? inv : invoiceAt(line, ref.plusMonths(1));
    }

    Invoice next(CreditLine line, Invoice invoice) {
        return invoiceAt(line, invoice.getReference().plusMonths(1));
    }

    Invoice invoice(UUID userId, UUID id) {
        return invoices.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Fatura não encontrada."));
    }

    /** Ao mudar fechamento/vencimento do limite, as faturas ainda abertas ou futuras acompanham. */
    void recalculateOpenDates(CreditLine line, LocalDate today) {
        for (Invoice inv : invoices.findByUserIdAndCreditLineIdOrderByReferenceMonthAsc(line.getUserId(), line.getId())) {
            if (inv.getClosingDate().isAfter(today)) {
                LocalDate closing = closingDate(inv.getReference(), line.getClosingDay(), line.getDueDay());
                LocalDate due = dueDate(inv.getReference(), line.getDueDay());
                if (closing.isAfter(today) && closing.isBefore(due)) {
                    inv.setClosingDate(closing);
                    inv.setDueDate(due);
                }
            }
        }
    }

    // ---------------------------------------------------------------- totais e situação

    record Totals(BigDecimal total, BigDecimal nonConsuming, BigDecimal paid) {
        static final Totals EMPTY = new Totals(Money.nz(null), Money.nz(null), Money.nz(null));

        BigDecimal outstanding() {
            return total.subtract(paid);
        }

        /** Parte do limite ocupada por esta fatura. */
        BigDecimal used() {
            return outstanding().subtract(nonConsuming).max(BigDecimal.ZERO);
        }
    }

    Map<UUID, Totals> totals(UUID userId) {
        Map<UUID, Totals> map = new HashMap<>();
        for (var row : invoices.totals(userId)) {
            map.put(row.getInvoiceId(), new Totals(Money.nz(row.getTotal()), Money.nz(row.getNonConsuming()),
                    Money.nz(row.getPaid())));
        }
        return map;
    }

    /**
     * Situação: a primeira fatura que ainda não fechou é a ABERTA; as seguintes são FUTURAS. Fechada: PAGA se nada
     * falta pagar; VENCIDA depois do vencimento; senão FECHADA.
     */
    static InvoiceStatus status(Invoice inv, Totals t, LocalDate today, UUID currentOpenId) {
        if (inv.getClosingDate().isAfter(today)) {
            return inv.getId().equals(currentOpenId) ? InvoiceStatus.ABERTA : InvoiceStatus.FUTURA;
        }
        if (t.outstanding().signum() <= 0) return InvoiceStatus.PAGA;
        return today.isAfter(inv.getDueDate()) ? InvoiceStatus.VENCIDA : InvoiceStatus.FECHADA;
    }

    /**
     * Resumo de todas as faturas de um limite, em ordem de mês. Saldo credor (pago a mais ou estorno maior que a
     * fatura) passa para a fatura seguinte, como nos bancos: ela mostra o crédito em {@code carriedCredit} e o
     * valor a pagar já descontado.
     */
    static List<InvoiceSummary> summaries(CreditLine line, List<Invoice> list, Map<UUID, Totals> totals,
                                          LocalDate today) {
        List<Invoice> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(Invoice::getReferenceMonth));
        UUID currentOpen = sorted.stream().filter(i -> i.getClosingDate().isAfter(today))
                .map(Invoice::getId).findFirst().orElse(null);
        List<InvoiceSummary> out = new ArrayList<>();
        BigDecimal carry = Money.nz(null);
        for (Invoice i : sorted) {
            Totals raw = totals.getOrDefault(i.getId(), Totals.EMPTY);
            // O crédito trazido abate a fatura como se fosse um pagamento.
            Totals t = new Totals(raw.total(), raw.nonConsuming(), raw.paid().add(carry));
            out.add(new InvoiceSummary(i.getId(), line.getId(), line.getName(), i.getReference().toString(),
                    i.getClosingDate(), i.getDueDate(), status(i, t, today, currentOpen), raw.total(), raw.paid(),
                    t.outstanding(), raw.nonConsuming(), carry));
            carry = t.outstanding().signum() < 0 ? t.outstanding().negate() : Money.nz(null);
        }
        return out;
    }

    /**
     * Parte do limite ocupada pelas faturas (em ordem de mês). Compras que não reservam limite ficam fora do
     * cálculo por inteiro: nem ocupam limite nem "gastam" o crédito que passa de uma fatura para a outra.
     * Pagamentos e estornos abatem primeiro a parte que ocupa limite.
     */
    static BigDecimal used(List<InvoiceSummary> summaries) {
        BigDecimal used = Money.nz(null);
        BigDecimal carry = Money.nz(null);
        for (InvoiceSummary s : summaries) {
            BigDecimal consuming = s.total().subtract(s.nonConsuming()).subtract(s.paid()).subtract(carry);
            if (consuming.signum() < 0) {
                carry = consuming.negate();
            } else {
                used = used.add(consuming);
                carry = Money.nz(null);
            }
        }
        return used;
    }

    // ---------------------------------------------------------------- consultas do site

    /** Faturas de um limite (garante que a fatura aberta de hoje exista). */
    @Transactional
    public List<InvoiceSummary> list(UUID userId, UUID creditLineId) {
        CreditLine line = creditLines.findByIdAndUserId(creditLineId, userId)
                .orElseThrow(() -> ApiException.notFound("Cartão de crédito não encontrado."));
        LocalDate today = Money.today();
        invoiceFor(line, today);
        return summaries(line, invoices.findByUserIdAndCreditLineIdOrderByReferenceMonthAsc(userId, creditLineId),
                totals(userId), today);
    }

    InvoiceSummary summary(UUID userId, Invoice inv) {
        CreditLine line = creditLines.findByIdAndUserId(inv.getCreditLineId(), userId).orElseThrow();
        return summaries(line, invoices.findByUserIdAndCreditLineIdOrderByReferenceMonthAsc(userId, line.getId()),
                totals(userId), Money.today()).stream()
                .filter(s -> s.id().equals(inv.getId())).findFirst().orElseThrow();
    }

    /** Detalhe montado pelo TransactionService (que conhece os nomes dos lançamentos). */
    InvoiceDetail detail(UUID userId, Invoice inv, List<FinanceDtos.TransactionResponse> items) {
        return new InvoiceDetail(summary(userId, inv), items);
    }
}
