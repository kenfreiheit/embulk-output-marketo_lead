package org.embulk.output.marketo_lead;

//import java.util.Calendar;
import java.util.List;
//import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import java.net.ConnectException;

import java.io.IOException;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.BadRequestException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.TaskReport;
import org.embulk.spi.Exec;
import org.embulk.spi.Schema;
import org.embulk.spi.Column;

import org.embulk.base.restclient.RestClientOutputPluginDelegate;
import org.embulk.base.restclient.RestClientOutputTaskBase;
import org.embulk.base.restclient.jackson.JacksonServiceRequestMapper;
import org.embulk.base.restclient.jackson.JacksonServiceRequestMapper.Builder;
import org.embulk.base.restclient.jackson.JacksonTaskReportRecordBuffer;
import org.embulk.base.restclient.jackson.JacksonTopLevelValueLocator;
import org.embulk.base.restclient.jackson.scope.JacksonDirectIntegerScope;
import org.embulk.base.restclient.jackson.scope.JacksonDirectStringScope;
import org.embulk.base.restclient.record.RecordBuffer;

import org.embulk.util.retryhelper.jaxrs.JAXRSClientCreator;
import org.embulk.util.retryhelper.jaxrs.JAXRSRetryHelper;
import org.embulk.util.retryhelper.jaxrs.JAXRSSingleRequester;
import org.embulk.util.retryhelper.jaxrs.StringJAXRSResponseEntityReader;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.Execution;

import org.slf4j.Logger;


