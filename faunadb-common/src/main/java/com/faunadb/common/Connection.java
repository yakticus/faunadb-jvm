package com.faunadb.common;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.*;
import org.asynchttpclient.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOError;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;

/**
 * The HTTP Connection adapter for FaunaDB clients.
 * <p>
 * <p>Relies on <a href="https://github.com/AsyncHttpClient/async-http-client">async-http-client</a>
 * for the underlying implementation.
 */

public final class Connection implements AutoCloseable {

  private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10000;
  private static final int DEFAULT_REQUEST_TIMEOUT_MS = 60000;
  private static final int DEFAULT_IDLE_TIMEOUT_MS = 4750;
  private static final int DEFAULT_MAX_RETRIES = 0;
  private static final URL FAUNA_ROOT;

  static {
    try {
      FAUNA_ROOT = new URL("https://rest.faunadb.com");
    } catch (MalformedURLException e) {
      throw new IOError(e); // won't happen
    }
  }

  /**
   * Returns a new {@link Connection.Builder}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for creating an instance of {@link Connection}. Use {@link Connection#builder} to obtain
   * an instance of this builder.
   */
  public static class Builder {

    private URL faunaRoot;
    private String authToken;
    private AsyncHttpClient client;
    private MetricRegistry metricRegistry;

    private Builder() {
    }

    /**
     * Sets the FaunaDB root URL for the built {@link Connection}.
     *
     * @param root the root URL, as a RFC 2396 formatted string. Example: https://rest.faunadb.com
     * @return this {@link Builder} object
     * @throws MalformedURLException if {@code root} is not RFC 2396.
     */
    public Builder withFaunaRoot(String root) throws MalformedURLException {
      this.faunaRoot = new URL(root);
      return this;
    }

    /**
     * Sets the FaunaDB root URL for the built {@link Connection}.
     *
     * @param root the root URL
     * @return this {@link Builder} object
     */
    public Builder withFaunaRoot(URL root) {
      this.faunaRoot = root;
      return this;
    }

    /**
     * Sets the Auth Token that the built {@link Connection} will provide to FaunaDB. This must be provided in order
     * for client to authenticate with FaunaDB.
     *
     * @param token the auth token.
     * @return this {@link Builder} object
     */
    public Builder withAuthToken(String token) {
      this.authToken = token;
      return this;
    }

    /**
     * Sets a custom {@link AsyncHttpClient} implementation that the built {@link Connection} will use. This custom implementation
     * can be provided to control the behavior of the underlying HTTP transport.
     *
     * @param client the custom {@link AsyncHttpClient} instance
     * @return this {@link Builder} object
     */
    public Builder withHttpClient(AsyncHttpClient client) {
      this.client = client;
      return this;
    }

    /**
     * Sets a {@link MetricRegistry} that the {@link Connection} will use to register and track Connection-level statistics.
     *
     * @param registry the MetricRegistry instance.
     * @return this {@link Builder} object
     */
    public Builder withMetrics(MetricRegistry registry) {
      this.metricRegistry = registry;
      return this;
    }

    /**
     * Returns a newly constructed {@link Connection} with configuration based on the settings of this {@link Builder}.
     */
    public Connection build() {
      MetricRegistry registry;
      if (metricRegistry == null)
        registry = new MetricRegistry();
      else
        registry = metricRegistry;

      AsyncHttpClient httpClient;
      if (client == null) {
        httpClient = new DefaultAsyncHttpClient(
          new DefaultAsyncHttpClientConfig.Builder()
            .setConnectTimeout(DEFAULT_CONNECTION_TIMEOUT_MS)
            .setRequestTimeout(DEFAULT_REQUEST_TIMEOUT_MS)
            .setPooledConnectionIdleTimeout(DEFAULT_IDLE_TIMEOUT_MS)
            .setMaxRequestRetry(DEFAULT_MAX_RETRIES)
            .build()
        );
      } else {
        httpClient = client;
      }

      URL root;
      if (faunaRoot == null)
        root = FAUNA_ROOT;
      else
        root = faunaRoot;

      return new Connection(root, authToken, httpClient, registry, new AtomicInteger(1));
    }
  }

  private static final String ASCII = "ASCII";
  private static final String X_FAUNADB_HOST = "X-FaunaDB-Host";
  private static final String X_FAUNADB_BUILD = "X-FaunaDB-Build";

  private final URL faunaRoot;
  private final String authHeader;
  private final AsyncHttpClient client;
  private final MetricRegistry registry;
  private final AtomicInteger refCount;

  private final Logger log = LoggerFactory.getLogger(getClass());
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private Connection(URL faunaRoot, String authToken, AsyncHttpClient client, MetricRegistry registry, AtomicInteger refCount) {
    this.faunaRoot = faunaRoot;
    this.authHeader = generateAuthHeader(authToken);
    this.client = client;
    this.registry = registry;
    this.refCount = refCount;
  }

  /**
   * Creates a new short live connection sharing its underneath {@link AsyncHttpClient}. Queries submited to a
   * session connection will be authenticated with the token provided.
   *
   * @param authToken token that will be used to authenticate requests
   * @return a new {@link Connection}
   */
  public Connection newSessionConnection(String authToken) {
    if (refCount.incrementAndGet() > 1)
      return new Connection(faunaRoot, authToken, client, registry, refCount);
    else
      throw new IllegalStateException("Can not create a session connection from a closed connection");
  }

