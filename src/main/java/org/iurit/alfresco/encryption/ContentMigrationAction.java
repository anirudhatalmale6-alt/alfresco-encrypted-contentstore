package org.iurit.alfresco.encryption;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.executer.ActionExecuterAbstractBase;
import org.alfresco.repo.content.filestore.FileContentStore;
import org.alfresco.service.cmr.action.Action;
import org.alfresco.service.cmr.action.ParameterDefinition;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Action that encrypts the content of a single node.
 * Can be run via a scheduled job or manually to migrate existing content.
 *
 * Usage: Run against individual nodes or as part of a batch via
 * the ContentMigrationWebScript.
 */
public class ContentMigrationAction extends ActionExecuterAbstractBase {

    private static final Log LOG = LogFactory.getLog(ContentMigrationAction.class);
    public static final String NAME = "encrypt-content";

    private ContentService contentService;
    private AESEncryptionService encryptionService;

    @Override
    protected void executeImpl(Action action, NodeRef nodeRef) {
        try {
            ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
            if (reader == null || !reader.exists()) {
                LOG.debug("No content for node: " + nodeRef);
                return;
            }

            // Check if already encrypted
            InputStream checkStream = reader.getContentInputStream();
            BufferedInputStream buffered = new BufferedInputStream(checkStream, 1);
            buffered.mark(1);
            int firstByte = buffered.read();
            buffered.close();

            if (firstByte == 1) {
                LOG.debug("Content already encrypted for node: " + nodeRef);
                return;
            }

            // Read plain content
            String mimetype = reader.getMimetype();
            String encoding = reader.getEncoding();

            // Get fresh reader
            reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
            InputStream plainIn = reader.getContentInputStream();

            // Write encrypted content
            ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
            writer.setMimetype(mimetype);
            writer.setEncoding(encoding);

            // The EncryptedContentStore's getWriter() returns an EncryptedContentWriter
            // which automatically encrypts via putContent/getContentOutputStream
            OutputStream encOut = writer.getContentOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = plainIn.read(buf)) != -1) {
                encOut.write(buf, 0, len);
            }
            plainIn.close();
            encOut.close();

            LOG.info("Encrypted content for node: " + nodeRef);

        } catch (Exception e) {
            LOG.error("Failed to encrypt content for node: " + nodeRef, e);
            throw new RuntimeException("Content encryption failed for " + nodeRef, e);
        }
    }

    @Override
    protected void addParameterDefinitions(List<ParameterDefinition> paramList) {
        // No parameters needed
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setEncryptionService(AESEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }
}
