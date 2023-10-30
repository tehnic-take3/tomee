package org.superbiz.microprofile.openapi;

import io.smallrye.openapi.api.OpenApiConfig;
import io.smallrye.openapi.api.OpenApiConfigImpl;
import io.smallrye.openapi.api.OpenApiDocument;
import io.smallrye.openapi.runtime.OpenApiProcessor;
import io.smallrye.openapi.runtime.OpenApiStaticFile;
import io.smallrye.openapi.runtime.io.Format;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.net.URL;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static io.smallrye.openapi.runtime.io.Format.JSON;
import static io.smallrye.openapi.runtime.io.Format.YAML;

public class CustomReader implements OASModelReader, ServletContainerInitializer {

    private static final Logger LOGGER = Logger.getLogger(CustomReader.class.getName());

    private static Optional<OpenAPI> openAPI;

    @Override
    public OpenAPI buildModel() {
        return openAPI.orElseThrow(() -> new IllegalStateException("OpenAPI document not available."));
    }

    @Override
    public void onStartup(final Set<Class<?>> c, final ServletContext ctx) throws ServletException {
        try {
            final OpenApiConfig openApiConfig = new OpenApiConfigImpl(ConfigProvider.getConfig());;
            final Optional<OpenAPI> yaml = readOpenApiFile(ctx, "/WEB-INF/classes/openapi/openapi.yaml", YAML);
            final Optional<OpenAPI> json = readOpenApiFile(ctx, "/WEB-INF/classes/my-openapi.json", JSON);

            final OpenApiDocument document = OpenApiDocument.INSTANCE;
            try {
                document.reset();
                document.config(openApiConfig);
                yaml.ifPresent(document::modelFromStaticFile);
                json.ifPresent(document::modelFromStaticFile);
                document.initialize();

                openAPI = Optional.ofNullable(document.get());

            } finally {
                document.reset();
            }

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<OpenAPI> readOpenApiFile(
        final ServletContext servletContext, final String location,
        final Format format) throws Exception {

        final URL resource = servletContext.getResource(location);
        if (resource == null) {
            LOGGER.fine("Could not find static OpenAPI file " + location);
            return Optional.empty();
        }

        LOGGER.fine("Found static OpenAPI file " + location);

        try (OpenApiStaticFile staticFile = new OpenApiStaticFile(resource.openStream(), format)) {
            return Optional.of(OpenApiProcessor.modelFromStaticFile(staticFile));
        }
    }
}
