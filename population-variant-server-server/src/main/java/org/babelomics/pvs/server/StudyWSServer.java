package org.babelomics.pvs.server;

import com.mongodb.BasicDBObject;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import org.babelomics.pvs.lib.mongodb.converter.PVSDBObjectToVariantSourceConverter;
import org.babelomics.pvs.lib.mongodb.dbAdaptor.PVSStudyDBAdaptor;
import org.babelomics.pvs.lib.mongodb.dbAdaptor.PVSStudyMongoDBAdaptor;
import org.babelomics.pvs.lib.mongodb.dbAdaptor.PVSVariantSourceMongoDBAdaptor;
import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.lib.auth.IllegalOpenCGACredentialsException;
import org.opencb.opencga.storage.variant.VariantSourceDBAdaptor;

import javax.naming.NamingException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;

/**
 * @author Alejandro Alemán Ramos <aaleman@cipf.es>
 */
@Path("/studies")
@Api(value = "studies", description = "Study")
@Produces(MediaType.APPLICATION_JSON)
public class StudyWSServer extends ExomeServerWSServer {

    private PVSStudyDBAdaptor studyMongoDbAdaptor;
    private VariantSourceDBAdaptor variantSourceDbAdaptor;

    public StudyWSServer(@DefaultValue("") @PathParam("version") String version, @Context UriInfo uriInfo, @Context HttpServletRequest hsr)
            throws IOException, IllegalOpenCGACredentialsException, NamingException {
        super(version, uriInfo, hsr);
        studyMongoDbAdaptor = new PVSStudyMongoDBAdaptor(credentials);
        variantSourceDbAdaptor = new PVSVariantSourceMongoDBAdaptor(credentials);
    }

    @GET
    @Path("/list")
    @Produces("application/json")
    @ApiOperation(value = "Get all Studies")
    public Response getStudies() {
        return createOkResponse(studyMongoDbAdaptor.getAllStudies(queryOptions));
    }


    @GET
    @Path("/diseases")
    @Produces("application/json")
    @ApiOperation(value = "Get all Diseases")
    public Response getDiseases() {
        return createOkResponse(studyMongoDbAdaptor.listDiseases());
    }

    @GET
    @Path("/{study}/files")
    @Produces("application/json")
    @ApiOperation(value = "Get Files from a Study")
    public Response getFilesByStudy(@ApiParam(value = "study") @PathParam("study") String study) {
        QueryResult idQueryResult = studyMongoDbAdaptor.findStudyNameOrStudyId(study, queryOptions);
        if (idQueryResult.getNumResults() == 0) {
            QueryResult queryResult = new QueryResult();
            queryResult.setErrorMsg("Study identifier not found");
            return createOkResponse(queryResult);
        }

        BasicDBObject id = (BasicDBObject) idQueryResult.getResult().get(0);
        QueryResult finalResult = variantSourceDbAdaptor.getAllSourcesByStudyId(id.getString(PVSDBObjectToVariantSourceConverter.STUDYID_FIELD), queryOptions);
        finalResult.setDbTime(finalResult.getDbTime() + idQueryResult.getDbTime());
        return createOkResponse(finalResult);
    }

    @GET
    @Path("/{study}/view")
    @Produces("application/json")
    @ApiOperation(value = "Get Study Info")
    public Response getStudy(@ApiParam(value = "study") @PathParam("study") String study) {
        QueryResult idQueryResult = studyMongoDbAdaptor.findStudyNameOrStudyId(study, queryOptions);
        if (idQueryResult.getNumResults() == 0) {
            QueryResult queryResult = new QueryResult();
            queryResult.setErrorMsg("Study identifier not found");
            return createOkResponse(queryResult);
        }

        BasicDBObject id = (BasicDBObject) idQueryResult.getResult().get(0);
        QueryResult finalResult = studyMongoDbAdaptor.getStudyById(id.getString(PVSDBObjectToVariantSourceConverter.STUDYID_FIELD), queryOptions);
        finalResult.setDbTime(finalResult.getDbTime() + idQueryResult.getDbTime());
        return createOkResponse(finalResult);
    }

}
