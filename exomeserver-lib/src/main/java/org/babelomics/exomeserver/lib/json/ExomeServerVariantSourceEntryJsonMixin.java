package org.babelomics.exomeserver.lib.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.opencb.opencga.storage.variant.json.VariantSourceEntryJsonMixin;

import java.util.Map;
import java.util.Set;

/**
 * @author Alejandro Alemán Ramos <aaleman@cipf.es>
 */
public abstract class ExomeServerVariantSourceEntryJsonMixin extends VariantSourceEntryJsonMixin {
    @JsonIgnore
    public abstract Set<String> getSampleNames();

    @JsonIgnore
    public abstract Map<String, String> getAttributes();

    @JsonIgnore
    public abstract String getFormat();

    @JsonIgnore
    public abstract Map<String, Map<String, String>> getSamplesData();


}
