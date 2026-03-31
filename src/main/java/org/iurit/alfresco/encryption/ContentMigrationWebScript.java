package org.iurit.alfresco.encryption;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

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

                    // Check if already encrypted
                    InputStream checkStream = reader.getContentInputStream();
                    BufferedInputStream buffered = new BufferedInputStream(checkStream, 1);
                    buffered.mark(1);
                    int firstByte = buffered.read();
                    buffered.close();

                    if (firstByte == 1) {
                        // Already encrypted
                        skipped++;
                        continue;
                    }

                    if (dryRun) {
                        processed++;
                        continue;
                    }

                    // Encrypt
                    String mimetype = reader.getMimetype();
                    String encoding = reader.getEncoding();

                    reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
                    InputStream plainIn = reader.getContentInputStream();

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

    public void setSearchService(SearchService searchService) { this.searchService = searchService; }
    public void setContentService(ContentService contentService) { this.contentService = contentService; }
    public void setEncryptionService(AESEncryptionService encryptionService) { this.encryptionService = encryptionService; }
}
