package org.babelomics.pvs.lib.io;

import com.mongodb.*;
import org.babelomics.pvs.lib.mongodb.converter.PVSDBObjectToVariantSourceConverter;
import org.babelomics.pvs.lib.mongodb.converter.PVSDBObjectToVariantStatsConverter;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.VariantSource;
import org.opencb.biodata.models.variant.VariantSourceEntry;
import org.opencb.biodata.models.variant.effect.ConsequenceTypeMappings;
import org.opencb.biodata.models.variant.effect.VariantEffect;
import org.opencb.opencga.lib.auth.MongoCredentials;
import org.opencb.opencga.storage.variant.VariantDBWriter;
import org.opencb.opencga.storage.variant.mongodb.*;

import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Alejandro Alemán Ramos <aaleman@cipf.es>
 */
public class PVSVariantMongoWriter extends VariantDBWriter {
    public static final int CHUNK_SIZE_SMALL = 1000;
    public static final int CHUNK_SIZE_BIG = 10000;

    private VariantSource source;

    private MongoClient mongoClient;
    private DB db;

    private String filesCollectionName;
    private String variantsCollectionName;
    private DBCollection filesCollection;
    private DBCollection variantsCollection;

    private Map<String, DBObject> mongoMap;
    private Map<String, DBObject> mongoFileMap;

    private MongoCredentials credentials;

    private boolean includeStats;
    private boolean includeEffect;
    private boolean includeSamples;

    private List<String> samples;
    private Map<String, Integer> conseqTypes;

    private DBObjectToVariantConverter variantConverter;
    private DBObjectToVariantStatsConverter statsConverter;
    private DBObjectToVariantSourceConverter sourceConverter;
    private DBObjectToVariantSourceEntryConverter archivedVariantFileConverter;

    private long numVariantsWritten;

    public PVSVariantMongoWriter(VariantSource source, MongoCredentials credentials) {
        this(source, credentials, "variants", "files");
    }

    public PVSVariantMongoWriter(VariantSource source, MongoCredentials credentials, String variantsCollection, String filesCollection) {
        this(source, credentials, variantsCollection, filesCollection, false, false, false);
    }

    public PVSVariantMongoWriter(VariantSource source, MongoCredentials credentials, String variantsCollection, String filesCollection,
                                 boolean includeSamples, boolean includeStats, boolean includeEffect) {
        if (credentials == null) {
            throw new IllegalArgumentException("Credentials for accessing the database must be specified");
        }
        this.source = source;
        this.credentials = credentials;
        this.filesCollectionName = filesCollection;
        this.variantsCollectionName = variantsCollection;

        this.mongoMap = new HashMap<>();
        this.mongoFileMap = new HashMap<>();

        this.includeSamples = includeSamples;
        this.includeStats = includeStats;
        this.includeEffect = includeEffect;

        conseqTypes = new LinkedHashMap<>();
        samples = new ArrayList<>();

        setConverters(this.includeStats, this.includeSamples, this.includeEffect);

        numVariantsWritten = 0;
    }

    @Override
    public boolean open() {
        try {
            // Mongo configuration
            ServerAddress address = new ServerAddress(credentials.getMongoHost(), credentials.getMongoPort());
            if (credentials.getMongoCredentials() != null) {
                mongoClient = new MongoClient(address, Arrays.asList(credentials.getMongoCredentials()));
            } else {
                mongoClient = new MongoClient(address);
            }
            db = mongoClient.getDB(credentials.getMongoDbName());
        } catch (UnknownHostException ex) {
            Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }

        return db != null;
    }

    @Override
    public boolean pre() {
        // Mongo collection creation
        filesCollection = db.getCollection(filesCollectionName);
        variantsCollection = db.getCollection(variantsCollectionName);

        return variantsCollection != null && filesCollection != null;
    }

    @Override
    public boolean write(Variant variant) {
        return write(Arrays.asList(variant));
    }

    @Override
    public boolean write(List<Variant> data) {
        buildBatchRaw(data);
        if (this.includeEffect) {
            buildEffectRaw(data);
        }
        buildBatchIndex(data);
        return writeBatch(data);
    }

