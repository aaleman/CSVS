package org.babelomics.csvs.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Lists;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import org.babelomics.csvs.lib.io.CSVSQueryManager;
import org.babelomics.csvs.lib.ws.QueryResponse;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.Morphia;
import org.opencb.datastore.core.ObjectMap;
import org.opencb.datastore.core.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@Path("/")
public class CSVSWSServer {

    protected Logger logger = LoggerFactory.getLogger(this.getClass());
    protected static Properties properties;
//    protected static Config config;

    protected String version;
    protected UriInfo uriInfo;
    protected String sessionIp;

    protected static ObjectWriter jsonObjectWriter;
    protected static ObjectMapper jsonObjectMapper;

    @DefaultValue("json")
    @QueryParam("of")
    protected String outputFormat;

    static final CSVSQueryManager qm;

    static final Datastore datastore;

    static {

        InputStream is = CSVSWSServer.class.getClassLoader().getResourceAsStream("csvs.properties");
        properties = new Properties();

        try {
            properties.load(is);

        } catch (IOException e) {
            System.out.println("Error loading properties");
            System.out.println(e.getMessage());
            e.printStackTrace();
        }

        jsonObjectMapper = new ObjectMapper();
        jsonObjectWriter = jsonObjectMapper.writer();

        Morphia morphia = new Morphia();
        morphia.mapPackage("org.babelomics.csvs.lib.models");

        String user = properties.getProperty("CSVS.DB.USER", "");
        String pass = properties.getProperty("CSVS.DB.PASS", "");
        String host = properties.getProperty("CSVS.DB.HOST", "localhost");
        String database = properties.getProperty("CSVS.DB.DATABASE", "csvs");
        int port = Integer.parseInt(properties.getProperty("CSVS.DB.PORT", "27017"));

        System.out.println(properties);

        MongoClient mongoClient;
        if (user.equals("") && pass.equals("")) {
            mongoClient = new MongoClient(host);
        } else {
            MongoCredential credential = MongoCredential.createCredential(user, database, pass.toCharArray());
            mongoClient = new MongoClient(new ServerAddress(host, port), Arrays.asList(credential));
        }

        datastore = morphia.createDatastore(mongoClient, database);

        qm = new CSVSQueryManager(datastore);

    }

    public CSVSWSServer(@PathParam("version") String version, @Context UriInfo uriInfo, @Context HttpServletRequest httpServletRequest) throws IOException {

        this.version = version;
        this.uriInfo = uriInfo;
        logger.debug(uriInfo.getRequestUri().toString());

        this.sessionIp = httpServletRequest.getRemoteAddr();
    }


    protected Response createErrorResponse(Object o) {
        System.out.println("ERROR");
        System.out.println("o.toString() = " + o.toString());
        QueryResult<ObjectMap> result = new QueryResult();
        result.setErrorMsg(o.toString());
//        QueryResponse qr = createQueryResponse(result);
        return createOkResponse(null);
    }

    protected Response createOkResponse(QueryResponse qr) {

        switch (outputFormat.toLowerCase()) {
            case "json":
                return createJsonResponse(qr);
            default:
                return buildResponse(Response.ok());
        }


    }

    protected QueryResponse createQueryResponse(Object obj) {
        QueryResponse queryResponse = new QueryResponse();

        List res;
        if (obj instanceof Iterable) {
            res = Lists.newArrayList((Iterable) obj);
        } else {
            res = new ArrayList<>();
            res.add(obj);
        }
        queryResponse.setResult(res);

        return queryResponse;
    }

    protected Response createJsonResponse(Object object) {
        try {
            return buildResponse(Response.ok(jsonObjectWriter.writeValueAsString(object), MediaType.APPLICATION_JSON_TYPE));
        } catch (JsonProcessingException e) {
            return createErrorResponse("Error parsing QueryResponse object:\n" + Arrays.toString(e.getStackTrace()));
        }
    }

    //Response methods
    protected Response createOkResponse(Object o1, MediaType o2) {
        return buildResponse(Response.ok(o1, o2));
    }

    protected Response createOkResponse(Object o1, MediaType o2, String fileName) {
        return buildResponse(Response.ok(o1, o2).header("content-disposition", "attachment; filename =" + fileName));
    }

    protected Response buildResponse(ResponseBuilder responseBuilder) {
        return responseBuilder.header("Access-Control-Allow-Origin", "*").header("Access-Control-Allow-Headers", "x-requested-with, content-type").build();
    }
}
