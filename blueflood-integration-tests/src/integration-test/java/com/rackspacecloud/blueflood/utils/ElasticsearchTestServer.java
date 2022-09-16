package com.rackspacecloud.blueflood.utils;

import com.github.tlrx.elasticsearch.test.EsSetup;
import com.rackspacecloud.blueflood.IntegrationTestConfig;
import com.rackspacecloud.blueflood.service.Configuration;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Manages embedded Elasticsearch servers for tests. An instance of this class manages its own, separate Elasticsearch.
 * In theory, multiple instances could be active simultaneously, but at the moment, the tlrx implementation always binds
 * to port 9200, and that's where tests know to find it. To run concurrent instances, that would have to be fixed. The
 * server should bind a random, available port, and each test should ask the instance of this class where to find it.
 *
 * Complication on that last: Blueflood's use of static initialization for lots of internals means that dynamic ports
 * don't work. Some test might start a server on a dynamic port, but some other class has already been initialized and
 * read the original configuration value of the port, so it's looking in the wrong place. It's simpler to just be sure
 * to bind the port(s) that the existing tests know to look for.
 */
public class ElasticsearchTestServer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchTestServer.class);

    private final String esInitMethod =
            Configuration.getInstance().getStringProperty(IntegrationTestConfig.IT_ELASTICSEARCH_TEST_METHOD);
    private final String testContainersEsVersion =
            Configuration.getInstance().getStringProperty(IntegrationTestConfig.IT_ELASTICSEARCH_CONTAINER_VERSION);

    private ElasticsearchContainer elasticsearchContainer;
    private EsSetup esSetup;
    /**
     * An http client, handy for talking to Elasticsearch. Keeping one on hand is more efficient than creating one every
     * time you need it.
     */
    private final CloseableHttpClient client = HttpClientBuilder.create().build();
    private static final ElasticsearchTestServer INSTANCE = new ElasticsearchTestServer();

    public static final ElasticsearchTestServer getInstance() {
        return INSTANCE;
    }

    /**
     * Starts an in-memory Elasticsearch that's configured for Blueflood. If such a server is already running, this is a
     * no-op. The configuration mimics that found in init-es.sh as closely as I can figure out how to.
     */
    public void ensureStarted() {
        if (esInitMethod.equals("TEST_CONTAINERS")) {
            if (elasticsearchContainer == null || !elasticsearchContainer.isRunning()) {
                startTestContainer();
            }
        } else if (esInitMethod.equals("TLRX")) {
            if (esSetup == null) {
                startTlrx();
            }
        } else if (esInitMethod.equals("EXTERNAL")) {
            // Do nothing! You have to manage Elasticsearch your own self!
            log.info("Using external Elasticsearch");
        } else {
            throw new IllegalStateException("Illegal value set for Elasticsearch init in tests: " + esInitMethod);
        }
    }

    /**
     * Resets the test Elasticsearch instance by deleting all indexes and re-initializing it. This is a somewhat
     * expensive process in terms of time, so it's better that tests use random data and avoid conflicts. Older tests
     * weren't written that way, though.
     */
    public void reset() {
        long start = System.currentTimeMillis();
        try {
            HttpDelete delete = new HttpDelete("http://localhost:9200/_all");
            CloseableHttpResponse deleteResponse = client.execute(delete);
            String body = EntityUtils.toString(deleteResponse.getEntity());
            EntityUtils.consume(deleteResponse.getEntity());
            if (deleteResponse.getStatusLine().getStatusCode() != 200) {
                throw new IllegalStateException("Couldn't delete indexes from test Elasticsearch: " + body);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't delete indexes from test Elasticsearch", e);
        }
        initIt();
        long elapsed = System.currentTimeMillis() - start;
        StackTraceElement callerElement = Thread.currentThread().getStackTrace()[2];
        log.info("Elasticsearch reset took {} ms; called by {}.{}:{}", new Object[] {
                elapsed, callerElement.getClassName(), callerElement.getMethodName(), callerElement.getLineNumber()});
    }

    public void startTestContainer() {
        // Starting with "as compatible" seems to generally work. Why/when do we need to switch to "compatible" images?
        DockerImageName myImage = DockerImageName.parse("elasticsearch:" + testContainersEsVersion)
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");
        elasticsearchContainer = new ElasticsearchContainer(myImage);
        elasticsearchContainer.setPortBindings(Arrays.asList("9200:9200", "9300:9300"));
        elasticsearchContainer.start();
        initIt();
    }

    public void startTlrx() {
        esSetup = new EsSetup();
        // It seems that this library doesn't actually start Elasticsearch until you do something with it, so...
        esSetup.execute(EsSetup.createIndex("init_me"));
        // While this was built for test containers, we can init the tlrx instance the same way.
        initIt();
    }

    private void initIt() {
        String initScript;
        if (testContainersEsVersion.startsWith("1.")) {
            initScript = "init-es.sh";
        } else if (testContainersEsVersion.startsWith("6.")) {
            initScript = "init-es-6/init-es.sh";
        } else {
            throw new IllegalStateException("I don't know which ES init script to use for ES version "
                    + testContainersEsVersion);
        }
        try {
            initIt(initScript);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to run Elasticsearch init script", e);
        }
    }

    private void initIt(String whichScript) throws IOException, InterruptedException {
        // Find the init script, which lives over in the elasticsearch module
        String resourceInThisModule = getClass().getResource("/blueflood.properties").getFile();
        String thisModuleResourcesDir = FilenameUtils.getFullPath(resourceInThisModule);
        String esResourcesDir = thisModuleResourcesDir + "../../../blueflood-elasticsearch/src/main/resources/";
        String initScript = FilenameUtils.concat(esResourcesDir, whichScript);
        String command = initScript + " -u localhost:9200";
        log.info("Initialize Elasticsearch for tests with '" + command + "'");
        Process process = Runtime.getRuntime().exec(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOUtils.copy(process.getInputStream(), output);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Elasticsearch init script exited with non-zero status; exit=" + exit +
                    "; stdout:\n" + output);
        }
    }

    /**
     * Stops the in-memory Elasticsearch managed by this object. It's expected, though unproven, that this would release
     * all resources in use by that Elasticsearch.
     */
    public void stop() {
        try {
            client.close();
        } catch (IOException e) {
            log.warn("Failed to close the HttpClient", e);
        }
        if (esInitMethod.equals("TEST_CONTAINERS")) {
            elasticsearchContainer.stop();
            elasticsearchContainer = null;
        } else if (esInitMethod.equals("TLRX")) {
            esSetup.terminate();
            esSetup = null;
        } else if (esInitMethod.equals("EXTERNAL")) {
            // Do nothing! You have to manage Elasticsearch your own self!
            log.info("Done with external Elasticsearch");
        } else {
            throw new IllegalStateException("Illegal value set for Elasticsearch init in tests: " + esInitMethod);
        }
    }
}
