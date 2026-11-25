package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anexos: criptografia no disco, arquivo trocado/alterado não abre, tipos conferidos pelo conteúdo. */
class AttachmentStorageTest {

    @TempDir
    Path dir;

    private static final String KEY = "chave-de-teste-com-mais-de-32-caracteres!!";

    @Test
    void gravaCriptografadoELeDeVolta() throws Exception {
        var storage = new AttachmentStorage(dir.toString(), KEY);
        UUID user = UUID.randomUUID(), id = UUID.randomUUID();
        byte[] plain = "%PDF-1.7 fatura nubank dezembro".getBytes(StandardCharsets.UTF_8);
        storage.write(user, id, plain);

        byte[] onDisk = Files.readAllBytes(storage.path(user, id));
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("fatura"), "não pode ficar em texto puro");
        assertArrayEquals(plain, storage.read(user, id));
    }

    @Test
    void arquivoAlteradoTrocadoOuChaveErradaNaoAbre() throws Exception {
        var storage = new AttachmentStorage(dir.toString(), KEY);
        UUID user = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        storage.write(user, a, "conteudo A".getBytes(StandardCharsets.UTF_8));
        storage.write(user, b, "conteudo B".getBytes(StandardCharsets.UTF_8));

        // Trocar o arquivo de A pelo de B: o id entra como dado autenticado, então não abre.
        Files.copy(storage.path(user, b), storage.path(user, a), StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class, () -> storage.read(user, a));

        // Alterar um byte do conteúdo cifrado.
        Path pb = storage.path(user, b);
        byte[] bytes = Files.readAllBytes(pb);
        bytes[bytes.length - 1] ^= 1;
        Files.write(pb, bytes);
        assertThrows(IllegalStateException.class, () -> storage.read(user, b));

        // Outra chave.
        UUID c = UUID.randomUUID();
        storage.write(user, c, "conteudo C".getBytes(StandardCharsets.UTF_8));
        var other = new AttachmentStorage(dir.toString(), "outra-chave-com-mais-de-32-caracteres!!!!");
        assertThrows(IllegalStateException.class, () -> other.read(user, c));
    }

    @Test
    void chaveCurtaNaoSobe() {
        assertThrows(IllegalStateException.class, () -> new AttachmentStorage(dir.toString(), "curta"));
    }

    @Test
    void tipoConferidoPeloConteudo() {
        var pdf = AttachmentService.KINDS.get("pdf");
        assertTrue(pdf.valid().test("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(pdf.valid().test("<html><script>".getBytes(StandardCharsets.US_ASCII)));
        var png = AttachmentService.KINDS.get("png");
        assertTrue(png.valid().test(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10}));
        assertFalse(png.valid().test("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(AttachmentService.KINDS.get("csv").valid().test("data;valor\n2026-11-01;10,00".getBytes(StandardCharsets.UTF_8)));
        assertFalse(AttachmentService.KINDS.get("csv").valid().test(new byte[] {'a', 0, 'b'}));
        assertFalse(AttachmentService.KINDS.containsKey("html"));
        assertFalse(AttachmentService.KINDS.containsKey("svg"));
        assertFalse(AttachmentService.KINDS.containsKey("exe"));
    }

    @Test
    void nomeDoArquivoSemCaminho() {
        assertEquals("fatura.pdf", AttachmentService.sanitize("../../etc/fatura.pdf"));
        assertEquals("fatura.pdf", AttachmentService.sanitize("C:\\Users\\x\\fatura.pdf"));
        assertEquals("anexo", AttachmentService.sanitize(".."));
        assertEquals("a_b.csv", AttachmentService.sanitize("a\"b.csv"));
    }
}
