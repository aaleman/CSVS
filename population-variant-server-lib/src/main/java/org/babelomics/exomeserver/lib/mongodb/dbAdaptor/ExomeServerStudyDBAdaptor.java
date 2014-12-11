package org.babelomics.exomeserver.lib.mongodb.dbAdaptor;

import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.storage.variant.StudyDBAdaptor;

/**
 * @author Alejandro Alemán Ramos <aaleman@cipf.es>
 */
public interface ExomeServerStudyDBAdaptor extends StudyDBAdaptor {

    QueryResult listDiseases();
}
