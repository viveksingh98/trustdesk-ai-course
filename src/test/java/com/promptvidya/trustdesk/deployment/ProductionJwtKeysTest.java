package com.promptvidya.trustdesk.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Real keys from a mounted directory, offline: the current pair signs
 * and verifies; a token from the previous key still verifies while its
 * public key is in the bundle; a token from a stranger's key is
 * rejected; a missing key stops the context by name; and PEM parsing
 * errors never echo key material.
 */
class ProductionJwtKeysTest {

    @TempDir
    Path mount;

    private static KeyPair rsa() throws NoSuchAlgorithmException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String kind, byte[] encoded) {
        return "-----BEGIN " + kind + " KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END " + kind + " KEY-----\n";
    }

    private void secret(String name, String value) throws IOException {
        var file = mount.resolve(name);
        Files.writeString(file, value);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static String token(JwtEncoder encoder, String subject) {
        var now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(DevelopmentJwtKeys.ISSUER)
                .audience(List.of(DevelopmentJwtKeys.AUDIENCE))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .build())).getTokenValue();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionJwtKeys.class)
                .withPropertyValues("spring.profiles.active=prod", "trustdesk.secrets.directory=" + mount);
    }

    @Test
    void theMountedPairSignsAndVerifiesAndThePreviousKeyStillVerifies() throws Exception {
        var current = rsa();
        var previous = rsa();
        secret(ProductionJwtKeys.PRIVATE_KEY_SECRET, pem("PRIVATE", current.getPrivate().getEncoded()));
        secret(ProductionJwtKeys.PUBLIC_KEY_SECRET, pem("PUBLIC", current.getPublic().getEncoded()));
        secret(ProductionJwtKeys.PREVIOUS_PUBLIC_KEYS_SECRET, pem("PUBLIC", previous.getPublic().getEncoded()));

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var encoder = context.getBean(JwtEncoder.class);
            var decoder = context.getBean(JwtDecoder.class);
            assertThat(decoder.decode(token(encoder, "alice")).getSubject()).isEqualTo("alice");

            var previousSecrets = mountFor(previous);
            var previousEncoder = new ProductionJwtKeys().jwtEncoder(previousSecrets);
            assertThat(decoder.decode(token(previousEncoder, "bob")).getSubject()).isEqualTo("bob");
        });
    }

    @Test
    void aStrangersKeyIsRejectedAndAWrongAudienceIsRejected() throws Exception {
        var current = rsa();
        secret(ProductionJwtKeys.PRIVATE_KEY_SECRET, pem("PRIVATE", current.getPrivate().getEncoded()));
        secret(ProductionJwtKeys.PUBLIC_KEY_SECRET, pem("PUBLIC", current.getPublic().getEncoded()));

        runner().run(context -> {
            var decoder = context.getBean(JwtDecoder.class);
            var stranger = new ProductionJwtKeys().jwtEncoder(mountFor(rsa()));
            assertThatThrownBy(() -> decoder.decode(token(stranger, "mallory"))).isInstanceOf(JwtException.class);

            var encoder = context.getBean(JwtEncoder.class);
            var now = Instant.now();
            var payrollToken = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                    .issuer(DevelopmentJwtKeys.ISSUER).audience(List.of("payroll-api")).subject("alice")
                    .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5))).build())).getTokenValue();
            assertThatThrownBy(() -> decoder.decode(payrollToken)).isInstanceOf(JwtException.class);
        });
    }

    @Test
    void aMissingKeyStopsTheContextByNameAndParsingNeverEchoesMaterial() throws Exception {
        secret(ProductionJwtKeys.PUBLIC_KEY_SECRET, pem("PUBLIC", rsa().getPublic().getEncoded()));

        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("required secret is missing: jwt-private-key");
        });
        assertThatThrownBy(() -> ProductionJwtKeys.privateKeyFromPem("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jwt private key is not a PKCS#8 RSA key");
        assertThatThrownBy(() -> ProductionJwtKeys.publicKeyFromPem("not a pem at all"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected a PEM public key block");
    }

    @Test
    void keyIdsNameAKeyWithoutRevealingIt() throws Exception {
        var pair = rsa();
        var publicKey = ProductionJwtKeys.publicKeyFromPem(pem("PUBLIC", pair.getPublic().getEncoded()));

        var id = ProductionJwtKeys.keyId(publicKey);

        assertThat(id).hasSize(16).matches("[0-9a-f]+");
        assertThat(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())).doesNotContain(id);
        assertThat(ProductionJwtKeys.publicKeysFromPem(pem("PUBLIC", pair.getPublic().getEncoded()) + pem("PUBLIC", rsa().getPublic().getEncoded()))).hasSize(2);
    }

    private MountedSecrets mountFor(KeyPair pair) throws IOException {
        var directory = Files.createTempDirectory(mount, "pair-");
        Files.writeString(directory.resolve(ProductionJwtKeys.PRIVATE_KEY_SECRET), pem("PRIVATE", pair.getPrivate().getEncoded()));
        Files.writeString(directory.resolve(ProductionJwtKeys.PUBLIC_KEY_SECRET), pem("PUBLIC", pair.getPublic().getEncoded()));
        for (var name : List.of(ProductionJwtKeys.PRIVATE_KEY_SECRET, ProductionJwtKeys.PUBLIC_KEY_SECRET)) {
            Files.setPosixFilePermissions(directory.resolve(name), PosixFilePermissions.fromString("rw-------"));
        }
        return MountedSecrets.read(directory);
    }
}
