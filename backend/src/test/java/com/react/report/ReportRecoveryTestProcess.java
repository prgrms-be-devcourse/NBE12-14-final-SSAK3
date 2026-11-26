package com.react.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ReActApplication;
import com.react.report.repository.ReportAttemptRepository;
import com.react.report.service.BackgroundReportClient;
import com.react.report.service.OpenAiReportOverviewClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** A separately killable application with a loopback Responses API and the normal report schedulers. */
@TestConfiguration(proxyBeanMethods = false)
@Profile("report-restart-test")
public class ReportRecoveryTestProcess {
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final int HALTED_BEFORE_ID_SAVE = 79;

    static Process start(Path log, String jdbcUrl, String username, String password, int providerPort,
                         boolean haltBeforeIdSave)
            throws IOException {
        var directory = log.toAbsolutePath().getParent();
        var java = ProcessHandle.current().info().command().orElseThrow();
        var process = new ProcessBuilder(java, "-Xmx256m", "-XX:ActiveProcessorCount=2",
                "-cp", classpathJar(directory).toString(), ReportRecoveryTestProcess.class.getName(),
                Integer.toString(providerPort), Boolean.toString(haltBeforeIdSave), portFile(log).toString());
        // Do not pass real service credentials, Spring overrides or Java agents from the developer's shell.
        var environment = process.environment();
        environment.clear();
        for (var key : new String[]{"SystemRoot", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL"}) {
            if (System.getenv(key) != null) environment.put(key, System.getenv(key));
        }
        environment.put("SPRING_DATASOURCE_URL", jdbcUrl);
        environment.put("SPRING_DATASOURCE_USERNAME", username);
        environment.put("SPRING_DATASOURCE_PASSWORD", password);
        return process.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    // Gradle's test classpath lives in a child loader. An ASCII manifest also works with long Windows paths.
    private static Path classpathJar(Path directory) throws IOException {
        var entries = new LinkedHashSet<String>();
        for (var loader = ReportRecoveryTestProcess.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) entries.add(URI.create(url.toExternalForm()).toASCIIString());
            }
        }
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            entries.add(Path.of(entry).toUri().toASCIIString());
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", entries));
        Path jar = Files.createTempFile(directory, "backend-classpath-", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // A pathing JAR contains only its manifest.
        }
        return jar;
    }

    static Path portFile(Path log) {
        return log.toAbsolutePath().resolveSibling(log.getFileName() + ".port");
    }

    public static void main(String[] args) throws IOException {
        var context = new SpringApplicationBuilder(ReActApplication.class, ReportRecoveryTestProcess.class).run(
                "--spring.config.location=classpath:/application.yml",
                "--spring.profiles.active=report-restart-test",
                "--server.address=127.0.0.1", "--server.port=0",
                "--server.servlet.session.cookie.secure=false",
                "--spring.flyway.enabled=false", "--spring.datasource.hikari.maximum-pool-size=6",
                "--PARTICIPANT_JOIN_SECRET=" + TEST_KEY,
                "--app.ai.report.background-enabled=true",
                "--app.ai.report.webhook-secret=whsec_" + TEST_KEY,
                "--app.ai.report.recovery-interval=1s",
                "--test.responses-port=" + Integer.parseInt(args[0]),
                "--test.halt-before-id-save=" + Boolean.parseBoolean(args[1]));
        var server = ((WebServerApplicationContext) context).getWebServer();
        var portFile = Path.of(args[2]);
        var temp = Files.writeString(portFile.resolveSibling(portFile.getFileName() + ".tmp"), Integer.toString(server.getPort()));
        Files.move(temp, portFile);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "test.halt-before-id-save", havingValue = "true")
    ReportAttemptRepository haltAtFirstResponseId(JdbcClient jdbc) {
        return new ReportAttemptRepository(jdbc) {
            @Override
            public Optional<Attempt> attach(UUID attemptKey, String responseId) {
                // The worker has validated the HTTP reply. Kill only this child, before the first ID write.
                if (!ReportRecoveryTestProvider.RESPONSE_ID.equals(responseId)) {
                    throw new AssertionError("Unexpected response ID at crash point: " + responseId);
                }
                Runtime.getRuntime().halt(HALTED_BEFORE_ID_SAVE);
                throw new AssertionError("The test backend survived Runtime.halt");
            }
        };
    }

    @Bean
    @Primary
    BackgroundReportClient localResponses(ObjectMapper mapper, @Value("${test.responses-port}") int port)
            throws IOException {
        var origin = URI.create("http://127.0.0.1:" + port);
        var transport = new SimpleClientHttpRequestFactory();
        var client = RestClient.builder().requestFactory((uri, method) ->
                transport.createRequest(origin.resolve(uri.getRawPath()), method)).build();
        var prompt = new ClassPathResource("report/report-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        try (var schema = new ClassPathResource("report/report-draft.schema.json").getInputStream()) {
            return new OpenAiReportOverviewClient(client, mapper, "test-key", "test-model", prompt,
                    mapper.readTree(schema)).background();
        }
    }

    @Bean
    static BeanFactoryPostProcessor disableUnrelatedTimers() {
        // Report jobs subscribe to ApplicationReadyEvent. The @Scheduled jobs belong to polling and audience analysis.
        return factory -> ((BeanDefinitionRegistry) factory)
                .removeBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
    }
}