    @Override
    protected boolean buildBatchRaw(List<Variant> data) {
        for (Variant v : data) {
            // Check if this variant is already stored
            String rowkey = variantConverter.buildStorageId(v);
            DBObject mongoVariant = new BasicDBObject("_id", rowkey);

            if (variantsCollection.count(mongoVariant) == 0) {
                mongoVariant = variantConverter.convertToStorageType(v);
            } /*else {
                System.out.println("Variant " + v.getChromosome() + ":" + v.getStart() + "-" + v.getEnd() + " already found");
            }*/

            BasicDBList mongoFiles = new BasicDBList();
            for (VariantSourceEntry archiveFile : v.getSourceEntries().values()) {
                if (!archiveFile.getFileId().equals(source.getFileId())) {
                    continue;
                }

                if (this.includeSamples && samples.isEmpty() && archiveFile.getSamplesData().size() > 0) {
                    // First time a variant is loaded, the list of samples is populated.
                    // This guarantees that samples are loaded only once to keep order among variants,
                    // and that they are loaded before needed by the ArchivedVariantFileConverter
                    samples.addAll(archiveFile.getSampleNames());
                }

                DBObject mongoFile = archivedVariantFileConverter.convertToStorageType(archiveFile);
                mongoFiles.add(mongoFile);
                mongoFileMap.put(rowkey + "_" + archiveFile.getFileId(), mongoFile);
            }

            mongoVariant.put(DBObjectToVariantConverter.FILES_FIELD, mongoFiles);
            mongoMap.put(rowkey, mongoVariant);
        }

        return true;
    }

    @Override
    protected boolean buildEffectRaw(List<Variant> variants) {
        for (Variant v : variants) {
            DBObject mongoVariant = mongoMap.get(variantConverter.buildStorageId(v));

            if (!mongoVariant.containsField(DBObjectToVariantConverter.CHROMOSOME_FIELD)) {
                // TODO It means that the same position was already found in this file, so __for now__ it won't be processed again
                continue;
            }

            Set<String> genesSet = new HashSet<>();
            Set<String> soSet = new HashSet<>();

            // Add effects to file
            if (!v.getAnnotation().getEffects().isEmpty()) {
                Set<BasicDBObject> effectsSet = new HashSet<>();

                for (List<VariantEffect> effects : v.getAnnotation().getEffects().values()) {
                    for (VariantEffect effect : effects) {
                        BasicDBObject object = getVariantEffectDBObject(effect);
                        effectsSet.add(object);

                        addConsequenceType(effect);
                        soSet.addAll(Arrays.asList((String[]) object.get("so")));
                        if (object.containsField("geneName")) {
                            genesSet.add(object.get("geneName").toString());
                        }
                    }
                }

                BasicDBList effectsList = new BasicDBList();
                effectsList.addAll(effectsSet);
                mongoVariant.put("effects", effectsList);
            }

            // Add gene fields directly to the variant, for query optimization purposes
            BasicDBObject _at = (BasicDBObject) mongoVariant.get("_at");
            if (!genesSet.isEmpty()) {
                BasicDBList genesList = new BasicDBList();
                genesList.addAll(genesSet);
                _at.append("gn", genesList);
            }
            if (!soSet.isEmpty()) {
                BasicDBList soList = new BasicDBList();
                soList.addAll(soSet);
                _at.append("ct", soList);
            }
        }

        return false;
    }

    private BasicDBObject getVariantEffectDBObject(VariantEffect effect) {
        String[] consequenceTypes = new String[effect.getConsequenceTypes().length];
        for (int i = 0; i < effect.getConsequenceTypes().length; i++) {
            consequenceTypes[i] = ConsequenceTypeMappings.accessionToTerm.get(effect.getConsequenceTypes()[i]);
        }

        BasicDBObject object = new BasicDBObject("so", consequenceTypes).append("featureId", effect.getFeatureId());
        if (effect.getGeneName() != null && !effect.getGeneName().isEmpty()) {
            object.append("geneName", effect.getGeneName());
        }
        return object;
    }

