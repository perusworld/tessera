package com.github.tessera.server;

import com.github.tessera.config.ServerConfig;
import com.github.tessera.ssl.context.SSLContextFactory;
import com.github.tessera.ssl.context.ServerSSLContextFactory;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import javax.net.ssl.SSLContext;
import javax.ws.rs.core.Application;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of a RestServer using Jersey and Grizzly.
 */
public class JerseyServer implements RestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(JerseyServer.class);

    private HttpServer server;

    private final URI uri;

    private final Application application;

    private final SSLContext sslContext;

    private final boolean secure;

    public JerseyServer(final URI uri, final Application application, final ServerConfig serverConfig) {
        this.uri = Objects.requireNonNull(uri);
        this.application = Objects.requireNonNull(application);
        this.secure = serverConfig.isSsl();

        if (this.secure) {
            final SSLContextFactory sslContextFactory = ServerSSLContextFactory.create();
            this.sslContext = sslContextFactory.from(serverConfig.getSslConfig());
        } else {
            this.sslContext = null;
        }
    }

    @Override
    public void start() throws Exception {

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        //https://jersey.github.io/documentation/latest/appendix-properties.html
        final Map<String, Object> initParams = new HashMap<>();
        initParams.put("jersey.config.server.application.name", application.getClass().getSimpleName());
        initParams.put("jersey.config.server.tracing.type", "ON_DEMAND");
        initParams.put("jersey.config.server.tracing.threshold", "SUMMARY");
        initParams.put("jersey.config.logging.verbosity", "PAYLOAD_ANY");
        initParams.put("jersey.config.beanValidation.enableOutputValidationErrorEntity.server", "true");
        initParams.put("jersey.config.server.monitoring.statistics.enabled", "true");
        initParams.put("jersey.config.server.monitoring.enabled", "true");
        initParams.put("jersey.config.server.monitoring.statistics.mbeans.enabled", "true");

        final ResourceConfig config = ResourceConfig.forApplication(application);
        config.addProperties(initParams);

        if (this.secure) {
            this.server = GrizzlyHttpServerFactory.createHttpServer(
                uri,
                new ResourceConfig(),
                true,
                new SSLEngineConfigurator(sslContext).setClientMode(false).setNeedClientAuth(true),
                false
            );
        } else {
            this.server = GrizzlyHttpServerFactory.createHttpServer(uri, false);
        }

        final WebappContext ctx = new WebappContext("WebappContext");
        final ServletRegistration registration = ctx.addServlet("ServletContainer", new ServletContainer(config));
        registration.addMapping("/*");

        ctx.deploy(this.server);

        LOGGER.info("Starting {}", uri);

        this.server.start();

        LOGGER.info("Started {}", uri);
        LOGGER.info("WADL {}/application.wadl", uri);
    }

    @Override
    public void stop() {
        LOGGER.info("Stopping Jersey server at {}", uri);

        if (Objects.nonNull(this.server)) {
            this.server.shutdown();
        }

        LOGGER.info("Stopped Jersey server at {}", uri);
    }

}
