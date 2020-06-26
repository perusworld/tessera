package com.quorum.tessera.test.migration.config;

import com.quorum.tessera.config.*;
import com.quorum.tessera.config.util.JaxbUtil;
import cucumber.api.java8.En;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigMigrationSteps implements En {

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private Path outputFile;

    public ConfigMigrationSteps() {

        Given("^(.+) exists$", (String filePath) -> assertThat(getClass().getResource(filePath)).isNotNull());

        Given("^the outputfile is created$", () -> assertThat(Files.exists(outputFile)).isTrue());

        When(
                "the Config Migration Utility is run with tomlfile (.+) and --outputfile option",
                (String toml) -> {
                    final String jarfile =
                            Optional.of("config-migration-app.jar")
                                    .map(System::getProperty)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "Unable to find config-migration-app.jar system property"));

                    outputFile = Paths.get("target", UUID.randomUUID().toString());

                    assertThat(Files.exists(outputFile)).isFalse();

                    List<String> args =
                            new ArrayList<>(
                                    Arrays.asList(
                                            "java",
                                            "-jar",
                                            jarfile,
                                            "--tomlfile",
                                            getAbsolutePath(toml).toString(),
                                            "--outputfile",
                                            outputFile.toAbsolutePath().toString()));
                    System.out.println(String.join(" ", args));

                    ProcessBuilder configMigrationProcessBuilder = new ProcessBuilder(args);

                    final Process configMigrationProcess =
                            configMigrationProcessBuilder.redirectErrorStream(true).start();

                    executorService.submit(
                            () -> {
                                final InputStream inputStream = configMigrationProcess.getInputStream();
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        System.out.println(line);
                                    }
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            });

                    configMigrationProcess.waitFor();

                    if (configMigrationProcess.isAlive()) {
                        configMigrationProcess.destroy();
                    }
                });

        Then(
                "(.+) and the outputfile are equivalent",
                (String legacyPath) -> {
                    final Config migratedConfig = JaxbUtil.unmarshal(Files.newInputStream(outputFile), Config.class);

                    // TODO These values were retrieved from legacy.toml.  Ideally legacyConfig would be generated by
                    // unmarshalling legacy.toml but didn't want to use the toml unmarshalling production code in the
                    // test
                    final SslConfig sslConfig = new SslConfig();
                    sslConfig.setTls(SslAuthenticationMode.STRICT);
                    sslConfig.setServerTlsCertificatePath(Paths.get("data", "tls-server-cert.pem").toAbsolutePath());
                    sslConfig.setServerTlsKeyPath(Paths.get("data", "tls-server-key.pem").toAbsolutePath());
                    sslConfig.setServerTrustCertificates(emptyList());
                    sslConfig.setServerTrustMode(SslTrustMode.TOFU);
                    sslConfig.setKnownClientsFile(Paths.get("data", "tls-known-clients").toAbsolutePath());
                    sslConfig.setClientTlsCertificatePath(Paths.get("data", "tls-client-cert.pem").toAbsolutePath());
                    sslConfig.setClientTlsKeyPath(Paths.get("data", "tls-client-key.pem").toAbsolutePath());
                    sslConfig.setClientTrustCertificates(emptyList());
                    sslConfig.setClientTrustMode(SslTrustMode.CA_OR_TOFU);
                    sslConfig.setKnownServersFile(Paths.get("data", "tls-known-servers").toAbsolutePath());

                    final String url = "http://127.0.0.1:9001";
                    final ServerConfig p2pServer =
                            new ServerConfig(AppType.P2P, url, CommunicationType.REST, sslConfig, null, url);
                    final ServerConfig unixServer =
                            new ServerConfig(
                                    AppType.Q2T,
                                    "unix:" + Paths.get("data", "constellation.ipc").toAbsolutePath().toString(),
                                    CommunicationType.REST,
                                    null,
                                    null,
                                    null);

                    final KeyConfiguration keys = new KeyConfiguration();

                    KeyData keyData = new KeyData();
                    keyData.setPrivateKeyPath(Paths.get("data", "foo.key").toAbsolutePath());
                    keyData.setPublicKeyPath(Paths.get("data", "foo.pub").toAbsolutePath());

                    keys.setKeyData(singletonList(keyData));
                    keys.setPasswordFile(Paths.get("data", "passwords").toAbsolutePath());

                    final JdbcConfig jdbcConfig = new JdbcConfig();
                    jdbcConfig.setUrl("jdbc:h2:mem:tessera");

                    assertThat(migratedConfig.getKeys()).isEqualToComparingFieldByFieldRecursively(keys);
                    assertThat(migratedConfig.getJdbcConfig()).isEqualToComparingFieldByField(jdbcConfig);
                    assertThat(migratedConfig.getAlwaysSendTo()).isEqualTo(emptyList());
                    assertThat(migratedConfig.getServerConfigs())
                            .hasSize(2)
                            .containsExactlyInAnyOrder(p2pServer, unixServer);
                    assertThat(migratedConfig.getPeers()).containsExactly(new Peer("http://127.0.0.1:9000/"));
                });
    }

    private Path getAbsolutePath(String filePath) throws Exception {
        return Paths.get(getClass().getResource(filePath).toURI()).toAbsolutePath();
    }
}
