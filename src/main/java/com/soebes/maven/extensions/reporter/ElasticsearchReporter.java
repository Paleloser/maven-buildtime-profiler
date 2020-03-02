package com.soebes.maven.extensions.reporter;

import java.io.IOException;
import java.util.Date;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.maven.execution.MavenExecutionResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;

import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

public class ElasticsearchReporter {

  private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

  private final String index;

  private final String address;

  private final int port;

  private boolean reachable;

  private String secret;

  private RestHighLevelClient client;

  public ElasticsearchReporter(String address, int port, String index, String secret) {
    this.index = index;
    this.address = address;
    this.port = port;
    this.secret = secret;
    initClient();
  }

  private void initClient() {
    RestClientBuilder builder = RestClient.builder(new HttpHost(address, this.port, "https"));

    builder.setHttpClientConfigCallback(clientBuilder ->
    {
      try
      {
        clientBuilder.setSSLContext(new SSLContextBuilder().loadTrustMaterial(null, (TrustStrategy) (arg0, arg1) -> true).build());
      }
      catch (Exception e)
      {
        LOGGER.warn("Error configuring ssl context: {}", e.getMessage());
      }

      return clientBuilder.setSSLHostnameVerifier((s, sslSession) -> true);
    });

    if (this.secret != null)
    {
      Header[] defHeaders = new Header[]{
          new BasicHeader("Authorization", "ApiKey " + this.secret)
      };

      builder.setDefaultHeaders(defHeaders);
    }

    this.client = new RestHighLevelClient(builder);
    this.reachable = initIndex();
  }

  private boolean initIndex()
  {
    try
    {
      if (!isIndex())
      {
        createIndex();
      }
      return true;
    }
    catch (IOException e)
    {
      LOGGER.warn("Couldn't init index at {}: {}", address, e.getMessage());
    }
    return false;
  }

  private boolean isIndex() throws IOException
  {
    GetIndexRequest request = new GetIndexRequest(index.toLowerCase());
    return client.indices().exists(request, RequestOptions.DEFAULT);
  }

  private void createIndex() throws IOException
  {
    JSONObject profileMapping = new JSONObject();
    JSONObject properties = new JSONObject();
    JSONObject date = new JSONObject();

    date.put("type", "date");
    properties.put("date", date);
    profileMapping.put("properties", properties);

    CreateIndexRequest request = new CreateIndexRequest(index.toLowerCase());
    request.mapping(profileMapping.toString(), XContentType.JSON);

    client.indices().create(request, RequestOptions.DEFAULT);
  }

  public void sendTelemetry(MavenExecutionResult event, JSONObject profiling, String[] ignoreReportFields)
  {
    removeJSONFields(profiling, ignoreReportFields);

    JSONObject document = new JSONObject();
    document.put("profiling", profiling);

    JSONObject project = new JSONObject();
    project.put("id", event.getProject().getId());
    project.put("groupId", event.getProject().getGroupId());
    project.put("artifactId", event.getProject().getArtifactId());
    project.put("version", event.getProject().getVersion());

    JSONObject parent = new JSONObject();
    parent.put("groupId", event.getProject().getParent().getGroupId());
    parent.put("artifactId", event.getProject().getParent().getArtifactId());
    parent.put("version", event.getProject().getParent().getVersion());

    project.put("parent", parent);
    document.put("project", project);

    document.put("system", getSystemTelemetry());
    document.put("version", this.getClass().getPackage().getImplementationVersion());
    document.put("git", getGitInfo());

    index(document);
  }

  private JSONObject getSystemTelemetry()
  {
    SystemInfo sysInfo = new SystemInfo();

    HardwareAbstractionLayer hardware = sysInfo.getHardware();
    OperatingSystem osInfo = sysInfo.getOperatingSystem();

    JSONObject system = new JSONObject();

    JSONObject os = new JSONObject();
    os.put("arch", System.getProperty("os.arch"));
    os.put("name", System.getProperty("os.name"));
    os.put("version", System.getProperty("os.version"));
    os.put("build", osInfo.getVersionInfo().getBuildNumber());

    JSONObject java = new JSONObject();

    JSONObject javaVm = new JSONObject();
    javaVm.put("specification", System.getProperty("java.vm.specification.version"));
    javaVm.put("version", System.getProperty("java.vm.version"));
    javaVm.put("vendor", System.getProperty("java.vm.vendor"));

    JSONObject javaVmMemory = new JSONObject();
    javaVmMemory.put("total", Runtime.getRuntime().totalMemory());
    javaVmMemory.put("available", Runtime.getRuntime().freeMemory());
    javaVmMemory.put("max", Runtime.getRuntime().maxMemory());

    javaVm.put("memory", javaVmMemory);

    java.put("vm", javaVm);
    java.put("runtime", System.getProperty("java.runtime.version"));

    JSONObject processor = new JSONObject();
    processor.put("id", hardware.getProcessor().getProcessorIdentifier().getIdentifier());
    processor.put("name", hardware.getProcessor().getProcessorIdentifier().getName());
    processor.put("logicalProcessors", hardware.getProcessor().getLogicalProcessorCount());
    processor.put("physicalProcessors", hardware.getProcessor().getPhysicalProcessorCount());
    processor.put("frequency", hardware.getProcessor().getMaxFreq());

    JSONObject memory = new JSONObject();
    memory.put("total", hardware.getMemory().getTotal());
    memory.put("available", hardware.getMemory().getAvailable());

    system.put("memory", memory);
    system.put("processor", processor);
    system.put("os", os);
    system.put("java", java);

    return system;
  }

  private JSONObject getGitInfo()
  {
    FileRepositoryBuilder builder = new FileRepositoryBuilder();
    JSONObject git = new JSONObject();

    try
    {
      Repository repository = builder
          .readEnvironment()
          .findGitDir()
          .build();

      Git gitObject = new Git(repository);

      RevCommit commit = gitObject
          .log()
          .add(repository.resolve(Constants.HEAD))
          .setMaxCount(1)
          .call()
          .iterator()
          .next();

      git.put("branch", repository.getBranch());
      git.put("commit", commit.getName());

      return git;
    }
    catch (IOException | GitAPIException e)
    {
      LOGGER.warn("Error getting Repository: {}", e.getMessage());
    }

    return git;
  }

  private void removeJSONFields(JSONObject document, String[] fields) {
    for (String field : fields)
    {
      removeFieldToDocument(document, field);
    }
  }

  private JSONObject removeFieldToDocument(JSONObject document, String field)
  {
    if (field.contains("."))
    {
      String key = field.substring(0, field.indexOf('.'));
      String subkey = field.substring(field.indexOf('.') + 1);

      if (document.has(key))
      {
        document.put(key, removeFieldToDocument((JSONObject) document.get(key), subkey));
      }
    }
    else if (document.has(field))
    {
      document.remove(field);
    }
    return document;
  }

  public void index(JSONObject doc)
  {
    if (doc == null)
    {
      return;
    }

    doc.put("date", new Date().getTime());

    IndexRequest request = new IndexRequest(index.toLowerCase());

    try
    {
      request.source(doc.toString(), XContentType.JSON);
      client.index(request, RequestOptions.DEFAULT);
    }
    catch (IOException e)
    {
      LOGGER.warn("Error indexing document: {}", e.getMessage());
    }
  }

  public boolean isReachable() {
    return reachable;
  }

  private void close()
  {
    try
    {
      client.close();
    }
    catch (IOException e)
    {
      LOGGER.warn("Error closing elasticsearch connection: {}", e.getMessage());
    }
  }
}
