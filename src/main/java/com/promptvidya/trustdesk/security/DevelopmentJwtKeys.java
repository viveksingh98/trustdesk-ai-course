package com.promptvidya.trustdesk.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * A course device: one RSA key pair generated at startup, so the demo can
 * mint and verify its own tokens offline. Production points the decoder
 * at the identity provider's JWK Set URI and deletes the encoder.
 *
 * <p>What is not a course device: the validation. Signature, issuer,
 * expiry, and audience are checked exactly as they must be against a
 * real provider — audience most of all, because a token minted for a
 * different API must never open this one.
 */
@Configuration
public class DevelopmentJwtKeys {

    public static final String ISSUER = "https://trustdesk.local/issuer";
    public static final String AUDIENCE = "trustdesk-agent-api";

    private final RSAKey key;

    public DevelopmentJwtKeys() throws JOSEException {
        this.key = new RSAKeyGenerator(2048).keyID("trustdesk-dev").generate();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder() throws JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audienceMustNameThisApi()));
        return decoder;
    }

    /** The check teams skip most: the token must have been minted for this API. */
    static OAuth2TokenValidator<Jwt> audienceMustNameThisApi() {
        return new JwtClaimValidator<Collection<String>>(
                JwtClaimNames.AUD, audience -> audience != null && audience.contains(AUDIENCE));
    }

    /** The verification key alone — what a naive resource server would build its decoder from. */
    public RSAPublicKey publicKey() throws JOSEException {
        return key.toRSAPublicKey();
    }
}
