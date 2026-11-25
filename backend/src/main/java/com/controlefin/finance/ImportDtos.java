package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.controlefin.finance.AttachmentService.AttachmentInfo;
import com.controlefin.finance.FinanceEnums.TransactionKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Contratos da importação pelo Claude (API externa) e da revisão pelo site. */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * ANALISAR: lançamentos entram PENDENTES (revisar depois no computador).
     * CONFIRMADO (padrão): entram confirmados e já contam no saldo/fatura.
     * REMOVER: desfaz compras (estorno/cancelamento): cancela o que ainda não aconteceu ou não fechou na fatura;
     *          o já cobrado vira ESTORNO. Nunca apaga.
     */
    public enum ImportMode { ANALISAR, CONFIRMADO, REMOVER }

    public enum ItemStatus { CRIADO, PENDENTE, DUPLICADO, ERRO, REMOVIDO, ESTORNADO, DESFEITO }

    /** Arquivo enviado junto (print, PDF da fatura, CSV, Excel): sempre guardado e ligado aos lançamentos. */
    public record ImportAttachment(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String mimeType,
            @NotBlank String contentBase64) {
    }

    /**
     * Item do lote. Para criar: os mesmos campos de um lançamento (kind padrão DESPESA); categoria pelo id ou pelo
     * nome (categoryName, ex.: "Alimentação › Mercado"). allowDuplicate=true grava mesmo se houver lançamento igual
     * (mesmo valor, até 3 dias, mesma conta/cartão). attachments = índices dos anexos do lote (padrão: todos).
     * Para REMOVER: transactionId, ou date + amount (+ cardId/walletId/description) para localizar a compra.
     */
    public record ImportItem(
            TransactionKind kind,
            LocalDate date,
            @Size(max = 200) String description,
            BigDecimal amount,
            UUID walletId,
            UUID targetWalletId,
            UUID categoryId,
            @Size(max = 130) String categoryName,
            @Size(max = 1000) String notes,
            UUID cardId,
            Boolean credit,
            Integer installments,
            Boolean consumesLimit,
            UUID invoiceId,
            String originalCurrency,
            BigDecimal originalAmount,
            BigDecimal exchangeRate,
            BigDecimal iof,
            UUID recurrenceId,
            LocalDate recurrenceDate,
            Boolean allowDuplicate,
            List<Integer> attachments,
            UUID transactionId) {
    }

    /** Arquivos enviados depois do lote (ex.: o print que o usuário escolheu pelo link de envio). */
    public record AttachmentsRequest(@NotEmpty @Size(max = 10) List<@Valid ImportAttachment> attachments) {
    }

    public record ImportRequest(
            ImportMode mode,
            @Size(max = 100) String idempotencyKey,
            @Size(max = 500) String note,
            @Size(max = 10) List<@Valid ImportAttachment> attachments,
            @NotEmpty @Size(max = 300) List<@Valid ImportItem> items) {
    }

    public record ItemResult(int position, ItemStatus status, UUID transactionId, LocalDate date, String description,
                             BigDecimal amount, String message) {
    }

    /** replayed = mesmo idempotencyKey já processado: devolve o resultado original sem lançar de novo. */
    public record BatchResponse(UUID id, ImportMode mode, String note, String status, Instant createdAt,
                                Instant undoneAt, String tokenName, boolean replayed, int created, int pending,
                                int duplicates, int errors, int removed, List<ItemResult> items,
                                List<AttachmentInfo> attachments) {
    }
}
