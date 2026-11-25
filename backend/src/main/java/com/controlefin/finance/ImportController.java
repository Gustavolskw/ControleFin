package com.controlefin.finance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.controlefin.common.ApiException;
import com.controlefin.common.CurrentUser;
import com.controlefin.finance.AttachmentService.AttachmentInfo;
import com.controlefin.finance.ImportDtos.BatchResponse;

/** Site: revisar importações do Claude (confirmar pendentes, desfazer) e anexos dos lançamentos. */
@RestController
@RequestMapping("/api")
public class ImportController {

    private final ImportService imports;
    private final AttachmentService attachments;

    public ImportController(ImportService imports, AttachmentService attachments) {
        this.imports = imports;
        this.attachments = attachments;
    }

    @GetMapping("/imports")
    public List<BatchResponse> list() {
        return imports.list(CurrentUser.id());
    }

    @GetMapping("/imports/{id}")
    public BatchResponse get(@PathVariable UUID id) {
        return imports.get(CurrentUser.id(), id);
    }

    @PostMapping("/imports/{id}/confirm")
    public BatchResponse confirm(@PathVariable UUID id) {
        return imports.confirm(CurrentUser.id(), id);
    }

    @PostMapping("/imports/{id}/undo")
    public BatchResponse undo(@PathVariable UUID id) {
        return imports.undo(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- anexos

    @GetMapping("/transactions/{id}/attachments")
    public List<AttachmentInfo> transactionAttachments(@PathVariable UUID id) {
        return attachments.forTransaction(CurrentUser.id(), id);
    }

    @PostMapping("/transactions/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentInfo upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Escolha um arquivo.");
        return attachments.upload(CurrentUser.id(), id, file.getOriginalFilename(), file.getBytes());
    }

    /** Sempre como download (nunca exibido como página): evita qualquer conteúdo ativo no domínio do site. */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var d = attachments.download(CurrentUser.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.info().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.info().name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(d.content());
    }
}