public class MarketoLeadOutputPluginDelegate
    implements RestClientOutputPluginDelegate<MarketoLeadOutputPluginDelegate.PluginTask>
{
    protected static List<Column> schemaColumns;

    public interface PluginTask
            extends RestClientOutputTaskBase
    {
        @Config("account_id")
        public String getAccountId();

        @Config("client_id")
        public String getClientId();

        @Config("client_secret")
        public String getClientSecret();

        @Config("action")
        public String getAction();

        @Config("lookupfield")
        public String getLookupField();

        @Config("maximum_retries")
        @ConfigDefault("7")
        public int getMaximumRetries();

        @Config("initial_retry_interval_millis")
        @ConfigDefault("1000")
        public int getInitialRetryIntervalMillis();

        @Config("maximum_retry_interval_millis")
        @ConfigDefault("60000")
        public int getMaximumRetryIntervalMillis();
    }

    @Override  // Overridden from |OutputTaskValidatable|
    public void validateOutputTask(PluginTask task, Schema embulkSchema, int taskCount)
    {
        schemaColumns = embulkSchema.getColumns();
    }

    // Bind Input column and Output column
    @Override  // Overridden from |ServiceRequestMapperBuildable|
    public JacksonServiceRequestMapper buildServiceRequestMapper(PluginTask task)
    {
        Builder serviceRequestMapperBulder = JacksonServiceRequestMapper.builder();

        schemaColumns.forEach( col -> {
            switch (col.getType().getName()) {
                case "string":
                    serviceRequestMapperBulder.add(new JacksonDirectStringScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
                    break;
                case "long":
                    serviceRequestMapperBulder.add(new JacksonDirectIntegerScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
                    break;
                case "double":
                    serviceRequestMapperBulder.add(new JacksonDirectIntegerScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
                    break;
                case "boolean":
                    serviceRequestMapperBulder.add(new JacksonDirectStringScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
                    break;
                case "timestamp":
                    serviceRequestMapperBulder.add(new JacksonDirectStringScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
                    break;
                default:
                    serviceRequestMapperBulder.add(new JacksonDirectStringScope(col.getName()), new JacksonTopLevelValueLocator(col.getName()));
            }
        });
    
        return serviceRequestMapperBulder.build();
    }

    @Override  // Overridden from |RecordBufferBuildable|
    public RecordBuffer buildRecordBuffer(PluginTask task, Schema schema, int taskIndex)
    {
        return new JacksonTaskReportRecordBuffer("input");
    }

    // Create JSON
    @Override  // Overridden from |EmbulkDataEgestable|
    public ConfigDiff egestEmbulkData(final PluginTask task,
                                      Schema schema,
                                      int taskIndex,
                                      List<TaskReport> taskReports)
    {
        ArrayNode records = JsonNodeFactory.instance.arrayNode();
        for (TaskReport taskReport : taskReports) {
            records.addAll(JacksonTaskReportRecordBuffer.resumeFromTaskReport(taskReport, "input"));
        }

        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("action", task.getAction());
        json.put("lookupField", task.getLookupField());
        json.set("input", records);

        try (JAXRSRetryHelper retryHelper = new JAXRSRetryHelper(
                 task.getMaximumRetries(),
                 task.getInitialRetryIntervalMillis(),
                 task.getMaximumRetryIntervalMillis(),
                 new JAXRSClientCreator() {
                     @Override
                     public javax.ws.rs.client.Client create() {
                         return javax.ws.rs.client.ClientBuilder.newBuilder().build();
                     }
                 })) {
            push(retryHelper, json, task);
        }

        return Exec.newConfigDiff();
    }

    // Reequest API
    private String push(JAXRSRetryHelper retryHelper, final JsonNode json, final PluginTask task)
    {
        String accessToken = getAccessToken(task);

        return retryHelper.requestWithRetry(
            new StringJAXRSResponseEntityReader(),
            new JAXRSSingleRequester() {
                @Override
                public Response requestOnce(javax.ws.rs.client.Client client)
                {
                    
                    //WebTarget target = client.target("http://localhost:3000")
                    //                     .path("/samples")
                    //                     .queryParam("access_token", accessToken);
                                        
                    WebTarget target = client.target("https://" + task.getAccountId() + ".mktorest.com")
                                             .path("/rest/v1/leads.json") 
                                             .queryParam("access_token", accessToken);
                    
                    return target.request()
                                 .post(Entity.<String>entity(json.toString(), MediaType.APPLICATION_JSON));
                }

                @Override
                public boolean isResponseStatusToRetry(javax.ws.rs.core.Response response)
                {
                    return response.getStatus() / 100 != 4;
                }
            });
    }

    private String getAccessToken(final PluginTask task) {

        ClientConfig config = new ClientConfig()
            .property(ClientProperties.CONNECT_TIMEOUT, "5000")
            .property(ClientProperties.READ_TIMEOUT, "3000");

        Client client = ClientBuilder.newClient(config);

        RetryPolicy retryPolicy = new RetryPolicy()
            .retryOn(ConnectException.class)
            .withDelay(1, TimeUnit.SECONDS)
            .withMaxRetries(task.getMaximumRetries());

        WebTarget target = client.target("https://" + task.getAccountId() + ".mktorest.com")
                             .path("/identity/oauth/token") 
                             .queryParam("grant_type", "client_credentials")
                             .queryParam("client_id", task.getClientId())
                             .queryParam("client_secret", task.getClientSecret());

        String accessToken = "";

        try {
            logger.info("Post to " + target.getUri().toString().replace(task.getClientId(), "XXXXX").replace(task.getClientSecret(), "XXXXX"));

            Execution execution = new Execution(retryPolicy);
            while (!execution.isComplete()) {
                try {
                    Response response = Failsafe.with(retryPolicy).get(() -> target.request().get(Response.class));
                    String result = response.readEntity(String.class);
                        
                    ObjectMapper objectMapper = new ObjectMapper();
                    Map<String,Object> map = objectMapper.readValue(result,  new TypeReference<Map<String, Object>>() {});
                                        
                    accessToken = map.get("access_token").toString();
                    execution.complete();
                    logger.info("Status code: " + Integer.toString(response.getStatus()) + " " + response.getStatusInfo().getReasonPhrase());

                } catch (ConnectException e) {
                    execution.recordFailure(e);
                }
            }

        } catch (BadRequestException e) {
            logger.error(e.getMessage());
        } catch (IOException e) {
            logger.error(e.getMessage());
        }

        return accessToken;
    }

    private final Logger logger = Exec.getLogger(MarketoLeadOutputPluginDelegate.class);
}
