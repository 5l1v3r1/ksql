/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.api.server;

import io.confluent.ksql.api.auth.DefaultApiSecurityContext;
import io.confluent.ksql.api.server.protocol.QueryResponseMetadata;
import io.confluent.ksql.api.server.protocol.QueryStreamArgs;
import io.confluent.ksql.api.spi.Endpoints;
import io.confluent.ksql.util.KsqlStatementException;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles requests to the query-stream endpoint
 */
public class QueryStreamHandler implements Handler<RoutingContext> {

  private static final Logger log = LoggerFactory.getLogger(QueryStreamHandler.class);

  static final String DELIMITED_CONTENT_TYPE = "application/vnd.ksqlapi.delimited.v1";

  private final Endpoints endpoints;
  private final ConnectionQueryManager connectionQueryManager;
  private final Context context;
  private final Server server;

  public QueryStreamHandler(final Endpoints endpoints,
      final ConnectionQueryManager connectionQueryManager,
      final Context context,
      final Server server) {
    this.endpoints = Objects.requireNonNull(endpoints);
    this.connectionQueryManager = Objects.requireNonNull(connectionQueryManager);
    this.context = Objects.requireNonNull(context);
    this.server = Objects.requireNonNull(server);
  }

  @Override
  public void handle(final RoutingContext routingContext) {

    final String contentType = routingContext.getAcceptableContentType();
    final QueryStreamResponseWriter queryStreamResponseWriter;
    if (DELIMITED_CONTENT_TYPE.equals(contentType) || contentType == null) {
      // Default
      queryStreamResponseWriter =
          new DelimitedQueryStreamResponseWriter(routingContext.response());
    } else {
      queryStreamResponseWriter = new JsonQueryStreamResponseWriter(routingContext.response());
    }

    final Optional<QueryStreamArgs> queryStreamArgs = ServerUtils
        .deserialiseObject(routingContext.getBody(), routingContext.response(),
            QueryStreamArgs.class);
    if (!queryStreamArgs.isPresent()) {
      return;
    }

    endpoints.createQueryPublisher(queryStreamArgs.get().sql, queryStreamArgs.get().properties,
        context, server.getWorkerExecutor(), DefaultApiSecurityContext.create(routingContext))
        .thenAccept(queryPublisher -> {

          final QueryResponseMetadata metadata;

          if (queryPublisher.isPullQuery()) {
            metadata = new QueryResponseMetadata(
                queryPublisher.getColumnNames(),
                queryPublisher.getColumnTypes());
          } else {
            final PushQueryHolder query = connectionQueryManager
                .createApiQuery(queryPublisher, routingContext.request());

            metadata = new QueryResponseMetadata(
                query.getId().toString(),
                queryPublisher.getColumnNames(),
                queryPublisher.getColumnTypes());

            // When response is complete, publisher should be closed and query unregistered
            routingContext.response().endHandler(v -> query.close());
          }

          queryStreamResponseWriter.writeMetadata(metadata);

          final QuerySubscriber querySubscriber = new QuerySubscriber(context,
              routingContext.response(),
              queryStreamResponseWriter);

          queryPublisher.subscribe(querySubscriber);

        })
        .exceptionally(t -> handleQueryPublisherException(t, routingContext));
  }

  private Void handleQueryPublisherException(final Throwable t,
      final RoutingContext routingContext) {
    log.error("Failed to execute query", t);
    if (t instanceof CompletionException) {
      final Throwable actual = t.getCause();
      if (actual instanceof KsqlStatementException) {
        ServerUtils.handleError(routingContext.response(), 400, ErrorCodes.ERROR_CODE_INVALID_QUERY,
            actual.getMessage());
        return null;
      } else if (actual instanceof KsqlApiException) {
        ServerUtils
            .handleError(routingContext.response(), 400, ((KsqlApiException) actual).getErrorCode(),
                actual.getMessage());
      }
    }
    // We don't expose internal error message via public API
    ServerUtils.handleError(routingContext.response(), 500, ErrorCodes.ERROR_CODE_INTERNAL_ERROR,
        "The server encountered an internal error when processing the query."
            + " Please consult the server logs for more information.");
    return null;
  }

}
