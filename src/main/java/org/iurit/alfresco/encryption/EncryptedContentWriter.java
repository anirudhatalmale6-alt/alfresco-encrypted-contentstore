package org.iurit.alfresco.encryption;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Locale;

import org.alfresco.repo.content.AbstractContentWriter;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * A content writer that encrypts content before writing it to the
 * delegate writer's underlying file. The approach:
 *
 * 1. Write encrypted content to a temp file
 * 2. On close, the delegate writer picks up the temp file content
 *
 * This writer delegates most metadata operations to the underlying writer,
 * only intercepting the actual content stream to encrypt it.
 */
public class EncryptedContentWriter implements ContentWriter {

    private static final Log LOG = LogFactory.getLog(EncryptedContentWriter.class);

    private final ContentWriter delegate;
    private final AESEncryptionService encryptionService;

    public EncryptedContentWriter(ContentWriter delegate, AESEncryptionService encryptionService) {
        this.delegate = delegate;
        this.encryptionService = encryptionService;
    }

    @Override
    public void putContent(InputStream is) throws ContentIOException {
        try {
            OutputStream out = delegate.getContentOutputStream();
            OutputStream encOut = encryptionService.getEncryptingOutputStream(out);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                encOut.write(buf, 0, len);
            }
            encOut.close();
            out.close();
        } catch (Exception e) {
            throw new ContentIOException("Failed to write encrypted content", e);
        }
    }

    @Override
    public void putContent(File file) throws ContentIOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            putContent(fis);
        } catch (IOException e) {
            throw new ContentIOException("Failed to write encrypted content from file", e);
        }
    }

    @Override
    public void putContent(String content) throws ContentIOException {
        try {
            byte[] bytes = content.getBytes("UTF-8");
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            putContent(bais);
        } catch (Exception e) {
            throw new ContentIOException("Failed to write encrypted string content", e);
        }
    }

    @Override
    public void putContent(ContentReader reader) throws ContentIOException {
        try (InputStream is = reader.getContentInputStream()) {
            putContent(is);
        } catch (IOException e) {
            throw new ContentIOException("Failed to write encrypted content from reader", e);
        }
    }

    @Override
    public OutputStream getContentOutputStream() throws ContentIOException {
        try {
            OutputStream rawOut = delegate.getContentOutputStream();
            return encryptionService.getEncryptingOutputStream(rawOut);
        } catch (Exception e) {
            throw new ContentIOException("Failed to create encrypting output stream", e);
        }
    }

    @Override
    public WritableByteChannel getWritableChannel() throws ContentIOException {
        throw new ContentIOException("WritableByteChannel not supported for encrypted content. Use getContentOutputStream() instead.");
    }

    @Override
    public FileChannel getFileChannel(boolean truncate) throws ContentIOException {
        throw new ContentIOException("FileChannel not supported for encrypted content. Use getContentOutputStream() instead.");
    }

    // Delegate all metadata methods to the underlying writer

    @Override
    public ContentReader getReader() throws ContentIOException {
        return delegate.getReader();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

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
    public void guessEncoding() {
        delegate.guessEncoding();
    }

    @Override
    public void guessMimetype(String filename) {
        delegate.guessMimetype(filename);
    }
}
