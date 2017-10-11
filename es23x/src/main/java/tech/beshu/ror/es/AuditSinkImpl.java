/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.es;

import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import tech.beshu.ror.commons.shims.es.AuditSinkCore;
import tech.beshu.ror.commons.BasicSettings;
import tech.beshu.ror.commons.ResponseContext;
import tech.beshu.ror.requestcontext.SerializationTool;

import java.util.Arrays;
import java.util.Calendar;
import java.util.function.Function;
import java.util.stream.Collectors;

import static tech.beshu.ror.commons.Constants.AUDIT_SINK_MAX_ITEMS;
import static tech.beshu.ror.commons.Constants.AUDIT_SINK_MAX_KB;
import static tech.beshu.ror.commons.Constants.AUDIT_SINK_MAX_RETRIES;
import static tech.beshu.ror.commons.Constants.AUDIT_SINK_MAX_SECONDS;

/**
 * Created by sscarduzio on 14/06/2017.
 */

@Singleton
public class AuditSinkImpl extends AuditSinkCore {

  private static final ESLogger logger = Loggers.getLogger(AuditSinkImpl.class);
  private final BulkProcessor bulkProcessor;
  private final BasicSettings settings;
  private SerializationTool serTool;

  @Inject
  public AuditSinkImpl(Client client, BasicSettings settings) {
    this.settings = settings;

    if (serTool == null) {
      serTool = new SerializationTool();
    }

    if (!isAuditCollectorEnabled()) {
      bulkProcessor = null;
      return;
    }

    this.bulkProcessor = BulkProcessor.builder(
      client,
      new BulkProcessor.Listener() {
        @Override
        public void beforeBulk(long executionId,
                               BulkRequest request) {
          logger.debug("Flushing " + request.numberOfActions() + " bulk actions..");
        }

        @Override
        public void afterBulk(long executionId,
                              BulkRequest request,
                              BulkResponse response) {
          if (response.hasFailures()) {
            logger.error("Some failures flushing the BulkProcessor: ");
            Arrays.stream(response.getItems())
              .filter(r -> r.isFailed())
              .map(r -> r.getFailureMessage())
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
              .forEach((message, times) -> logger.error(times + "x: " + message));
          }
        }

        @Override
        public void afterBulk(long executionId,
                              BulkRequest request,
                              Throwable failure) {
          logger.error("Failed flushing the BulkProcessor: " + failure.getMessage());
          failure.printStackTrace();
        }
      }
    )
      .setBulkActions(AUDIT_SINK_MAX_ITEMS)
      .setBulkSize(new ByteSizeValue(AUDIT_SINK_MAX_KB, ByteSizeUnit.KB))
      .setFlushInterval(TimeValue.timeValueSeconds(AUDIT_SINK_MAX_SECONDS))
      .setConcurrentRequests(1)
      .setBackoffPolicy(
        BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), AUDIT_SINK_MAX_RETRIES))
      .build();
  }

  public void submit(ResponseContext rc) {
    if (!isAuditCollectorEnabled()) {
      return;
    }
    String indexName = "readonlyrest_audit-" + formatter.format(Calendar.getInstance().getTime());
    IndexRequest ir = new IndexRequest(
      indexName,
      "ror_audit_evt",
      rc.getRequestContext().getId()
    ).source(
      serTool.toJson(rc)
    );
    bulkProcessor.add(ir);
  }

  @Override
  public Boolean isAuditCollectorEnabled() {
    return settings.isAuditorCollectorEnabled();
  }
}
