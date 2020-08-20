/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.impl;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.request.RequestWriter;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.SuppressForbidden;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;
import org.junit.Test;

public class Http2SolrClientTest extends SolrJettyTestBase {

  private static final String EXPECTED_USER_AGENT = "Solr[" + Http2SolrClient.class.getName() + "] 2.0";


  public static class DebugServlet extends HttpServlet {
    public static void clear() {
      lastMethod = null;
      headers = null;
      parameters = null;
      errorCode = null;
      queryString = null;
      cookies = null;
    }

    public static Integer errorCode = null;
    public static String lastMethod = null;
    public static HashMap<String,String> headers = null;
    public static Map<String,String[]> parameters = null;
    public static String queryString = null;
    public static javax.servlet.http.Cookie[] cookies = null;

    public static void setErrorCode(Integer code) {
      errorCode = code;
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "delete";
      recordRequest(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "get";
      recordRequest(req, resp);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "head";
      recordRequest(req, resp);
    }

    private void setHeaders(HttpServletRequest req) {
      Enumeration<String> headerNames = req.getHeaderNames();
      headers = new HashMap<>();
      while (headerNames.hasMoreElements()) {
        final String name = headerNames.nextElement();
        headers.put(name.toLowerCase(Locale.getDefault()), req.getHeader(name));
      }
    }

    @SuppressForbidden(reason = "fake servlet only")
    private void setParameters(HttpServletRequest req) {
      parameters = req.getParameterMap();
    }

    private void setQueryString(HttpServletRequest req) {
      queryString = req.getQueryString();
    }

    private void setCookies(HttpServletRequest req) {
      javax.servlet.http.Cookie[] ck = req.getCookies();
      cookies = req.getCookies();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "post";
      recordRequest(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "put";
      recordRequest(req, resp);
    }

    private void recordRequest(HttpServletRequest req, HttpServletResponse resp) {
      setHeaders(req);
      setParameters(req);
      setQueryString(req);
      setCookies(req);
      if (null != errorCode) {
        try {
          resp.sendError(errorCode);
        } catch (IOException e) {
          throw new RuntimeException("sendError IO fail in DebugServlet", e);
        }
      }
    }
  }

  @BeforeClass
  public static void beforeTest() throws Exception {
    JettyConfig jettyConfig = JettyConfig.builder()
        .withServlet(new ServletHolder(BasicHttpSolrClientTest.RedirectServlet.class), "/redirect/*")
        .withServlet(new ServletHolder(BasicHttpSolrClientTest.SlowServlet.class), "/slow/*")
        .withServlet(new ServletHolder(DebugServlet.class), "/debug/*")
        .withSSLConfig(sslConfig.buildServerSSLConfig())
        .build();
    createAndStartJetty(legacyExampleCollection1SolrHome(), jettyConfig);
  }

  private Http2SolrClient getHttp2SolrClient(String url, int connectionTimeOut, int socketTimeout) {
    return new Http2SolrClient.Builder(url)
        .connectionTimeout(connectionTimeOut)
        .idleTimeout(socketTimeout)
        .build();
  }

  private Http2SolrClient getHttp2SolrClient(String url) {
    return new Http2SolrClient.Builder(url)
        .build();
  }

  @Test
  public void testTimeout() throws Exception {
    SolrQuery q = new SolrQuery("*:*");
    try(Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/slow/foo", DEFAULT_CONNECTION_TIMEOUT, 2000)) {
      client.query(q, SolrRequest.METHOD.GET);
      fail("No exception thrown.");
    } catch (SolrServerException e) {
      assertTrue(e.getMessage().contains("timeout") || e.getMessage().contains("Timeout"));
    }

  }

  @Test
  public void test0IdleTimeout() throws Exception {
    SolrQuery q = new SolrQuery("*:*");
    try(Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo", DEFAULT_CONNECTION_TIMEOUT, 0)) {
      try {
        client.query(q, SolrRequest.METHOD.GET);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}
    }

  }

  /**
   * test that SolrExceptions thrown by HttpSolrClient can
   * correctly encapsulate http status codes even when not on the list of
   * ErrorCodes solr may return.
   */
  @Test
  public void testSolrExceptionCodeNotFromSolr() throws IOException, SolrServerException {
    final int status = 527;
    assertEquals(status + " didn't generate an UNKNOWN error code, someone modified the list of valid ErrorCode's w/o changing this test to work a different way",
        SolrException.ErrorCode.UNKNOWN, SolrException.ErrorCode.getErrorCode(status));

    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo")) {
      DebugServlet.setErrorCode(status);
      try {
        SolrQuery q = new SolrQuery("foo");
        client.query(q, SolrRequest.METHOD.GET);
        fail("Didn't get excepted exception from oversided request");
      } catch (SolrException e) {
        assertEquals("Unexpected exception status code", status, e.code());
      }
    } finally {
      DebugServlet.clear();
    }
  }

