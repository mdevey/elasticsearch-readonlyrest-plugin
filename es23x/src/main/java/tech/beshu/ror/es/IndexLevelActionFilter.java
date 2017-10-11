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

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.commons.BasicSettings;
import tech.beshu.ror.commons.RawSettings;
import tech.beshu.ror.commons.shims.es.ACLHandler;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.configuration.ReloadableSettings;
import tech.beshu.ror.es.actionlisteners.RuleActionListenersProvider;
import tech.beshu.ror.es.requestcontext.RequestContextImpl;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter, Consumer<BasicSettings> {

  private final IndexNameExpressionResolver indexResolver;
  private ThreadPool threadPool;
  private ClusterService clusterService;

  private AtomicReference<Optional<ACL>> acl = new AtomicReference<>(Optional.empty());
  private ESContext context;
  private RuleActionListenersProvider ruleActionListenersProvider;
  private ReloadableSettings reloadableSettings;
  private AtomicReference<Optional<AuditSinkImpl>> audit = new AtomicReference<>(Optional.empty());
  private NodeClient client;
  private LoggerShim logger;
  private BasicSettings basicSettings;


  @Inject
  public IndexLevelActionFilter(Settings settings,
                                ClusterService clusterService,
                                TransportService transportService,
                                ThreadPool threadPool,
                                IndexNameExpressionResolver indexResolver)
    throws IOException {
    super(settings);

    this.basicSettings = new BasicSettings(new RawSettings(settings.getAsStructuredMap()));
    this.context = new ESContextImpl();
    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
    this.threadPool = threadPool;

    ReadonlyRestPlugin.clientFuture.thenAccept(c -> {
      try {
        this.client = c;
        reloadableSettings = new ReloadableSettingsImpl(new SettingsManagerImpl(settings, client));

        this.ruleActionListenersProvider = new RuleActionListenersProvider(context);
        this.logger = ESContextImpl.mkLoggerShim(super.logger);
        new TaskManagerWrapper(settings).injectIntoTransportService(transportService, logger);

        reloadableSettings.addListener(s -> accept(s));

        logger.info("Readonly REST plugin was loaded...");
      } catch (Throwable e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    });

  }

  private void initialize(BasicSettings basicsSettings) {
    this.basicSettings = basicsSettings;
    if (basicsSettings.isEnabled()) {
      try {
        AuditSinkImpl audit = new AuditSinkImpl(client, basicsSettings);
        this.audit.set(Optional.of(audit));
        ACL acl = new ACL(basicsSettings, this.context, audit);
        this.acl.set(Optional.of(acl));
      } catch (Exception ex) {
        logger.error("Cannot initialize ReadonlyREST settings!", ex);
      }
    }
    else {
      this.acl.set(Optional.empty());
      logger.info("ReadonlyREST Settings initialized - ReadonlyREST disabled");
    }

  }

  @Override
  public void accept(BasicSettings basicSettings) {
    if (!basicSettings.isEnabled()) {
      logger.info("ReadonlyREST Settings reloaded - ReadonlyREST ENABLED");
    }
    initialize(basicSettings);
    logger.info("ReadonlyREST Settings reloaded - ReadonlyREST DISABLED");
  }

  @Override
  public int order() {
    return 0;
  }

  @Override
  public void apply(Task task,
                    String action,
                    ActionRequest request,
                    ActionListener listener,
                    ActionFilterChain chain) {


    Optional<ACL> acl = this.acl.get();
    if (acl.isPresent()) {
      handleRequest(acl.get(), task, action, request, listener, chain);
    }
    else {
      // The ACL was not yet set, or the plugin is not enabled..
      chain.proceed(task, action, request, listener);
    }
  }

  @Override
  public void apply(String action, ActionResponse response, ActionListener listener, ActionFilterChain chain) {
    chain.proceed(action, response, listener);
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(ACL acl,
                                                                                              Task task,
                                                                                              String action,
                                                                                              Request request,
                                                                                              ActionListener<Response> listener,
                                                                                              ActionFilterChain chain) {
    RestChannel channel = ThreadRepo.channel.get();
    boolean chanNull = channel == null;
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (ACL.shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }
    RequestContextImpl rc = new RequestContextImpl(channel.request(), action, request, clusterService, threadPool, context, indexResolver);

    acl.check(rc, new ACLHandler() {
      @Override
      public void onForbidden() {
          sendNotAuthResponse(channel, basicSettings);
      }

      @Override
      public void onAllow(Object blockExitResult) {

//          @SuppressWarnings("unchecked")
//          ActionListener<Response> aclActionListener = (ActionListener<Response>) new ACLActionListener(
//            request, (ActionListener<ActionResponse>) listener, ruleActionListenersProvider, rc, result, context
//          );
        chain.proceed(task, action, request, listener);

      }

      @Override
      public boolean isNotFound(Throwable throwable) {
        return throwable.getCause() instanceof ResourceNotFoundException;
      }

      @Override
      public void onNotFound(Throwable t) {
        sendNotFound((ResourceNotFoundException) t.getCause(), channel);
      }

      @Override
      public void onErrored(Throwable t) {
        sendNotAuthResponse(channel, basicSettings);
      }
    });

  }

  private void sendNotAuthResponse(RestChannel channel, BasicSettings basicSettings) {
    BytesRestResponse resp;
    boolean doesRequirePassword = acl.get().map(ACL::doesRequirePassword).orElse(false);
    if (doesRequirePassword) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, BytesRestResponse.TEXT_CONTENT_TYPE, basicSettings.getForbiddenMessage());
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    }
    else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, BytesRestResponse.TEXT_CONTENT_TYPE, basicSettings.getForbiddenMessage());
    }

    channel.sendResponse(resp);
  }

  private void sendNotFound(ResourceNotFoundException e, RestChannel channel) {
    try {
      XContentBuilder b = JsonXContent.contentBuilder();
      b.startObject();
      ElasticsearchException.renderThrowable(b, ToXContent.EMPTY_PARAMS, e);
      b.endObject();
      BytesRestResponse resp;
      resp = new BytesRestResponse(RestStatus.NOT_FOUND, "application/json", b.string());
      channel.sendResponse(resp);
    } catch (Exception e1) {
      e1.printStackTrace();
    }
  }

}
