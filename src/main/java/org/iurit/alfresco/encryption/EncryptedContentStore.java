package org.iurit.alfresco.encryption;

import java.util.Map;

import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * An encrypting content store that wraps a standard ContentStore (typically FileContentStore).
 * Uses composition: delegates all operations to the wrapped store, intercepting
 * getWriter/getReader to add encryption/decryption.
 *
 * All new content is encrypted using AES-256-GCM before being stored.
 * Reads transparently decrypt content. Unencrypted (legacy) content
 * is returned as-is for backward compatibility.
 */
public class EncryptedContentStore implements ContentStore {

    private static final Log LOG = LogFactory.getLog(EncryptedContentStore.class);

    private ContentStore delegate;
    private AESEncryptionService encryptionService;

    @Override
    public ContentWriter getWriter(ContentContext context) {
        ContentWriter rawWriter = delegate.getWriter(context);
        LOG.debug("Creating encrypted writer for: " + rawWriter.getContentUrl());
        return new EncryptedContentWriter(rawWriter, encryptionService);
    }

    @Override
    public ContentReader getReader(String contentUrl) {
        ContentReader rawReader = delegate.getReader(contentUrl);
        if (rawReader == null || !rawReader.exists()) {
            return rawReader;
        }
        LOG.debug("Creating decrypting reader for: " + contentUrl);
        return new EncryptedContentReader(rawReader, encryptionService);
    }

    @Override
    public boolean delete(String contentUrl) {
        return delegate.delete(contentUrl);
    }

    @Override
    public boolean exists(String contentUrl) {
        return delegate.exists(contentUrl);
    }

    @Override
    public boolean isContentUrlSupported(String contentUrl) {
        return delegate.isContentUrlSupported(contentUrl);
    }

    @Override
    public boolean isWriteSupported() {
        return delegate.isWriteSupported();
    }

    @Override
    public String getRootLocation() {
        return delegate.getRootLocation();
    }

    @Override
    public long getSpaceFree() {
        return delegate.getSpaceFree();
    }

    @Override
    public long getSpaceTotal() {
        return delegate.getSpaceTotal();
    }

    // Spring setters
    public void setDelegate(ContentStore delegate) {
        this.delegate = delegate;
    }

    public void setEncryptionService(AESEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }
}