    @Override
    protected boolean buildBatchIndex(List<Variant> data) {
        variantsCollection.createIndex(new BasicDBObject("_at.chunkIds", 1));
        variantsCollection.createIndex(new BasicDBObject("_at.gn", 1));
        variantsCollection.createIndex(new BasicDBObject("_at.ct", 1));
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.ID_FIELD, 1));
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, 1));
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, 1)
                .append(DBObjectToVariantConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD, 1));
        return true;
    }

    @Override
    protected boolean writeBatch(List<Variant> batch) {
        for (Variant v : batch) {
            String rowkey = variantConverter.buildStorageId(v);
            DBObject mongoVariant = mongoMap.get(rowkey);
            DBObject query = new BasicDBObject("_id", rowkey);
            WriteResult wr;

            if (mongoVariant.containsField(DBObjectToVariantConverter.CHROMOSOME_FIELD)) {
                // Was fully built in this run because it didn't exist, and must be inserted
                try {
                    wr = variantsCollection.insert(mongoVariant);
                    if (!wr.getLastError().ok()) {
                        // TODO If not correct, retry?
                        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, wr.getError(), wr.getLastError());
                    }
                } catch (MongoInternalException ex) {
                    System.out.println(v);
                    Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, v.getChromosome() + ":" + v.getStart(), ex);
                } catch (DuplicateKeyException ex) {
                    Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.WARNING,
                            "Variant already existed: {0}:{1}", new Object[]{v.getChromosome(), v.getStart()});
                }

            } else { // It existed previously, was not fully built in this run and only files need to be updated
                // TODO How to do this efficiently, inserting all files at once?
                for (VariantSourceEntry archiveFile : v.getSourceEntries().values()) {
                    DBObject mongoFile = mongoFileMap.get(rowkey + "_" + archiveFile.getFileId());
                    BasicDBObject changes = new BasicDBObject().append("$addToSet",
                            new BasicDBObject(DBObjectToVariantConverter.FILES_FIELD, mongoFile));

                    wr = variantsCollection.update(query, changes, true, false);
                    if (!wr.getLastError().ok()) {
                        // TODO If not correct, retry?
                        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.SEVERE, wr.getError(), wr.getLastError());
                    }
                }
            }

        }

        mongoMap.clear();
        mongoFileMap.clear();

        numVariantsWritten += batch.size();
        Variant lastVariantInBatch = batch.get(batch.size() - 1);
        Logger.getLogger(VariantMongoWriter.class.getName()).log(Level.INFO, "{0}\tvariants written upto position {1}:{2}",
                new Object[]{numVariantsWritten, lastVariantInBatch.getChromosome(), lastVariantInBatch.getStart()});

        return true;
    }

    private boolean writeSourceSummary(VariantSource source) {
        DBObject studyMongo = sourceConverter.convertToStorageType(source);
        String id = source.getStudyId().toUpperCase() + "_" + source.getMetadata().get("disease").toString().toUpperCase() + "_" + source.getMetadata().get("phenotype").toString().toUpperCase();
        DBObject query = new BasicDBObject(DBObjectToVariantSourceConverter.FILEID_FIELD, id);
        WriteResult wr = filesCollection.update(query, studyMongo, true, false);

        return wr.getLastError().ok(); // TODO Is this a proper return statement?
    }

    @Override
    public boolean post() {
        writeSourceSummary(source);
        return true;
    }

    @Override
    public boolean close() {
        mongoClient.close();
        return true;
    }

    @Override
    public final void includeStats(boolean b) {
        includeStats = b;
        setConverters(includeStats, includeSamples, includeEffect);
    }

    @Override
    public final void includeSamples(boolean b) {
        includeSamples = b;
        setConverters(includeStats, includeSamples, includeEffect);
    }

    @Override
    public final void includeEffect(boolean b) {
        includeEffect = b;
        setConverters(includeStats, includeSamples, includeEffect);
    }

    private void setConverters(boolean includeStats, boolean includeSamples, boolean includeEffect) {
        boolean compressSamples;
        switch (source.getType()) {
            case FAMILY:
            case TRIO:
                compressSamples = false;
                break;
            case CONTROL:
            case CASE:
            case CASE_CONTROL:
            case COLLECTION:
            default:
                compressSamples = true;
        }

        sourceConverter = new PVSDBObjectToVariantSourceConverter();
        statsConverter = new PVSDBObjectToVariantStatsConverter();
        // TODO Allow to configure samples compression
        archivedVariantFileConverter = new DBObjectToVariantSourceEntryConverter(
                compressSamples,
                includeSamples ? samples : null,
                includeStats ? statsConverter : null);
        // TODO Not sure about commenting this, but otherwise it looks like the ArchiveVariantFile will be processed twice
//        variantConverter = new DBObjectToVariantConverter(archivedVariantFileConverter);
        variantConverter = new DBObjectToVariantConverter();
    }

    private void addConsequenceType(VariantEffect effect) {
        for (int so : effect.getConsequenceTypes()) {
            String ct = ConsequenceTypeMappings.accessionToTerm.get(so);
            int ctCount = conseqTypes.containsKey(ct) ? conseqTypes.get(ct) + 1 : 1;
            conseqTypes.put(ct, ctCount);
        }
    }


}
