package com.controlefin.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.controlefin.audit.AuditService;
import com.controlefin.common.ApiException;
import com.controlefin.common.CurrentUser;
import com.controlefin.common.Money;
import com.controlefin.finance.AttachmentService.AttachmentInfo;
import com.controlefin.finance.FinanceDtos.ReverseRequest;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;
import com.controlefin.finance.ImportDtos.BatchResponse;
import com.controlefin.finance.ImportDtos.ImportAttachment;
import com.controlefin.finance.ImportDtos.ImportItem;
import com.controlefin.finance.ImportDtos.ImportMode;
import com.controlefin.finance.ImportDtos.ImportRequest;
import com.controlefin.finance.ImportDtos.ItemResult;
import com.controlefin.finance.ImportDtos.ItemStatus;

/**
 * Importação de lotes enviados pelo Claude (API externa).
 *
 * <ul>
 *   <li>Cada item é gravado na sua própria transação: um item com erro não derruba os outros.</li>
 *   <li>Antes de criar, procura lançamento igual (mesmo tipo e valor, até 3 dias, mesma conta/cartão). Se achar, o item
 *       fica DUPLICADO e nada é gravado, a menos que venha allowDuplicate=true.</li>
 *   <li>idempotencyKey: o mesmo lote reenviado (ex.: queda de conexão) devolve o resultado original.</li>
 *   <li>Anexos: sempre guardados (criptografados) e ligados ao lote e aos lançamentos.</li>
 *   <li>REMOVER nunca apaga: cancela ou estorna. Desfazer um lote cancela o que ele criou.</li>
 * </ul>
 */
@Service
public class ImportService {

    static final int DUPLICATE_DAYS = 3;

    private final JdbcClient jdbc;
    private final TransactionService transactionService;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final AttachmentService attachments;
    private final AuditService audit;
    private final TransactionTemplate newTx;

    public ImportService(JdbcClient jdbc, TransactionService transactionService, TransactionRepository transactions,
                         CategoryRepository categories, AttachmentService attachments, AuditService audit,
                         PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.transactionService = transactionService;
        this.transactions = transactions;
        this.categories = categories;
        this.attachments = attachments;
        this.audit = audit;
        this.newTx = new TransactionTemplate(tm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- importar

    public BatchResponse importBatch(UUID userId, ImportRequest req) {
        ImportMode mode = req.mode() == null ? ImportMode.CONFIRMADO : req.mode();
        String key = Money.trimToNull(req.idempotencyKey());
        if (key != null) {
            UUID existing = findByKey(userId, key);
            if (existing != null) return batch(userId, existing, true);
        }

        // Anexos são conferidos antes de qualquer gravação (arquivo inválido = lote recusado inteiro).
        List<byte[]> files = new ArrayList<>();
        List<ImportAttachment> sent = req.attachments() == null ? List.of() : req.attachments();
        long total = 0;
        for (ImportAttachment a : sent) {
            byte[] content;
            try {
                content = Base64.getMimeDecoder().decode(a.contentBase64());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Anexo \"" + a.name() + "\" não está em base64 válido.");
            }
            total += content.length;
            files.add(content);
        }
        if (total > 20L * 1024 * 1024) throw ApiException.badRequest("Os anexos do lote passam de 20 MB.");

        UUID batchId;
        List<AttachmentInfo> stored = new ArrayList<>();
        try {
            batchId = newTx.execute(st -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("insert into import_batches (id, user_id, token_id, mode, idempotency_key, note) "
                                + "values (?, ?, ?, ?, ?, ?)")
                        .params(id, userId, CurrentUser.tokenId(), mode.name(), key, Money.trimToNull(req.note())).update();
                for (int i = 0; i < files.size(); i++) {
                    AttachmentInfo info = attachments.store(userId, sent.get(i).name(), files.get(i));
                    attachments.linkToBatch(userId, id, info.id(), i);
                    stored.add(info);
                }
                return id;
            });
        } catch (DataIntegrityViolationException e) {
            // Mesmo idempotencyKey chegando ao mesmo tempo: devolve o lote que ganhou.
            UUID existing = key == null ? null : findByKey(userId, key);
            if (existing != null) return batch(userId, existing, true);
            throw e;
        }

        List<ImportItem> items = req.items();
        for (int i = 0; i < items.size(); i++) {
            ImportItem item = items.get(i);
            int pos = i;
            ItemResult result;
            try {
                result = newTx.execute(st -> {
                    ItemResult r = mode == ImportMode.REMOVER
                            ? remove(userId, pos, item, stored)
                            : create(userId, batchId, pos, item, mode, stored);
                    saveItem(userId, batchId, r);
                    return r;
                });
            } catch (ApiException e) {
                result = error(pos, item, e.getMessage());
                saveItemInNewTx(userId, batchId, result);
            } catch (DataIntegrityViolationException e) {
                result = error(pos, item, "Dados inconsistentes ou já usados (ex.: ocorrência de recorrência já lançada).");
                saveItemInNewTx(userId, batchId, result);
            }
        }

        BatchResponse response = batch(userId, batchId, false);
        newTx.executeWithoutResult(st -> audit.log(userId, "IMPORTACAO", batchId, mode + ": " + response.created()
                + " criado(s), " + response.pending() + " pendente(s), " + response.duplicates() + " duplicado(s), "
                + response.removed() + " removido(s), " + response.errors() + " erro(s)"
                + (stored.isEmpty() ? "" : ", " + stored.size() + " anexo(s)")));
        return response;
    }

