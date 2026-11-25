package com.controlefin.finance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Arquivos dos anexos no disco (volume próprio), sempre criptografados com AES-256-GCM.
 * Formato: "CFA1" + IV (12 bytes) + texto cifrado com a tag. O id do anexo entra como dado autenticado (AAD):
 * um arquivo trocado de lugar não abre. Chave: ATTACHMENTS_KEY do .env (derivada com SHA-256).
 */
@Component
public class AttachmentStorage {

    private static final byte[] MAGIC = "CFA1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final SecretKeySpec key;

    public AttachmentStorage(@Value("${app.attachments.dir}") String dir, @Value("${app.attachments.key}") String key) {
        if (key == null || key.length() < 32) {
            throw new IllegalStateException("ATTACHMENTS_KEY precisa ter pelo menos 32 caracteres.");
        }
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void write(UUID userId, UUID id, byte[] plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad(id));
            byte[] ct = c.doFinal(plain);
            byte[] out = ByteBuffer.allocate(MAGIC.length + IV_LENGTH + ct.length).put(MAGIC).put(iv).put(ct).array();
            Path target = path(userId, id);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), id.toString(), ".tmp");
            Files.write(tmp, out);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar o anexo.", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar o anexo.", e);
        }
    }

    public byte[] read(UUID userId, UUID id) {
        try {
            byte[] all = Files.readAllBytes(path(userId, id));
            if (all.length < MAGIC.length + IV_LENGTH || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
                throw new IllegalStateException("Arquivo de anexo inválido.");
            }
            byte[] iv = Arrays.copyOfRange(all, MAGIC.length, MAGIC.length + IV_LENGTH);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad(id));
            return c.doFinal(all, MAGIC.length + IV_LENGTH, all.length - MAGIC.length - IV_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException("Anexo não encontrado no disco.", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Não foi possível abrir o anexo (chave diferente ou arquivo alterado).", e);
        }
    }

    Path path(UUID userId, UUID id) {
        Path p = dir.resolve(userId.toString()).resolve(id + ".bin").normalize();
        if (!p.startsWith(dir)) throw new IllegalArgumentException("Caminho inválido.");
        return p;
    }

    private static byte[] aad(UUID id) {
        return id.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
