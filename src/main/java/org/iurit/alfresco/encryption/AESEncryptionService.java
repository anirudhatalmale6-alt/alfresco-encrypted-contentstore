package org.iurit.alfresco.encryption;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * AES-256-GCM encryption service backed by a JKS keystore.
 *
 * Each encrypted file is prefixed with:
 *   [1 byte: version] [4 bytes: key alias length] [key alias bytes] [12 bytes: IV]
 * followed by the AES-GCM ciphertext (which includes the authentication tag).
 */
public class AESEncryptionService {

    private static final Log LOG = LogFactory.getLog(AESEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final byte FORMAT_VERSION = 1;

    private String keystorePath;
    private String keystorePassword;
    private String keyAlias;
    private String keyPassword;

    private Key encryptionKey;
    private boolean initialized = false;

    public void init() {
        try {
            KeyStore keyStore = KeyStore.getInstance("JCEKS");
            try (FileInputStream fis = new FileInputStream(keystorePath)) {
                keyStore.load(fis, keystorePassword.toCharArray());
            }

            encryptionKey = keyStore.getKey(keyAlias, keyPassword.toCharArray());
            if (encryptionKey == null) {
                throw new IllegalStateException(
                    "Key alias '" + keyAlias + "' not found in keystore: " + keystorePath);
            }

            // Verify it's an AES key
            if (!"AES".equals(encryptionKey.getAlgorithm())) {
                throw new IllegalStateException(
                    "Key alias '" + keyAlias + "' is not an AES key (found: " +
                    encryptionKey.getAlgorithm() + ")");
            }

            initialized = true;
            LOG.info("Encryption service initialized with keystore: " + keystorePath +
                     ", alias: " + keyAlias +
                     ", key size: " + (encryptionKey.getEncoded().length * 8) + " bits");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize encryption service", e);
        }
    }

    /**
     * Returns a CipherOutputStream that encrypts data written to the underlying stream.
     * Writes the header (version + key alias + IV) before returning.
     */
    public OutputStream getEncryptingOutputStream(OutputStream out) throws Exception {
        ensureInitialized();

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        // Write header
        byte[] aliasBytes = keyAlias.getBytes("UTF-8");
        out.write(FORMAT_VERSION);
        out.write((aliasBytes.length >> 24) & 0xFF);
        out.write((aliasBytes.length >> 16) & 0xFF);
        out.write((aliasBytes.length >> 8) & 0xFF);
        out.write(aliasBytes.length & 0xFF);
        out.write(aliasBytes);
        out.write(iv);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);

        return new CipherOutputStream(out, cipher);
    }

    /**
     * Returns a CipherInputStream that decrypts data read from the underlying stream.
     * Reads the header (version + key alias + IV) before returning.
     */
    public InputStream getDecryptingInputStream(InputStream in) throws Exception {
        ensureInitialized();

        int version = in.read();
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported encryption format version: " + version);
        }

        // Read key alias length (4 bytes big-endian)
        int aliasLen = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
        if (aliasLen <= 0 || aliasLen > 256) {
            throw new IllegalStateException("Invalid key alias length: " + aliasLen);
        }

        byte[] aliasBytes = new byte[aliasLen];
        readFully(in, aliasBytes);
        String storedAlias = new String(aliasBytes, "UTF-8");

        // Read IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        readFully(in, iv);

        // For now, use the current key. Key rotation support will resolve the alias.
        Key decryptKey = resolveKey(storedAlias);

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, decryptKey, spec);

        return new CipherInputStream(in, cipher);
    }

    /**
     * Checks if a stream starts with the encryption header.
     */
    public boolean isEncrypted(InputStream in) throws Exception {
        if (!in.markSupported()) {
            return false; // Can't check without mark support
        }
        in.mark(1);
        int firstByte = in.read();
        in.reset();
        return firstByte == FORMAT_VERSION;
    }

    /**
     * Resolves the encryption key for a given alias.
     * In the future, this will support multiple keys for rotation.
     */
    private Key resolveKey(String alias) throws Exception {
        if (alias.equals(this.keyAlias)) {
            return this.encryptionKey;
        }

        // Try loading from keystore (supports old keys during rotation)
        try {
            KeyStore keyStore = KeyStore.getInstance("JCEKS");
            try (FileInputStream fis = new FileInputStream(keystorePath)) {
                keyStore.load(fis, keystorePassword.toCharArray());
            }
            Key key = keyStore.getKey(alias, keyPassword.toCharArray());
            if (key != null) {
                return key;
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve key for alias: " + alias, e);
        }

        throw new IllegalStateException("Cannot find key for alias: " + alias);
    }

    private void readFully(InputStream in, byte[] buf) throws Exception {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read == -1) {
                throw new IllegalStateException("Unexpected end of stream");
            }
            offset += read;
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Encryption service not initialized. Call init() first.");
        }
    }

    // Spring setters
    public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }
    public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }
    public void setKeyAlias(String keyAlias) { this.keyAlias = keyAlias; }
    public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }
    public String getKeyAlias() { return keyAlias; }
}