    private ItemResult create(UUID userId, UUID batchId, int pos, ImportItem item, ImportMode mode,
                              List<AttachmentInfo> stored) {
        TransactionKind kind = item.kind() == null ? TransactionKind.DESPESA : item.kind();
        if (item.date() == null) throw ApiException.badRequest("Informe a data.");
        String desc = Money.trimToNull(item.description());
        if (desc == null) throw ApiException.badRequest("Informe a descrição.");
        if (item.transactionId() != null) {
            throw ApiException.badRequest("transactionId só é usado no modo REMOVER (para editar, use PUT /transactions/{id}).");
        }

        String warning = null;
        UUID categoryId = item.categoryId();
        if (categoryId == null && Money.trimToNull(item.categoryName()) != null) {
            categoryId = categoryByName(userId, kind, item.categoryName());
            if (categoryId == null) warning = "Categoria \"" + item.categoryName().trim() + "\" não encontrada: lançado sem categoria.";
        }

        TransactionRequest req = new TransactionRequest(kind,
                mode == ImportMode.ANALISAR ? TransactionStatus.PENDENTE : TransactionStatus.CONFIRMADO,
                item.date(), desc, item.amount(), item.walletId(), item.targetWalletId(), categoryId,
                item.notes(), item.cardId(), item.credit(), item.installments(), item.consumesLimit(),
                item.invoiceId(), item.originalCurrency(), item.originalAmount(), item.exchangeRate(), item.iof(),
                item.recurrenceId(), item.recurrenceDate());

        BigDecimal amount = brl(item);
        if (!Boolean.TRUE.equals(item.allowDuplicate()) && amount != null && kind != TransactionKind.AJUSTE) {
            List<TransactionResponse> dups = duplicates(userId, kind, item, amount);
            if (!dups.isEmpty()) {
                TransactionResponse d = dups.get(0);
                return new ItemResult(pos, ItemStatus.DUPLICADO, d.id(), item.date(), desc, amount,
                        "Já existe \"" + d.description() + "\" em " + d.date() + " com o mesmo valor"
                                + (dups.size() > 1 ? " (e mais " + (dups.size() - 1) + ")" : "")
                                + ". Nada foi gravado; reenvie com allowDuplicate=true se for outra compra.");
            }
        }

        TransactionResponse created = transactionService.create(userId, req, Origin.API);
        Transaction t = transactionService.transaction(userId, created.id());
        for (Transaction row : transactionService.rows(userId, t)) row.setImportBatchId(batchId);
        for (AttachmentInfo a : chosen(item, stored)) attachments.link(userId, created.id(), a.id());
        ItemStatus status = mode == ImportMode.ANALISAR ? ItemStatus.PENDENTE : ItemStatus.CRIADO;
        String msg = created.installmentCount() == null ? warning
                : join(warning, created.installmentCount() + " parcelas criadas.");
        return new ItemResult(pos, status, created.id(), item.date(), desc, created.amount(), msg);
    }

