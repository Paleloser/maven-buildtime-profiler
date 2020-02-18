package com.soebes.maven.extensions.reporter;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import org.apache.http.HttpHost;
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

public class ElasticsearchReporter {

  private final Logger LOGGER = LoggerFactory.getLogger( getClass() );

  private final String index;

  private final RestHighLevelClient client;

  public ElasticsearchReporter(String address, int port, String index) {
    this.index = index;
    this.client = new RestHighLevelClient(RestClient.builder(new HttpHost(address, port, "http")));
  }

  public void initIndex()
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
