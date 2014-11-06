package org.babelomics.exomeserver.lib.mongodb.dbAdaptor;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import org.babelomics.exomeserver.lib.mongodb.converter.ExomeServerDBObjectToVariantSourceConverter;
import org.opencb.datastore.core.QueryOptions;
import org.opencb.datastore.core.QueryResult;
import org.opencb.datastore.mongodb.MongoDBCollection;
import org.opencb.datastore.mongodb.MongoDBConfiguration;
import org.opencb.datastore.mongodb.MongoDataStore;
import org.opencb.datastore.mongodb.MongoDataStoreManager;
import org.opencb.opencga.lib.auth.MongoCredentials;
import org.opencb.opencga.storage.variant.VariantSourceDBAdaptor;
import org.opencb.opencga.storage.variant.mongodb.DBObjectToVariantSourceConverter;
import org.opencb.opencga.storage.variant.mongodb.StudyMongoDBAdaptor;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Alejandro Alemán Ramos <aaleman@cipf.es>
 */
public class ExomeServerVariantSourceMongoDBAdaptor implements VariantSourceDBAdaptor {
    private static final Map<String, List> samplesInSources = new HashMap<>();

    private final MongoDataStoreManager mongoManager;
    private final MongoDataStore db;
    private final DBObjectToVariantSourceConverter variantSourceConverter;


    public ExomeServerVariantSourceMongoDBAdaptor(MongoCredentials credentials) throws UnknownHostException {
        // Mongo configuration
        mongoManager = new MongoDataStoreManager(credentials.getMongoHost(), credentials.getMongoPort());
        MongoDBConfiguration mongoDBConfiguration = MongoDBConfiguration.builder()
                .add("username", credentials.getUsername())
                .add("password", credentials.getPassword() != null ? new String(credentials.getPassword()) : null).build();
        db = mongoManager.get(credentials.getMongoDbName(), mongoDBConfiguration);
        variantSourceConverter = new ExomeServerDBObjectToVariantSourceConverter();
    }

    @Override
    public QueryResult countSources() {
        MongoDBCollection coll = db.getCollection("files");
        return coll.count();
    }

    @Override
    public QueryResult getAllSources(QueryOptions options) {
        MongoDBCollection coll = db.getCollection("files");
        QueryBuilder qb = QueryBuilder.start();
//        parseQueryOptions(options, qb);

        return coll.find(qb.get(), options, variantSourceConverter);
    }

    @Override
    public QueryResult getAllSourcesByStudyId(String studyId, QueryOptions options) {
        MongoDBCollection coll = db.getCollection("files");
        QueryBuilder qb = QueryBuilder.start();
        getStudyIdFilter(studyId, qb);
//        parseQueryOptions(options, qb);

        return coll.find(qb.get(), options, variantSourceConverter);
    }

    @Override
    public QueryResult getAllSourcesByStudyIds(List<String> studyIds, QueryOptions options) {
        MongoDBCollection coll = db.getCollection("files");
        QueryBuilder qb = QueryBuilder.start();
        getStudyIdFilter(studyIds, qb);
//        parseQueryOptions(options, qb);

        return coll.find(qb.get(), options, variantSourceConverter);
    }

    @Override
    public QueryResult getSamplesBySource(String fileId, QueryOptions options) {
        if (samplesInSources.size() != (long) countSources().getResult().get(0)) {
            synchronized (StudyMongoDBAdaptor.class) {
                if (samplesInSources.size() != (long) countSources().getResult().get(0)) {
                    QueryResult queryResult = populateSamplesInSources();
                    populateSamplesQueryResult(fileId, queryResult);
                    return queryResult;
                }
            }
        }

        QueryResult queryResult = new QueryResult();
        populateSamplesQueryResult(fileId, queryResult);
        return queryResult;
    }

    @Override
    public QueryResult getSamplesBySources(List<String> fileIds, QueryOptions options) {
        if (samplesInSources.size() != (long) countSources().getResult().get(0)) {
            synchronized (StudyMongoDBAdaptor.class) {
                if (samplesInSources.size() != (long) countSources().getResult().get(0)) {
                    QueryResult queryResult = populateSamplesInSources();
                    populateSamplesQueryResult(fileIds, queryResult);
                    return queryResult;
                }
            }
        }

        QueryResult queryResult = new QueryResult();
        populateSamplesQueryResult(fileIds, queryResult);
        return queryResult;
    }

    @Override
    public QueryResult getSourceDownloadUrlByName(String filename) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public List<QueryResult> getSourceDownloadUrlByName(List<String> filenames) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public QueryResult getSourceDownloadUrlById(String fileId, String studyId) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean close() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    private QueryBuilder getStudyIdFilter(String id, QueryBuilder builder) {
        return builder.and(DBObjectToVariantSourceConverter.STUDYID_FIELD).is(id);
    }

    private QueryBuilder getStudyIdFilter(List<String> ids, QueryBuilder builder) {
        return builder.and(DBObjectToVariantSourceConverter.STUDYID_FIELD).in(ids);
    }

    /**
     * Populates the dictionary relating sources and samples.
     *
     * @return The QueryResult with information of how long the query took
     */
    private QueryResult populateSamplesInSources() {
        MongoDBCollection coll = db.getCollection("files");
        DBObject returnFields = new BasicDBObject(DBObjectToVariantSourceConverter.FILEID_FIELD, true)
                .append(DBObjectToVariantSourceConverter.SAMPLES_FIELD, true);
        QueryResult queryResult = coll.find(null, null, null, returnFields);

        List<DBObject> result = queryResult.getResult();
        for (DBObject dbo : result) {
            String key = dbo.get(DBObjectToVariantSourceConverter.FILEID_FIELD).toString();
            DBObject value = (DBObject) dbo.get(DBObjectToVariantSourceConverter.SAMPLES_FIELD);
            samplesInSources.put(key, new ArrayList<>(value.toMap().keySet()));
        }

        return queryResult;
    }

    private void populateSamplesQueryResult(String fileId, QueryResult queryResult) {
        List<List> samples = new ArrayList<>(1);
        List<String> samplesInSource = samplesInSources.get(fileId);

        if (samplesInSource == null || samplesInSource.isEmpty()) {
            queryResult.setWarningMsg("Source " + fileId + " not found");
            queryResult.setNumTotalResults(0);
        } else {
            samples.add(samplesInSource);
            queryResult.setResult(samples);
            queryResult.setNumTotalResults(1);
        }
    }

    private void populateSamplesQueryResult(List<String> fileIds, QueryResult queryResult) {
        List<List> samples = new ArrayList<>(fileIds.size());

        for (String fileId : fileIds) {
            List<String> samplesInSource = samplesInSources.get(fileId);

            if (samplesInSource == null || samplesInSource.isEmpty()) {
                // Samples not found
                samples.add(new ArrayList<>());
                if (queryResult.getWarningMsg() == null) {
                    queryResult.setWarningMsg("Source " + fileId + " not found");
                } else {
                    queryResult.setWarningMsg(queryResult.getWarningMsg().concat("\nSource " + fileId + " not found"));
                }
//                queryResult.setNumTotalResults(0);
            } else {
                // Add new list of samples
                samples.add(samplesInSource);
//                queryResult.setNumTotalResults(1);
            }
        }

        queryResult.setResult(samples);
        queryResult.setNumTotalResults(fileIds.size());
    }

}
