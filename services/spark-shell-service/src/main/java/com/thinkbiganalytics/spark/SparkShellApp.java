package com.thinkbiganalytics.spark;

import com.thinkbiganalytics.spark.repl.ScriptEngine;
import com.thinkbiganalytics.spark.repl.ScriptEngineFactory;
import com.thinkbiganalytics.spark.rest.SparkShellController;
import com.thinkbiganalytics.spark.service.TransformService;

import org.apache.spark.SparkConf;
import org.apache.spark.util.ShutdownHookManager;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.beans.factory.config.PropertyOverrideConfigurer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import javax.annotation.Nonnull;

import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import scala.Function0;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

/**
 * Instantiates a REST server for executing Spark scripts.
 */
@SpringBootApplication
public class SparkShellApp {

    /**
     * Instantiates the REST server with the specified arguments.
     *
     * @param args the command-line arguments
     * @throws Exception if an error occurs
     */
    public static void main(String[] args) throws Exception {
        SpringApplication.run(SparkShellApp.class, args);
    }

    /**
     * Gets the resource configuration for setting up Jersey.
     *
     * @return the Jersey configuration
     */
    @Bean
    public ResourceConfig getJerseyConfig () {
        ResourceConfig config = new ResourceConfig(ApiListingResource.class, SwaggerSerializers.class, SparkShellController.class);

        SparkConf conf = new SparkConf().setAppName("SparkShellServer");
        final ScriptEngine scriptEngine = ScriptEngineFactory.getScriptEngine(conf);
        final TransformService transformService = createTransformService(scriptEngine);
        config.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(new Factory<TransformService>() {
                    @Override
                    public void dispose(TransformService instance) {
                        // nothing to do
                    }

                    @Override
                    public TransformService provide() {
                        return transformService;
                    }
                }).to(TransformService.class).in(RequestScoped.class);
            }
        });

        return config;
    }

    /**
     * Gets additional property configurations.
     *
     * @return additional property configurations
     */
    @Bean
    public PropertyOverrideConfigurer getPropertyOverrideConfigurer() {
        PropertyOverrideConfigurer poc = new PropertyOverrideConfigurer();
        poc.setIgnoreInvalidKeys(true);
        poc.setIgnoreResourceNotFound(true);
        poc.setLocations(new ClassPathResource("application.properties"), new ClassPathResource("applicationDevOverride.properties"));
        poc.setOrder(-100);
        return poc;
    }

    /**
     * Create a transform service using the specified script engine.
     *
     * @param engine the script engine
     * @return the transform service
     */
    private static TransformService createTransformService(@Nonnull final ScriptEngine engine) {
        // Start the service
        final TransformService service = new TransformService(engine);
        service.startAsync();

        // Add a shutdown hook
        Function0<BoxedUnit> hook = new AbstractFunction0<BoxedUnit>() {
            @Override
            public BoxedUnit apply() {
                service.stopAsync();
                service.awaitTerminated();
                return BoxedUnit.UNIT;
            }
        };
        ShutdownHookManager.addShutdownHook(hook);

        // Wait for service to start
        service.awaitRunning();
        return service;
    }
}