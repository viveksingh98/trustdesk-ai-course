package com.promptvidya.trustdesk.deployment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Real key material, vault-ready. In production the signing key is a
 * PEM file mounted by the secret store, never generated at startup and
 * never a property in a yaml; verification accepts the current public
 * key plus any previous ones still in the bundle, so keys rotate
 * without a flag day. Key ids are digests of the public key, so a
 * token names the key that signed it without revealing anything.
 */
@Configuration
@Profile("prod")
public class ProductionJwtKeys {

    public static final String PRIVATE_KEY_SECRET = "jwt-private-key";
    public static final String PUBLIC_KEY_SECRET = "jwt-public-key";
    public static final String PREVIOUS_PUBLIC_KEYS_SECRET = "jwt-previous-public-keys";

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN (?:RSA )?(PRIVATE|PUBLIC) KEY-----([A-Za-z0-9+/=\\s]+)-----END (?:RSA )?\\1 KEY-----");

    @Bean
    public MountedSecrets mountedSecrets(@Value("${trustdesk.secrets.directory:/run/secrets}") Path directory) {
        return MountedSecrets.read(directory);
    }

    @Bean
    public JwtEncoder jwtEncoder(MountedSecrets secrets) {
        var privateKey = privateKeyFromPem(secrets.require(PRIVATE_KEY_SECRET));
        var publicKey = publicKeyFromPem(secrets.require(PUBLIC_KEY_SECRET));
        var signingKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId(publicKey)).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(MountedSecrets secrets) {
        var accepted = new ArrayList<RSAPublicKey>();
        accepted.add(publicKeyFromPem(secrets.require(PUBLIC_KEY_SECRET)));
        secrets.text(PREVIOUS_PUBLIC_KEYS_SECRET).ifPresent(bundle -> accepted.addAll(publicKeysFromPem(bundle)));
        var keys = accepted.stream()
                .map(key -> (com.nimbusds.jose.jwk.JWK) new RSAKey.Builder(key).keyID(keyId(key)).build())
                .toList();
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(new JWKSet(keys))));
        var decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(DevelopmentJwtKeys.ISSUER),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(DevelopmentJwtKeys.AUDIENCE))));
        return decoder;
    }

    /** PKCS#8 private key text to a key; the message never includes the text. */
    public static RSAPrivateKey privateKeyFromPem(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(body(pem, "PRIVATE")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("jwt private key is not a PKCS#8 RSA key", exception);
        }
    }

    public static RSAPublicKey publicKeyFromPem(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(body(pem, "PUBLIC")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("jwt public key is not an X.509 RSA key", exception);
        }
    }

    /** Every PUBLIC KEY block in a bundle, in order — the keys still allowed to verify. */
    public static List<RSAPublicKey> publicKeysFromPem(String bundle) {
        var keys = new ArrayList<RSAPublicKey>();
        var matcher = PEM_BLOCK.matcher(bundle);
        while (matcher.find()) {
            if (matcher.group(1).equals("PUBLIC")) {
                keys.add(publicKeyFromPem(matcher.group()));
            }
        }
        return List.copyOf(keys);
    }

    /** A key id that names the key without revealing it: the first 16 hex characters of its digest. */
    public static String keyId(RSAPublicKey key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory on the JVM", exception);
        }
    }

    private static byte[] body(String pem, String kind) {
        var matcher = PEM_BLOCK.matcher(pem == null ? "" : pem);
        if (!matcher.find() || !matcher.group(1).equals(kind)) {
            throw new IllegalStateException("expected a PEM " + kind.toLowerCase() + " key block");
        }
        return Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
    }
}
