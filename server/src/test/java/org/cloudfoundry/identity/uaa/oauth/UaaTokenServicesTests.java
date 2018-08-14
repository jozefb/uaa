package org.cloudfoundry.identity.uaa.oauth;

import com.google.common.collect.Sets;
import org.apache.commons.io.output.TeeOutputStream;
import org.bouncycastle.util.Strings;
import org.cloudfoundry.identity.uaa.annotations.WithSpring;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.oauth.jwt.Jwt;
import org.cloudfoundry.identity.uaa.oauth.jwt.JwtHelper;
import org.cloudfoundry.identity.uaa.oauth.token.CompositeToken;
import org.cloudfoundry.identity.uaa.user.JdbcUaaUserDatabase;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.AuthorizationRequest;
import org.springframework.security.oauth2.provider.OAuth2Authentication;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.cloudfoundry.identity.uaa.oauth.TokenTestSupport.GRANT_TYPE;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_AUTHORIZATION_CODE;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_CLIENT_CREDENTIALS;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_IMPLICIT;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_PASSWORD;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@WithSpring
public class UaaTokenServicesTests {
    @Autowired
    private UaaTokenServices tokenServices;

    @Value("${uaa.url}")
    private String uaaUrl;

    @Value("${oauth.clients.jku_test.id}")
    private String clientId;

    @Value("${oauth.clients.jku_test.secret}")
    private String clientSecret;

    @Value("${oauth.clients.jku_test.scope}")
    private String clientScopes;

    @Autowired
    private JdbcUaaUserDatabase jdbcUaaUserDatabase;

    @Nested
    @DisplayName("when building an id token")
    @WithSpring
    class WhenRequestingAnIdToken {
        private String requestedScope;
        private String responseType;

        private PrintStream systemOut;
        private PrintStream systemErr;
        private ByteArrayOutputStream loggingOutputStream;

        @BeforeEach
        void setupLogger() {
            systemOut = System.out;
            systemErr = System.err;

            loggingOutputStream = new ByteArrayOutputStream();

            System.setErr(new PrintStream(new TeeOutputStream(loggingOutputStream, systemOut), true));
            System.setOut(new PrintStream(new TeeOutputStream(loggingOutputStream, systemErr), true));
        }

        @AfterEach
        void resetStdout() {
            System.setOut(systemOut);
            System.setErr(systemErr);
        }


        @BeforeEach
        void setupRequest() {
            requestedScope = "openid";
            responseType = "id_token";
        }

        @Test
        public void ensureJKUHeaderIsSetWhenBuildingAnIdToken() {
            AuthorizationRequest authorizationRequest = constructAuthorizationRequest(GRANT_TYPE_PASSWORD, requestedScope);
            authorizationRequest.setResponseTypes(Sets.newHashSet(responseType));

            OAuth2Authentication auth2Authentication = constructUserAuthenticationFromAuthzRequest(authorizationRequest, "admin", "uaa");

            CompositeToken accessToken = (CompositeToken) tokenServices.createAccessToken(auth2Authentication);

            Jwt jwtToken = JwtHelper.decode(accessToken.getIdTokenValue());
            assertThat(jwtToken.getHeader().getJku(), startsWith(uaaUrl));
            assertThat(jwtToken.getHeader().getJku(), is("https://uaa.some.test.domain.com:555/uaa/token_keys"));
        }

        @DisplayName("ensureIdToken Returned when Client Has OpenId Scope and Scope=OpenId, ResponseType=id_token withGrantType")
        @ParameterizedTest
        @ValueSource(strings = {GRANT_TYPE_PASSWORD, GRANT_TYPE_AUTHORIZATION_CODE, GRANT_TYPE_IMPLICIT})
        public void ensureIdTokenReturned_withGrantType(String grantType) {
            AuthorizationRequest authorizationRequest = constructAuthorizationRequest(grantType, requestedScope);
            authorizationRequest.setResponseTypes(Sets.newHashSet(responseType));

            OAuth2Authentication auth2Authentication = constructUserAuthenticationFromAuthzRequest(authorizationRequest, "admin", "uaa");

            CompositeToken accessToken = (CompositeToken) tokenServices.createAccessToken(auth2Authentication);

            assertThat(accessToken.getIdTokenValue(), is(not(nullValue())));
            JwtHelper.decode(accessToken.getIdTokenValue());
        }

        @Nested
        @DisplayName("when the user doesn't request the 'openid' scope")
        @WithSpring
        class WhenUserDoesntRequestOpenIdScope {
            @BeforeEach
            void setupRequest() {
                requestedScope = "uaa.admin";

            }

