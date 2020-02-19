package com.soebes.maven.extensions.reporter;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import org.apache.http.HttpHost;
import org.apache.maven.execution.MavenExecutionResult;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

public class ElasticsearchReporter {

  private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

  private final String index;

  private final RestHighLevelClient client;

  public ElasticsearchReporter(String address, int port, String index) {
    this.index = index;
    this.client = new RestHighLevelClient(RestClient.builder(new HttpHost(address, port, "http")));
    initIndex();
  }

  private void initIndex()
  {
    try
    {
      if (!isIndex())
      {
        createIndex();
      }
    }
    catch (IOException e)
    {
      LOGGER.error("Error initializing index: {}", e.getMessage());
    }
  }

  private boolean isIndex() throws IOException
  {
    GetIndexRequest request = new GetIndexRequest(index.toLowerCase());
    return client.indices().exists(request, RequestOptions.DEFAULT);
  }

  private void createIndex() throws IOException
  {
    final String mappingFile = "src/main/resources/profile-mapping.json";

    JSONTokener tokener = new JSONTokener(new InputStreamReader(new FileInputStream(mappingFile)));
    JSONObject profileMapping = new JSONObject(tokener);

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
      LOGGER.error("Error indexing document: {}", e.getMessage());
    }
  }

  private void close()
  {
    try {
      client.close();
    } catch (IOException e) {
      LOGGER.error("Error closing elasticsearch connection: {}", e.getMessage());
    }
  }
}
