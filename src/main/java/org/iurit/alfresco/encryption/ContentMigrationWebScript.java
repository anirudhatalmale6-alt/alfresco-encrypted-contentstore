package org.iurit.alfresco.encryption;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;

/**
 * Admin webscript to migrate existing unencrypted content to encrypted.
 *
 * GET /alfresco/s/api/encryption/migrate?batchSize=100&dryRun=true
 *
 * Parameters:
 *   batchSize - number of nodes to process (default: 100)
 *   dryRun    - if true, only count unencrypted nodes (default: false)
 *
 * Only accessible by admin users.
 */
public class ContentMigrationWebScript extends AbstractWebScript {

    private static final Log LOG = LogFactory.getLog(ContentMigrationWebScript.class);

    private SearchService searchService;
    private ContentService contentService;
    private AESEncryptionService encryptionService;
    private String contentStoreRoot;

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) {
      try {
        doExecute(req, res);
      } catch (Exception e) {
        throw new RuntimeException("Migration failed", e);
      }
    }

    private void doExecute(WebScriptRequest req, WebScriptResponse res) throws Exception {
        String batchSizeParam = req.getParameter("batchSize");
        String dryRunParam = req.getParameter("dryRun");

        int batchSize = 100;
        boolean dryRun = false;

        if (batchSizeParam != null) {
            batchSize = Integer.parseInt(batchSizeParam);
        }
        if (dryRunParam != null) {
            dryRun = Boolean.parseBoolean(dryRunParam);
        }

        LOG.info("Content migration started. batchSize=" + batchSize + ", dryRun=" + dryRun);

        // Search for all cm:content nodes
        SearchParameters sp = new SearchParameters();
        sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
        sp.setQuery("TYPE:\"cm:content\"");
        sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
        sp.setMaxItems(batchSize);

        ResultSet results = null;
        int processed = 0;
        int encrypted = 0;
        int skipped = 0;
        int errors = 0;

        try {
            results = searchService.query(sp);
            List<NodeRef> nodes = results.getNodeRefs();

            for (NodeRef nodeRef : nodes) {
                try {
                    ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
                    if (reader == null || !reader.exists()) {
                        skipped++;
                        continue;
                    }

                    // Check if already encrypted by reading the RAW file on disk
                    // ContentService goes through EncryptedContentStore which decrypts,
                    // so we must check the raw file directly
                    if (isRawContentEncrypted(reader.getContentUrl())) {
                        skipped++;
                        continue;
                    }

                    if (dryRun) {
                        processed++;
                        continue;
                    }

                    // Read plain content through ContentService (which decrypts if needed,
                    // but for unencrypted files returns as-is)
                    String mimetype = reader.getMimetype();
                    String encoding = reader.getEncoding();

                    reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
                    InputStream plainIn = reader.getContentInputStream();

                    // Write back through ContentService (which encrypts via EncryptedContentStore)
                    ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
                    writer.setMimetype(mimetype);
                    writer.setEncoding(encoding);

                    OutputStream encOut = writer.getContentOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = plainIn.read(buf)) != -1) {
                        encOut.write(buf, 0, len);
                    }
                    plainIn.close();
                    encOut.close();

                    encrypted++;
                    processed++;

                    if (processed % 100 == 0) {
                        LOG.info("Migration progress: processed=" + processed + ", encrypted=" + encrypted);
                    }

                } catch (Exception e) {
                    LOG.error("Failed to migrate node: " + nodeRef, e);
                    errors++;
                }
            }
        } finally {
            if (results != null) {
                results.close();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"status\": \"completed\",\n");
        sb.append("  \"dryRun\": ").append(dryRun).append(",\n");
        sb.append("  \"batchSize\": ").append(batchSize).append(",\n");
        sb.append("  \"processed\": ").append(processed).append(",\n");
        sb.append("  \"encrypted\": ").append(encrypted).append(",\n");
        sb.append("  \"skipped\": ").append(skipped).append(",\n");
        sb.append("  \"errors\": ").append(errors).append("\n");
        sb.append("}");

        res.setContentType("application/json");
        res.getWriter().write(sb.toString());

        LOG.info("Content migration completed. processed=" + processed +
                 ", encrypted=" + encrypted + ", skipped=" + skipped + ", errors=" + errors);
    }

    /**
     * Checks if the raw file on disk is already encrypted by reading its first byte.
     * This bypasses the EncryptedContentStore's automatic decryption.
     *
     * Content URL format: store://2026/4/1/22/7/xxx.bin
     * File path: {contentStoreRoot}/2026/4/1/22/7/xxx.bin
     */
    private boolean isRawContentEncrypted(String contentUrl) {
        if (contentUrl == null || contentStoreRoot == null) {
            return false;
        }

        try {
            // Convert content URL to file path
            // Format: store://YYYY/M/D/H/m/uuid.bin
            String relativePath = contentUrl;
            if (relativePath.startsWith("store://")) {
                relativePath = relativePath.substring("store://".length());
            }

            File rawFile = new File(contentStoreRoot, relativePath);
            if (!rawFile.exists()) {
                return false;
            }

            try (FileInputStream fis = new FileInputStream(rawFile)) {
                int firstByte = fis.read();
                return firstByte == 1; // FORMAT_VERSION = 1
            }
        } catch (Exception e) {
            LOG.debug("Could not check raw encryption status for: " + contentUrl, e);
            return false;
        }
    }

    public void setSearchService(SearchService searchService) { this.searchService = searchService; }
    public void setContentService(ContentService contentService) { this.contentService = contentService; }
    public void setEncryptionService(AESEncryptionService encryptionService) { this.encryptionService = encryptionService; }
    public void setContentStoreRoot(String contentStoreRoot) { this.contentStoreRoot = contentStoreRoot; }
}