    private ItemResult remove(UUID userId, int pos, ImportItem item, List<AttachmentInfo> stored) {
        Transaction target = findTarget(userId, item);
        if (target.getStatus() == TransactionStatus.CANCELADO) {
            throw ApiException.badRequest("O lançamento \"" + target.getDescription() + "\" já está cancelado.");
        }
        for (AttachmentInfo a : chosen(item, stored)) attachments.link(userId, target.getId(), a.id());
        if (target.getKind() == TransactionKind.DESPESA) {
            TransactionResponse r = transactionService.reverse(userId, target.getId(),
                    new ReverseRequest(item.date() != null && item.date().isAfter(target.getDate()) ? item.date() : null));
            boolean estorno = r.kind() == TransactionKind.ESTORNO;
            if (estorno) for (AttachmentInfo a : chosen(item, stored)) attachments.link(userId, r.id(), a.id());
            return new ItemResult(pos, estorno ? ItemStatus.ESTORNADO : ItemStatus.REMOVIDO, r.id(), target.getDate(),
                    target.getDescription(), estorno ? r.amount() : target.getAmount(),
                    estorno ? "Já cobrado: estorno de " + r.amount() + " lançado." : "Compra cancelada (ainda não tinha sido cobrada).");
        }
        transactionService.setStatus(userId, target.getId(), TransactionStatus.CANCELADO);
        return new ItemResult(pos, ItemStatus.REMOVIDO, target.getId(), target.getDate(), target.getDescription(),
                target.getAmount(), "Lançamento cancelado.");
    }

    /** Compra a remover: pelo id ou por data ± 3 dias + valor (+ cartão/carteira/descrição). Precisa ser única. */
    private Transaction findTarget(UUID userId, ImportItem item) {
        if (item.transactionId() != null) return transactionService.transaction(userId, item.transactionId());
        if (item.date() == null || item.amount() == null) {
            throw ApiException.badRequest("Para remover, informe transactionId ou data e valor da compra.");
        }
        TransactionKind kind = item.kind() == null ? TransactionKind.DESPESA : item.kind();
        BigDecimal amount = Money.of(item.amount());
        List<Transaction> found = transactions.sameKindBetween(userId, kind, item.date().minusDays(DUPLICATE_DAYS),
                        item.date().plusDays(DUPLICATE_DAYS)).stream()
                .filter(t -> t.getAmount().compareTo(amount) == 0)
                .filter(t -> item.cardId() == null || item.cardId().equals(t.getCardId()))
                .filter(t -> item.walletId() == null || item.walletId().equals(t.getWalletId()))
                .toList();
        String desc = Money.trimToNull(item.description());
        if (found.size() > 1 && desc != null) {
            List<Transaction> similar = found.stream().filter(t -> RecurrenceService.similar(desc, t.getDescription())).toList();
            if (!similar.isEmpty()) found = similar;
        }
        if (found.isEmpty()) throw ApiException.badRequest("Compra não encontrada (data ± 3 dias e valor " + amount + ").");
        if (found.size() > 1) {
            throw ApiException.badRequest("Mais de uma compra encontrada; informe transactionId: "
                    + found.stream().map(t -> t.getId() + " (" + t.getDate() + " " + t.getDescription() + ")")
                    .collect(Collectors.joining(", ")));
        }
        return found.get(0);
    }

    private List<TransactionResponse> duplicates(UUID userId, TransactionKind kind, ImportItem item, BigDecimal amount) {
        UUID wallet = item.cardId() == null ? item.walletId() : null;
        List<TransactionResponse> list = new ArrayList<>(
                transactionService.similar(userId, kind, item.date(), amount, wallet, item.cardId(), null));
        int n = item.installments() == null ? 1 : item.installments();
        if (n > 1) {
            // Compra parcelada já lançada: a 1ª parcela tem o valor total dividido (+ centavos que sobram).
            BigDecimal part = amount.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
            BigDecimal first = amount.subtract(part.multiply(BigDecimal.valueOf(n - 1)));
            list.addAll(transactionService.similar(userId, kind, item.date(), first, wallet, item.cardId(), null));
        }
        return list;
    }

    /** Valor em reais do item (informado ou calculado da moeda estrangeira). */
    static BigDecimal brl(ImportItem item) {
        if (item.amount() != null) return Money.of(item.amount());
        if (item.originalAmount() != null && item.exchangeRate() != null) {
            return Money.of(item.originalAmount().multiply(item.exchangeRate()))
                    .add(item.iof() == null ? BigDecimal.ZERO : Money.of(item.iof()));
        }
        return null;
    }