  /**
   * Releases any resources being held by the HTTP client. Also closes the underlying
   * {@link AsyncHttpClient}.
   */
  @Override
  public void close() {
    try {
      if (closed.compareAndSet(false, true)) {
        if (refCount.decrementAndGet() == 0)
          client.close();
      }
    } catch (IOException e) {
      // DefaultAsyncHttpClient do not throw IOException, we don't need to pollute the API with it
      throw new IllegalStateException("Unexpected error when closing http connection pool", e);
    }
  }

  /**
   * Issues a {@code GET} request with no parameters.
   *
   * @param path the relative path of the resource.
   * @return a {@code ListenableFuture} containing the HTTP Response.
   * @throws IOException if the HTTP request cannot be issued.
   */
  public ListenableFuture<Response> get(String path) throws IOException {
    Request request = new RequestBuilder("GET")
      .setUrl(mkUrl(path))
      .build();

    return performRequest(request);
  }

  /**
   * Issues a {@code GET} request with the provided request parameters.
   *
   * @param path   the relative path of the resource.
   * @param params a map containing the request parameters.
   * @return a {@code ListenableFuture} containing the HTTP response.
   * @throws IOException if the HTTP request cannot be issued.
   */
  public ListenableFuture<Response> get(String path, Map<String, List<String>> params) throws IOException {
    Request request = new RequestBuilder("GET")
      .setUrl(mkUrl(path))
      .setQueryParams(params)
      .build();

    return performRequest(request);
  }

  /**
   * Issues a {@code POST} request with the provided JSON request body.
   *
   * @param path the relative path of the resource.
   * @param body the JSON tree that will be serialized into the request body.
   * @return a {@link ListenableFuture} containing the HTTP response.
   * @throws IOException if the HTTP request cannot be issued.
   */
  public ListenableFuture<Response> post(String path, JsonNode body) throws IOException {
    Request request = new RequestBuilder("POST")
      .setUrl(mkUrl(path))
      .setBody(json.writeValueAsString(body))
      .setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=utf-8")
      .build();

    return performRequest(request);
  }

  /**
   * Issues a {@code PUT} request with the provided JSON request body.
   *
   * @param path the relative path of the resource.
   * @param body the JSON tree that will be serialized into the request body.
   * @return a {@link ListenableFuture} containing the HTTP response.
   * @throws IOException if the HTTP request cannot be issued.
   */
  public ListenableFuture<Response> put(String path, JsonNode body) throws IOException {
    Request request = new RequestBuilder("PUT")
      .setUrl(mkUrl(path))
      .setBody(json.writeValueAsString(body))
      .setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=utf-8")
      .build();

    return performRequest(request);
  }

  /**
   * Issues a {@code PATCH} request with the provided JSON request body.
   *
   * @param path the relative path of the resource.
   * @param body the JSON tree that will be serialized into the request body.
   * @return a {@link ListenableFuture} containing the HTTP response.
   * @throws IOException if the HTTP request cannot be issued.
   */
  public ListenableFuture<Response> patch(String path, JsonNode body) throws IOException {
    Request request = new RequestBuilder("PATCH")
      .setUrl(mkUrl(path))
      .setBody(json.writeValueAsString(body))
      .setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/json; charset=utf-8")
      .build();

    return performRequest(request);
  }

  private ListenableFuture<Response> performRequest(final Request request) {
    final Timer.Context ctx = registry.timer("fauna-request").time();
    final SettableFuture<Response> rv = SettableFuture.create();

    client.prepareRequest(request)
      .addHeader("Authorization", authHeader)
      .execute(new AsyncCompletionHandler<Response>() {
        @Override
        public void onThrowable(Throwable t) {
          ctx.stop();
          rv.setException(t);
          logFailure(request, t);
        }

        @Override
        public Response onCompleted(Response response) throws Exception {
          ctx.stop();
          rv.set(response);
          logSuccess(request, response);
          return response;
        }
      });

    return rv;
  }

  private String mkUrl(String path) throws MalformedURLException {
    return new URL(faunaRoot, path).toString();
  }

  private void logSuccess(Request request, Response response) {
    if (log.isDebugEnabled()) {
      String data = Optional.fromNullable(request.getStringData()).or("");
      String host = Optional.fromNullable(response.getHeader(X_FAUNADB_HOST)).or("Unknown");
      String build = Optional.fromNullable(response.getHeader(X_FAUNADB_BUILD)).or("Unknown");
      String body = Optional.fromNullable(response.getResponseBody()).or("");

      log.debug(
        format("Request: %s %s: %s. Response: Status=%d, Fauna Host: %s, Fauna Build: %s: %s",
          request.getMethod(), request.getUrl(), data, response.getStatusCode(), host, build, body));
    }
  }

  private void logFailure(Request request, Throwable ex) {
    String data = Optional.fromNullable(request.getStringData()).or("");

    log.info(
      format("Request: %s %s: %s. Failed: %s",
        request.getMethod(), request.getUrl(), data, ex.getMessage()), ex);
  }

  private static String generateAuthHeader(String authToken) {
    try {
      String token = authToken + ":";
      return "Basic " + Base64.encode(token.getBytes(ASCII));
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // If ASCII is not supported there is no recovery action to be taken
    }
  }
}
