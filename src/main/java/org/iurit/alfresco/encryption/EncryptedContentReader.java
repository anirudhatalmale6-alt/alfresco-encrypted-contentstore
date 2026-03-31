package org.iurit.alfresco.encryption;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Locale;

import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * A content reader that transparently decrypts encrypted content.
 * If the content is not encrypted (legacy/unencrypted files), it
 * returns the raw content as-is for backward compatibility.
 */
public class EncryptedContentReader implements ContentReader {

    private static final Log LOG = LogFactory.getLog(EncryptedContentReader.class);

    private final ContentReader delegate;
    private final AESEncryptionService encryptionService;

    public EncryptedContentReader(ContentReader delegate, AESEncryptionService encryptionService) {
        this.delegate = delegate;
        this.encryptionService = encryptionService;
    }

    @Override
    public InputStream getContentInputStream() throws ContentIOException {
        try {
            InputStream rawIn = delegate.getContentInputStream();
            BufferedInputStream buffered = new BufferedInputStream(rawIn, 8192);
            buffered.mark(1);

            int firstByte = buffered.read();
            buffered.reset();

            if (firstByte == 1) { // FORMAT_VERSION = 1
                // Likely encrypted — try to decrypt
                try {
                    return encryptionService.getDecryptingInputStream(buffered);
                } catch (Exception e) {
                    // If decryption fails, this might be unencrypted content that
                    // happens to start with byte 0x01. Fall through to raw.
                    LOG.debug("Content at " + delegate.getContentUrl() +
                              " starts with 0x01 but failed decryption, treating as unencrypted", e);
                    // Need to re-open the stream since decryption consumed some bytes
                    buffered.close();
                    return delegate.getContentInputStream();
                }
            } else {
                // Not encrypted — return raw stream
                return buffered;
            }
        } catch (IOException e) {
            throw new ContentIOException("Failed to read content", e);
        }
    }

    @Override
    public void getContent(File file) throws ContentIOException {
        try (InputStream in = getContentInputStream();
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
        } catch (IOException e) {
            throw new ContentIOException("Failed to read encrypted content to file", e);
        }
    }

    @Override
    public String getContentString() throws ContentIOException {
        try (InputStream in = getContentInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            String encoding = getEncoding();
            if (encoding == null || encoding.isEmpty()) {
                encoding = "UTF-8";
            }
            return baos.toString(encoding);
        } catch (IOException e) {
            throw new ContentIOException("Failed to read encrypted content as string", e);
        }
    }

    @Override
    public String getContentString(int length) throws ContentIOException {
        String full = getContentString();
        if (full.length() <= length) {
            return full;
        }
        return full.substring(0, length);
    }

    @Override
    public void getContent(OutputStream os) throws ContentIOException {
        try (InputStream in = getContentInputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        } catch (IOException e) {
            throw new ContentIOException("Failed to write decrypted content to output stream", e);
        }
    }

    @Override
    public ReadableByteChannel getReadableChannel() throws ContentIOException {
        return java.nio.channels.Channels.newChannel(getContentInputStream());
    }

    @Override
    public FileChannel getFileChannel() throws ContentIOException {
        throw new ContentIOException(
            "FileChannel not supported for encrypted content. Use getContentInputStream() instead.");
    }

    @Override
    public ContentReader getReader() throws ContentIOException {
        return new EncryptedContentReader(delegate.getReader(), encryptionService);
    }

    @Override
    public boolean exists() {
        return delegate.exists();
    }

    @Override
    public long getLastModified() {
        return delegate.getLastModified();
    }

    // Delegate all metadata methods

    @Override
    public String getContentUrl() {
        return delegate.getContentUrl();
    }

    @Override
    public String getMimetype() {
        return delegate.getMimetype();
    }

    @Override
    public void setMimetype(String mimetype) {
        delegate.setMimetype(mimetype);
    }

    @Override
    public String getEncoding() {
        return delegate.getEncoding();
    }

    @Override
    public void setEncoding(String encoding) {
        delegate.setEncoding(encoding);
    }

    @Override
    public Locale getLocale() {
        return delegate.getLocale();
    }

    @Override
    public void setLocale(Locale locale) {
        delegate.setLocale(locale);
    }

    @Override
    public long getSize() {
        return delegate.getSize();
    }

    @Override
    public ContentData getContentData() {
        return delegate.getContentData();
    }

    @Override
    public boolean isChannelOpen() {
        return delegate.isChannelOpen();
    }

    @Override
    public void addListener(org.alfresco.service.cmr.repository.ContentStreamListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }
}