    private UUID categoryByName(UUID userId, TransactionKind kind, String name) {
        CategoryKind ck = kind == TransactionKind.RECEITA ? CategoryKind.RECEITA : CategoryKind.DESPESA;
        List<Category> all = categories.findByUserIdOrderByNameAsc(userId).stream()
                .filter(c -> c.getKind() == ck && !c.isArchived()).toList();
        Map<UUID, Category> byId = all.stream().collect(Collectors.toMap(Category::getId, c -> c));
        String wanted = norm(name);
        for (Category c : all) {
            Category parent = c.getParentId() == null ? null : byId.get(c.getParentId());
            String full = parent == null ? norm(c.getName()) : norm(parent.getName()) + ">" + norm(c.getName());
            if (wanted.equals(full)) return c.getId();
        }
        List<Category> byName = all.stream().filter(c -> norm(c.getName()).equals(wanted)).toList();
        return byName.size() == 1 ? byName.get(0).getId() : null;
    }

    /** Sem acentos, minúsculas, "›" ou "/" viram ">", sem espaços. */
    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return n.replace('›', '>').replace('/', '>').replaceAll("\\s+", "");
    }

    private static List<AttachmentInfo> chosen(ImportItem item, List<AttachmentInfo> stored) {
        if (item.attachments() == null) return stored;
        List<AttachmentInfo> out = new ArrayList<>();
        for (Integer i : item.attachments()) {
            if (i == null || i < 0 || i >= stored.size()) {
                throw ApiException.badRequest("Anexo " + i + " não existe no lote (use índices de 0 a " + (stored.size() - 1) + ").");
            }
            out.add(stored.get(i));
        }
        return out;
    }

    private static ItemResult error(int pos, ImportItem item, String message) {
        return new ItemResult(pos, ItemStatus.ERRO, null, item.date(), Money.trimToNull(item.description()),
                item.amount() == null ? null : Money.of(item.amount()), message);
    }

    private static String join(String a, String b) {
        return a == null ? b : a + " " + b;
    }

    private void saveItem(UUID userId, UUID batchId, ItemResult r) {
        String desc = r.description() == null ? null : r.description().length() > 200 ? r.description().substring(0, 200) : r.description();
        String msg = r.message() == null ? null : r.message().length() > 1000 ? r.message().substring(0, 1000) : r.message();
        jdbc.sql("insert into import_items (id, user_id, batch_id, position, status, transaction_id, date, description, "
                        + "amount, message) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), userId, batchId, r.position(), r.status().name(), r.transactionId(), r.date(),
                        desc, r.amount(), msg)
                .update();
    }

    private void saveItemInNewTx(UUID userId, UUID batchId, ItemResult r) {
        newTx.executeWithoutResult(st -> saveItem(userId, batchId, r));
    }

    // ---------------------------------------------------------------- revisão (site) e desfazer

    @Transactional(readOnly = true)
    public List<BatchResponse> list(UUID userId) {
        List<UUID> ids = jdbc.sql("select id from import_batches where user_id = ? order by created_at desc limit 50")
                .params(userId).query(UUID.class).list();
        return ids.stream().map(id -> summary(userId, id)).toList();
    }

    @Transactional(readOnly = true)
    public BatchResponse get(UUID userId, UUID id) {
        return batch(userId, id, false);
    }

    /** Confirma todos os lançamentos pendentes do lote (modo ANALISAR revisado no computador). */
    @Transactional
    public BatchResponse confirm(UUID userId, UUID id) {
        BatchRow b = row(userId, id);
        if ("DESFEITO".equals(b.status())) throw ApiException.badRequest("Esta importação foi desfeita.");
        int n = 0;
        for (ItemResult r : items(userId, id)) {
            if (r.status() == ItemStatus.PENDENTE && r.transactionId() != null) {
                Transaction t = transactions.findByIdAndUserId(r.transactionId(), userId).orElse(null);
                if (t != null && t.getStatus() == TransactionStatus.PENDENTE) {
                    transactionService.setStatus(userId, t.getId(), TransactionStatus.CONFIRMADO);
                    n++;
                }
                jdbc.sql("update import_items set status = 'CRIADO' where batch_id = ? and position = ?")
                        .params(id, r.position()).update();
            }
        }
        audit.log(userId, "IMPORTACAO_CONFIRMADA", id, n + " lançamento(s) confirmado(s)");
        return batch(userId, id, false);
    }

    /** Desfaz o lote: cancela tudo o que ele criou (nunca apaga). Remoções não são desfeitas automaticamente. */
    @Transactional
    public BatchResponse undo(UUID userId, UUID id) {
        BatchRow b = row(userId, id);
        if ("DESFEITO".equals(b.status())) throw ApiException.badRequest("Esta importação já foi desfeita.");
        if (b.mode() == ImportMode.REMOVER) {
            throw ApiException.badRequest("Uma remoção não é desfeita automaticamente: lance a compra de novo se precisar.");
        }
        int n = 0;
        for (ItemResult r : items(userId, id)) {
            if ((r.status() == ItemStatus.CRIADO || r.status() == ItemStatus.PENDENTE) && r.transactionId() != null) {
                Transaction t = transactions.findByIdAndUserId(r.transactionId(), userId).orElse(null);
                if (t != null && t.getStatus() != TransactionStatus.CANCELADO) {
                    transactionService.setStatus(userId, t.getId(), TransactionStatus.CANCELADO);
                    n++;
                }
                jdbc.sql("update import_items set status = 'DESFEITO' where batch_id = ? and position = ?")
                        .params(id, r.position()).update();
            }
        }
        jdbc.sql("update import_batches set status = 'DESFEITO', undone_at = now() where id = ? and user_id = ?")
                .params(id, userId).update();
        audit.log(userId, "IMPORTACAO_DESFEITA", id, n + " lançamento(s) cancelado(s)");
        return batch(userId, id, false);
    }

    // ---------------------------------------------------------------- leitura

    record BatchRow(UUID id, ImportMode mode, String note, String status, Instant createdAt, Instant undoneAt,
                    String tokenName) {
    }

    private UUID findByKey(UUID userId, String key) {
        return newTx.execute(st -> jdbc.sql("select id from import_batches where user_id = ? and idempotency_key = ?")
                .params(userId, key).query(UUID.class).optional().orElse(null));
    }

    private BatchRow row(UUID userId, UUID id) {
        return jdbc.sql("select b.id, b.mode, b.note, b.status, b.created_at, b.undone_at, t.name as token_name "
                        + "from import_batches b left join api_tokens t on t.id = b.token_id where b.user_id = ? and b.id = ?")
                .params(userId, id)
                .query((rs, i) -> new BatchRow(rs.getObject("id", UUID.class), ImportMode.valueOf(rs.getString("mode")),
                        rs.getString("note"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("undone_at") == null ? null : rs.getTimestamp("undone_at").toInstant(),
                        rs.getString("token_name")))
                .optional().orElseThrow(() -> ApiException.notFound("Importação não encontrada."));
    }

    private List<ItemResult> items(UUID userId, UUID batchId) {
        return jdbc.sql("select position, status, transaction_id, date, description, amount, message from import_items "
                        + "where user_id = ? and batch_id = ? order by position")
                .params(userId, batchId)
                .query((rs, i) -> new ItemResult(rs.getInt("position"), ItemStatus.valueOf(rs.getString("status")),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getDate("date") == null ? null : rs.getDate("date").toLocalDate(), rs.getString("description"),
                        rs.getBigDecimal("amount"), rs.getString("message")))
                .list();
    }

    /** Lê o lote na transação atual (ou numa nova, quando chamado fora de transação, como na importação). */
    private BatchResponse batch(UUID userId, UUID id, boolean replayed) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            return load(userId, id, replayed);
        }
        return newTx.execute(st -> load(userId, id, replayed));
    }

    private BatchResponse load(UUID userId, UUID id, boolean replayed) {
        BatchRow b = row(userId, id);
        List<ItemResult> items = new ArrayList<>(items(userId, id));
        items.sort(Comparator.comparingInt(ItemResult::position));
        return build(b, items, attachments.forBatch(userId, id), replayed);
    }

    private BatchResponse summary(UUID userId, UUID id) {
        BatchRow b = row(userId, id);
        return build(b, items(userId, id), attachments.forBatch(userId, id), false);
    }

    private static BatchResponse build(BatchRow b, List<ItemResult> items, List<AttachmentInfo> files, boolean replayed) {
        Map<ItemStatus, Long> c = items.stream().collect(Collectors.groupingBy(ItemResult::status, Collectors.counting()));
        int created = c.getOrDefault(ItemStatus.CRIADO, 0L).intValue();
        int pending = c.getOrDefault(ItemStatus.PENDENTE, 0L).intValue();
        int dup = c.getOrDefault(ItemStatus.DUPLICADO, 0L).intValue();
        int err = c.getOrDefault(ItemStatus.ERRO, 0L).intValue();
        int removed = c.getOrDefault(ItemStatus.REMOVIDO, 0L).intValue() + c.getOrDefault(ItemStatus.ESTORNADO, 0L).intValue();
        return new BatchResponse(b.id(), b.mode(), b.note(), b.status(), b.createdAt(), b.undoneAt(), b.tokenName(),
                replayed, created, pending, dup, err, removed, items, files);
    }
}