            @Test
            public void ensureAnIdTokenIsNotReturned() {
                AuthorizationRequest authorizationRequest = constructAuthorizationRequest(GRANT_TYPE_PASSWORD, requestedScope);
                authorizationRequest.setResponseTypes(Sets.newHashSet(responseType));

                OAuth2Authentication auth2Authentication = constructUserAuthenticationFromAuthzRequest(authorizationRequest, "admin", "uaa");

                CompositeToken accessToken = (CompositeToken) tokenServices.createAccessToken(auth2Authentication);
                assertAll("id token is not returned, and a useful log message is printed",
                  () -> assertThat(accessToken.getIdTokenValue(), is(nullValue())),
                  () -> assertThat("Useful log message", loggingOutputStream.toString(), containsString("an ID token was requested but 'openid' is missing from the requested scopes")),
                  () -> assertThat("Does not contain log message", loggingOutputStream.toString(), not(containsString("an ID token cannot be returned since the user didn't specify 'id_token' as the response_type")))
                );
            }
        }

        @Nested
        @DisplayName("when the user doesn't request response_type=id_token")
        @WithSpring
        class WhenUserDoesntRequestResponseTypeEqualsIdToken {
            @BeforeEach
            void setupRequest() {
                responseType = "token";
            }

            @Test
            public void ensureAnIdTokenIsNotReturned() {
                AuthorizationRequest authorizationRequest = constructAuthorizationRequest(GRANT_TYPE_PASSWORD, requestedScope);
                authorizationRequest.setResponseTypes(Sets.newHashSet(responseType));

                OAuth2Authentication auth2Authentication = constructUserAuthenticationFromAuthzRequest(authorizationRequest, "admin", "uaa");

                CompositeToken accessToken = (CompositeToken) tokenServices.createAccessToken(auth2Authentication);
                assertAll("id token is not returned, and a useful log message is printed",
                  () -> assertThat(accessToken.getIdTokenValue(), is(nullValue())),
                  () -> assertThat("Useful log message", loggingOutputStream.toString(), containsString("an ID token cannot be returned since the user didn't specify 'id_token' as the response_type")),
                  () -> assertThat("Does not contain log message", loggingOutputStream.toString(), not(containsString("an ID token was requested but 'openid' is missing from the requested scopes")))
                );
            }
        }
    }

    @Test
    public void ensureJKUHeaderIsSetWhenBuildingAnAccessToken() {
        AuthorizationRequest authorizationRequest = constructAuthorizationRequest(GRANT_TYPE_CLIENT_CREDENTIALS, Strings.split(clientScopes, ','));

        OAuth2Authentication authentication = new OAuth2Authentication(authorizationRequest.createOAuth2Request(), null);

        OAuth2AccessToken accessToken = tokenServices.createAccessToken(authentication);

        Jwt decode = JwtHelper.decode(accessToken.getValue());
        assertThat(decode.getHeader().getJku(), startsWith(uaaUrl));
        assertThat(decode.getHeader().getJku(), is("https://uaa.some.test.domain.com:555/uaa/token_keys"));
    }


    @Test
    public void ensureJKUHeaderIsSetWhenBuildingARefreshToken() {
        AuthorizationRequest authorizationRequest = constructAuthorizationRequest(GRANT_TYPE_PASSWORD, "oauth.approvals");

        OAuth2Authentication auth2Authentication = constructUserAuthenticationFromAuthzRequest(authorizationRequest, "admin", "uaa");

        CompositeToken accessToken = (CompositeToken) tokenServices.createAccessToken(auth2Authentication);

        Jwt jwtToken = JwtHelper.decode(accessToken.getRefreshToken().getValue());
        assertThat(jwtToken.getHeader().getJku(), startsWith(uaaUrl));
        assertThat(jwtToken.getHeader().getJku(), is("https://uaa.some.test.domain.com:555/uaa/token_keys"));
    }


    private OAuth2Authentication constructUserAuthenticationFromAuthzRequest(AuthorizationRequest authzRequest,
                                                                             String userId,
                                                                             String userOrigin,
                                                                             GrantedAuthority... authorities
    ) {
        UaaUser uaaUser = jdbcUaaUserDatabase.retrieveUserByName(userId, userOrigin);
        UaaPrincipal principal = new UaaPrincipal(uaaUser);
        UaaAuthentication userAuthentication = new UaaAuthentication(
          principal, null, Arrays.asList(authorities), null, true, System.currentTimeMillis()
        );
        return new OAuth2Authentication(authzRequest.createOAuth2Request(), userAuthentication);
    }

    private AuthorizationRequest constructAuthorizationRequest(String grantType, String... scopes) {
        AuthorizationRequest authorizationRequest = new AuthorizationRequest(clientId, Arrays.asList(scopes));
        Map<String, String> azParameters = new HashMap<>(authorizationRequest.getRequestParameters());
        azParameters.put(GRANT_TYPE, grantType);
        authorizationRequest.setRequestParameters(azParameters);
        return authorizationRequest;
    }
}