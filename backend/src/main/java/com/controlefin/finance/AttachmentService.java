package com.controlefin.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;

/**
 * Anexos (prints, PDFs, CSV, Excel, OFX): sempre guardados e ligados aos lançamentos por id. O mesmo arquivo
 * (SHA-256) do mesmo usuário é guardado uma vez só. O tipo é conferido pelo conteúdo, não só pela extensão.
 * Nunca apagados pelo site/API (o histórico fica).
 */
@Service
public class AttachmentService {

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final AttachmentStorage storage;
    private final TransactionRepository transactions;

    public AttachmentService(JdbcClient jdbc, AttachmentStorage storage, TransactionRepository transactions) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = transactions;
    }

    public record AttachmentInfo(UUID id, String name, String mimeType, long size, String sha256, Instant createdAt) {
    }

    public record Download(AttachmentInfo info, byte[] content) {
    }

    record Kind(String mime, Predicate<byte[]> valid) {
    }

    /** Tipos aceitos: extensão → tipo MIME + conferência do conteúdo. */
    static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("pdf", new Kind("application/pdf", b -> starts(b, "%PDF".getBytes(StandardCharsets.US_ASCII)))),
            Map.entry("png", new Kind("image/png", b -> starts(b, new byte[] {(byte) 0x89, 'P', 'N', 'G'}))),
            Map.entry("jpg", new Kind("image/jpeg", b -> starts(b, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))),
            Map.entry("jpeg", new Kind("image/jpeg", b -> starts(b, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))),
            Map.entry("webp", new Kind("image/webp", b -> starts(b, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && b.length > 12 && new String(b, 8, 4, StandardCharsets.US_ASCII).equals("WEBP"))),
            Map.entry("heic", new Kind("image/heic", b -> b.length > 12
                    && new String(b, 4, 4, StandardCharsets.US_ASCII).equals("ftyp"))),
            Map.entry("csv", new Kind("text/csv", AttachmentService::isText)),
            Map.entry("txt", new Kind("text/plain", AttachmentService::isText)),
            Map.entry("ofx", new Kind("application/x-ofx", AttachmentService::isText)),
            Map.entry("xlsx", new Kind("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    b -> starts(b, new byte[] {'P', 'K', 3, 4}))),
            Map.entry("xls", new Kind("application/vnd.ms-excel",
                    b -> starts(b, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}))));

    /** Guarda (ou reaproveita, se o mesmo arquivo já existe) e devolve o anexo. Chamar dentro de uma transação. */
    public AttachmentInfo store(UUID userId, String originalName, byte[] content) {
        if (content == null || content.length == 0) throw ApiException.badRequest("Anexo vazio.");
        if (content.length > MAX_BYTES) throw ApiException.badRequest("Anexo maior que 10 MB: " + originalName);
        String name = sanitize(originalName);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        Kind kind = KINDS.get(ext);
        if (kind == null) {
            throw ApiException.badRequest("Tipo de anexo não aceito (" + name + "). Use PDF, PNG, JPG, WEBP, HEIC, CSV, "
                    + "TXT, OFX, XLS ou XLSX.");
        }
        if (!kind.valid().test(content)) {
            throw ApiException.badRequest("O conteúdo de \"" + name + "\" não confere com a extensão ." + ext + ".");
        }
        String sha = sha256(content);
        var existing = jdbc.sql("select id, original_name, mime_type, size_bytes, sha256, created_at from attachments "
                        + "where user_id = ? and sha256 = ?")
                .params(userId, sha).query(AttachmentService::map).optional();
        if (existing.isPresent()) return existing.get();

        UUID id = UUID.randomUUID();
        jdbc.sql("insert into attachments (id, user_id, original_name, mime_type, size_bytes, sha256) values (?, ?, ?, ?, ?, ?)")
                .params(id, userId, name, kind.mime(), (long) content.length, sha).update();
        storage.write(userId, id, content);
        return new AttachmentInfo(id, name, kind.mime(), content.length, sha, Instant.now());
    }

    /** Liga um anexo a um lançamento do usuário (idempotente). */
    public void link(UUID userId, UUID transactionId, UUID attachmentId) {
        jdbc.sql("insert into transaction_attachments (transaction_id, attachment_id, user_id) values (?, ?, ?) "
                + "on conflict do nothing").params(transactionId, attachmentId, userId).update();
    }

    public void linkToBatch(UUID userId, UUID batchId, UUID attachmentId, int position) {
        jdbc.sql("insert into import_batch_attachments (batch_id, attachment_id, user_id, position) values (?, ?, ?, ?) "
                + "on conflict do nothing").params(batchId, attachmentId, userId, position).update();
    }

    @Transactional(readOnly = true)
    public List<AttachmentInfo> forTransaction(UUID userId, UUID transactionId) {
        transactions.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> ApiException.notFound("Lançamento não encontrado."));
        return jdbc.sql("select a.id, a.original_name, a.mime_type, a.size_bytes, a.sha256, a.created_at "
                        + "from attachments a join transaction_attachments ta on ta.attachment_id = a.id "
                        + "where ta.user_id = ? and ta.transaction_id = ? order by ta.created_at")
                .params(userId, transactionId).query(AttachmentService::map).list();
    }

    public List<AttachmentInfo> forBatch(UUID userId, UUID batchId) {
        return jdbc.sql("select a.id, a.original_name, a.mime_type, a.size_bytes, a.sha256, a.created_at "
                        + "from attachments a join import_batch_attachments b on b.attachment_id = a.id "
                        + "where b.user_id = ? and b.batch_id = ? order by b.position")
                .params(userId, batchId).query(AttachmentService::map).list();
    }

    /** Upload pelo site para um lançamento. */
    @Transactional
    public AttachmentInfo upload(UUID userId, UUID transactionId, String name, byte[] content) {
        transactions.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> ApiException.notFound("Lançamento não encontrado."));
        AttachmentInfo info = store(userId, name, content);
        link(userId, transactionId, info.id());
        return info;
    }

    @Transactional(readOnly = true)
    public Download download(UUID userId, UUID id) {
        AttachmentInfo info = jdbc.sql("select id, original_name, mime_type, size_bytes, sha256, created_at "
                        + "from attachments where user_id = ? and id = ?")
                .params(userId, id).query(AttachmentService::map).optional()
                .orElseThrow(() -> ApiException.notFound("Anexo não encontrado."));
        return new Download(info, storage.read(userId, id));
    }

    // ---------------------------------------------------------------- utilitários

    private static AttachmentInfo map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AttachmentInfo(rs.getObject("id", UUID.class), rs.getString("original_name"),
                rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getTimestamp("created_at").toInstant());
    }

    static String sanitize(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "anexo";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    static boolean starts(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(Arrays.copyOf(b, prefix.length), prefix);
    }

    /** Texto: sem bytes nulos no começo do arquivo. */
    static boolean isText(byte[] b) {
        int n = Math.min(b.length, 8192);
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) return false;
        }
        return true;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