  @Test
  public void testQuery() throws Exception {
    DebugServlet.clear();
    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo")) {
      SolrQuery q = new SolrQuery("foo");
      q.setParam("a", "\u1234");
      try {
        client.query(q, SolrRequest.METHOD.GET);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      //default method
      assertEquals("get", DebugServlet.lastMethod);
      //agent
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      //default wt
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      //default version
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      //agent
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      //content-type
      assertEquals(null, DebugServlet.headers.get("content-type"));
      //param encoding
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

      //POST
      DebugServlet.clear();
      try {
        client.query(q, SolrRequest.METHOD.POST);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("post", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));

      //PUT
      DebugServlet.clear();
      try {
        client.query(q, SolrRequest.METHOD.PUT);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("put", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));

      //XML/GET
      client.setParser(new XMLResponseParser());
      DebugServlet.clear();
      try {
        client.query(q, SolrRequest.METHOD.GET);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("get", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));

      //XML/POST
      client.setParser(new XMLResponseParser());
      DebugServlet.clear();
      try {
        client.query(q, SolrRequest.METHOD.POST);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("post", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));

      client.setParser(new XMLResponseParser());
      DebugServlet.clear();
      try {
        client.query(q, SolrRequest.METHOD.PUT);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("put", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals("application/x-www-form-urlencoded", DebugServlet.headers.get("content-type"));
    }

  }

  @Test
  public void testDelete() throws Exception {
    DebugServlet.clear();
    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo")) {
      try {
        client.deleteById("id");
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      //default method
      assertEquals("post", DebugServlet.lastMethod);
      //agent
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      //default wt
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      //default version
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      //agent
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));

      //XML
      client.setParser(new XMLResponseParser());
      try {
        client.deleteByQuery("*:*");
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      assertEquals("post", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
    }

  }

  @Test
  public void testGetById() throws Exception {
    DebugServlet.clear();
    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo")) {
      Collection<String> ids = Collections.singletonList("a");
      try {
        client.getById("a");
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      try {
        client.getById(ids, null);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      try {
        client.getById("foo", "a");
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}

      try {
        client.getById("foo", ids, null);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}
    }
  }

  @Test
  public void testUpdate() throws Exception {
    testUpdate(true);
    testUpdate(false);
  }

  private void testUpdate(boolean useAsyncClient) throws Exception {
    DebugServlet.clear();
    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString() + "/debug/foo")) {
      UpdateRequest req = new UpdateRequest();
      req.add(new SolrInputDocument());
      req.setParam("a", "\u1234");
      makeRequest(client, req, useAsyncClient);

      //default method
      assertEquals("post", DebugServlet.lastMethod);
      //agent
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      //default wt
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      //default version
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      //content type
      assertEquals("application/javabin", DebugServlet.headers.get("content-type"));
      //parameter encoding
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

      //XML response and writer
      client.setParser(new XMLResponseParser());
      client.setRequestWriter(new RequestWriter());
      makeRequest(client, req, useAsyncClient);

      assertEquals("post", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals("application/xml; charset=UTF-8", DebugServlet.headers.get("content-type"));
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

      //javabin request
      client.setParser(new BinaryResponseParser());
      client.setRequestWriter(new BinaryRequestWriter());
      DebugServlet.clear();
      makeRequest(client, req, useAsyncClient);

      assertEquals("post", DebugServlet.lastMethod);
      assertEquals(EXPECTED_USER_AGENT, DebugServlet.headers.get("user-agent"));
      assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
      assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
      assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
      assertEquals(client.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
      assertEquals("application/javabin", DebugServlet.headers.get("content-type"));
      assertEquals(1, DebugServlet.parameters.get("a").length);
      assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    }

  }

  @Test
  public void testRedirect() throws Exception {
    final String clientUrl = jetty.getBaseUrl().toString() + "/redirect/foo";
    try (Http2SolrClient client = getHttp2SolrClient(clientUrl)) {
      SolrQuery q = new SolrQuery("*:*");
      // default = false
      try {
        client.query(q);
        fail("Should have thrown an exception.");
      } catch (SolrServerException e) {
        assertTrue(e.getMessage().contains("redirect"));
      }

      client.setFollowRedirects(true);
      client.query(q);

      //And back again:
      client.setFollowRedirects(false);
      try {
        client.query(q);
        fail("Should have thrown an exception.");
      } catch (SolrServerException e) {
        assertTrue(e.getMessage().contains("redirect"));
      }
    }

  }

  @Test
  public void testCollectionParameters() throws IOException, SolrServerException {

    try (Http2SolrClient client = getHttp2SolrClient(jetty.getBaseUrl().toString())) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", "collection");
      client.add("collection1", doc);
      client.commit("collection1");

      assertEquals(1, client.query("collection1", new SolrQuery("id:collection")).getResults().getNumFound());
    }

    final String collection1Url = jetty.getBaseUrl().toString() + "/collection1";
    try (Http2SolrClient client = getHttp2SolrClient(collection1Url)) {
      assertEquals(1, client.query(new SolrQuery("id:collection")).getResults().getNumFound());
    }

  }

  private Set<String> setOf(String... keys) {
    Set<String> set = new TreeSet<>();
    if (keys != null) {
      Collections.addAll(set, keys);
    }
    return set;
  }

  private void setReqParamsOf(UpdateRequest req, String... keys) {
    if (keys != null) {
      for (String k : keys) {
        req.setParam(k, k+"Value");
      }
    }
  }

  private void verifyServletState(Http2SolrClient client,
                                  @SuppressWarnings({"rawtypes"})SolrRequest request) {
    // check query String
    Iterator<String> paramNames = request.getParams().getParameterNamesIterator();
    while (paramNames.hasNext()) {
      String name = paramNames.next();
      String [] values = request.getParams().getParams(name);
      if (values != null) {
        for (String value : values) {
          boolean shouldBeInQueryString = client.getQueryParams().contains(name)
              || (request.getQueryParams() != null && request.getQueryParams().contains(name));
          assertEquals(shouldBeInQueryString, DebugServlet.queryString.contains(name + "=" + value));
          // in either case, it should be in the parameters
          assertNotNull(DebugServlet.parameters.get(name));
          assertEquals(1, DebugServlet.parameters.get(name).length);
          assertEquals(value, DebugServlet.parameters.get(name)[0]);
        }
      }
    }
  }

  @Test
  public void testQueryString() throws Exception {
    testQueryString(true);
    testQueryString(false);
  }


  private void testQueryString(boolean useAsyncClient) throws Exception {

    final String clientUrl = jetty.getBaseUrl().toString() + "/debug/foo";
    try(Http2SolrClient client = getHttp2SolrClient(clientUrl)) {
      // test without request query params
      DebugServlet.clear();
      client.setQueryParams(setOf("serverOnly"));
      UpdateRequest req = new UpdateRequest();
      setReqParamsOf(req, "serverOnly", "notServer");
      makeRequest(client, req, useAsyncClient);
      verifyServletState(client, req);

      // test without server query params
      DebugServlet.clear();
      client.setQueryParams(setOf());
      req = new UpdateRequest();
      req.setQueryParams(setOf("requestOnly"));
      setReqParamsOf(req, "requestOnly", "notRequest");
      makeRequest(client, req, useAsyncClient);
      verifyServletState(client, req);

      // test with both request and server query params
      DebugServlet.clear();
      req = new UpdateRequest();
      client.setQueryParams(setOf("serverOnly", "both"));
      req.setQueryParams(setOf("requestOnly", "both"));
      setReqParamsOf(req, "serverOnly", "requestOnly", "both", "neither");
      makeRequest(client, req, useAsyncClient);
      verifyServletState(client, req);

      // test with both request and server query params with single stream
      DebugServlet.clear();
      req = new UpdateRequest();
      req.add(new SolrInputDocument());
      client.setQueryParams(setOf("serverOnly", "both"));
      req.setQueryParams(setOf("requestOnly", "both"));
      setReqParamsOf(req, "serverOnly", "requestOnly", "both", "neither");
      makeRequest(client, req, useAsyncClient);
      // NOTE: single stream requests send all the params
      // as part of the query string.  So add "neither" to the request
      // so it passes the verification step.
      req.setQueryParams(setOf("requestOnly", "both", "neither"));
      verifyServletState(client, req);
    }
  }

  @Test
  public void testGetDefaultSslContextFactory() {
    assertNull(Http2SolrClient.getDefaultSslContextFactory().getEndpointIdentificationAlgorithm());

    System.setProperty("solr.jetty.ssl.verifyClientHostName", "HTTPS");
    SslContextFactory.Client sslContextFactory = Http2SolrClient.getDefaultSslContextFactory();
    assertEquals("HTTPS", sslContextFactory.getEndpointIdentificationAlgorithm());
    System.clearProperty("solr.jetty.ssl.verifyClientHostName");
  }

  private void makeRequest(Http2SolrClient client, SolrRequest<?> req, boolean useAsyncClient) throws Exception {
    if (useAsyncClient) {
      try {
        client.requestAsync(req, null).get();
      } catch (ExecutionException e) {}
    } else {
      try {
        client.request(req);
      } catch (BaseHttpSolrClient.RemoteSolrException ignored) {}
    }
  }

  /**
   * Missed tests :
   * - set cookies via interceptor
   * - invariant params
   * - compression
   * - get raw stream
   */
}
