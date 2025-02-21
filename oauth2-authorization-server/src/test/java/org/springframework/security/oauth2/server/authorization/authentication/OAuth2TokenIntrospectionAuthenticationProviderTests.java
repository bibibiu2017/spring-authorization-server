/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.TestJwtClaimsSets;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.TestOAuth2Authorizations;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OAuth2TokenIntrospectionAuthenticationProvider}.
 *
 * @author Gerardo Roza
 * @author Joe Grandja
 */
public class OAuth2TokenIntrospectionAuthenticationProviderTests {
	private RegisteredClientRepository registeredClientRepository;
	private OAuth2AuthorizationService authorizationService;
	private OAuth2TokenIntrospectionAuthenticationProvider authenticationProvider;

	@Before
	public void setUp() {
		this.registeredClientRepository = mock(RegisteredClientRepository.class);
		this.authorizationService = mock(OAuth2AuthorizationService.class);
		this.authenticationProvider = new OAuth2TokenIntrospectionAuthenticationProvider(
				this.registeredClientRepository, this.authorizationService);
	}

	@Test
	public void constructorWhenRegisteredClientRepositoryNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new OAuth2TokenIntrospectionAuthenticationProvider(null, this.authorizationService))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("registeredClientRepository cannot be null");
	}

	@Test
	public void constructorWhenAuthorizationServiceNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new OAuth2TokenIntrospectionAuthenticationProvider(this.registeredClientRepository, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationService cannot be null");
	}

	@Test
	public void supportsWhenTypeOAuth2TokenIntrospectionAuthenticationTokenThenReturnTrue() {
		assertThat(this.authenticationProvider.supports(OAuth2TokenIntrospectionAuthenticationToken.class)).isTrue();
	}

	@Test
	public void authenticateWhenClientPrincipalNotOAuth2ClientAuthenticationTokenThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		TestingAuthenticationToken clientPrincipal = new TestingAuthenticationToken(
				registeredClient.getClientId(), registeredClient.getClientSecret());

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				"token", clientPrincipal, null, null);

		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError()).extracting("errorCode")
				.isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
	}

	@Test
	public void authenticateWhenClientPrincipalNotAuthenticatedThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), registeredClient.getClientSecret(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null);

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				"token", clientPrincipal, null, null);

		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError()).extracting("errorCode")
				.isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
	}

	@Test
	public void authenticateWhenInvalidTokenThenNotActive() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				"token", clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		assertThat(authenticationResult.isAuthenticated()).isFalse();
		assertThat(authenticationResult.getTokenClaims().getClaims()).hasSize(1);
		assertThat(authenticationResult.getTokenClaims().isActive()).isFalse();
	}

	@Test
	public void authenticateWhenTokenInvalidatedThenNotActive() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		OAuth2Authorization authorization = TestOAuth2Authorizations.authorization(registeredClient).build();
		OAuth2AccessToken accessToken = authorization.getAccessToken().getToken();
		authorization = OAuth2AuthenticationProviderUtils.invalidate(authorization, accessToken);
		when(this.authorizationService.findByToken(eq(accessToken.getTokenValue()), isNull()))
				.thenReturn(authorization);
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				accessToken.getTokenValue(), clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getTokenClaims().getClaims()).hasSize(1);
		assertThat(authenticationResult.getTokenClaims().isActive()).isFalse();
	}

	@Test
	public void authenticateWhenTokenExpiredThenNotActive() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		Instant issuedAt = Instant.now().minus(Duration.ofHours(1));
		Instant expiresAt = Instant.now().minus(Duration.ofMinutes(1));
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "access-token", issuedAt, expiresAt);
		OAuth2Authorization authorization = TestOAuth2Authorizations.authorization(registeredClient).token(accessToken).build();
		when(this.authorizationService.findByToken(eq(accessToken.getTokenValue()), isNull()))
				.thenReturn(authorization);
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				accessToken.getTokenValue(), clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getTokenClaims().getClaims()).hasSize(1);
		assertThat(authenticationResult.getTokenClaims().isActive()).isFalse();
	}

	@Test
	public void authenticateWhenTokenBeforeUseThenNotActive() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		Instant issuedAt = Instant.now();
		Instant notBefore = issuedAt.plus(Duration.ofMinutes(5));
		Instant expiresAt = issuedAt.plus(Duration.ofHours(1));
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "access-token", issuedAt, expiresAt);
		Map<String, Object> accessTokenClaims = Collections.singletonMap(JwtClaimNames.NBF, notBefore);
		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(registeredClient, accessToken, accessTokenClaims)
				.build();
		when(this.authorizationService.findByToken(eq(accessToken.getTokenValue()), isNull()))
				.thenReturn(authorization);
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				accessToken.getTokenValue(), clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getTokenClaims().getClaims()).hasSize(1);
		assertThat(authenticationResult.getTokenClaims().isActive()).isFalse();
	}

	@Test
	public void authenticateWhenValidAccessTokenThenActive() {
		RegisteredClient authorizedClient = TestRegisteredClients.registeredClient().build();
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(Duration.ofHours(1));
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "access-token", issuedAt, expiresAt,
				new HashSet<>(Arrays.asList("scope1", "scope2")));
		JwtClaimsSet jwtClaims = TestJwtClaimsSets.jwtClaimsSet().build();
		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(authorizedClient, accessToken, jwtClaims.getClaims())
				.build();
		when(this.authorizationService.findByToken(eq(accessToken.getTokenValue()), isNull()))
				.thenReturn(authorization);
		when(this.registeredClientRepository.findById(eq(authorizedClient.getId()))).thenReturn(authorizedClient);
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
				TestRegisteredClients.registeredClient2().build());

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				accessToken.getTokenValue(), clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		verify(this.registeredClientRepository).findById(eq(authorizedClient.getId()));
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		OAuth2TokenIntrospection tokenClaims = authenticationResult.getTokenClaims();
		assertThat(tokenClaims.isActive()).isTrue();
		assertThat(tokenClaims.getClientId()).isEqualTo(authorizedClient.getClientId());
		assertThat(tokenClaims.getIssuedAt()).isEqualTo(accessToken.getIssuedAt());
		assertThat(tokenClaims.getExpiresAt()).isEqualTo(accessToken.getExpiresAt());
		assertThat(tokenClaims.getScopes()).containsExactlyInAnyOrderElementsOf(accessToken.getScopes());
		assertThat(tokenClaims.getTokenType()).isEqualTo(accessToken.getTokenType().getValue());
		assertThat(tokenClaims.getNotBefore()).isEqualTo(jwtClaims.getNotBefore());
		assertThat(tokenClaims.getSubject()).isEqualTo(jwtClaims.getSubject());
		assertThat(tokenClaims.getAudience()).containsExactlyInAnyOrderElementsOf(jwtClaims.getAudience());
		assertThat(tokenClaims.getIssuer()).isEqualTo(jwtClaims.getIssuer());
		assertThat(tokenClaims.getId()).isEqualTo(jwtClaims.getId());
	}

	@Test
	public void authenticateWhenValidRefreshTokenThenActive() {
		RegisteredClient authorizedClient = TestRegisteredClients.registeredClient().build();
		OAuth2Authorization authorization = TestOAuth2Authorizations.authorization().build();
		OAuth2RefreshToken refreshToken = authorization.getRefreshToken().getToken();
		when(this.authorizationService.findByToken(eq(refreshToken.getTokenValue()), isNull()))
				.thenReturn(authorization);
		when(this.registeredClientRepository.findById(eq(authorizedClient.getId()))).thenReturn(authorizedClient);
		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
				TestRegisteredClients.registeredClient2().build());

		OAuth2TokenIntrospectionAuthenticationToken authentication = new OAuth2TokenIntrospectionAuthenticationToken(
				refreshToken.getTokenValue(), clientPrincipal, null, null);
		OAuth2TokenIntrospectionAuthenticationToken authenticationResult =
				(OAuth2TokenIntrospectionAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(authentication.getToken()), isNull());
		verify(this.registeredClientRepository).findById(eq(authorizedClient.getId()));
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		OAuth2TokenIntrospection tokenClaims = authenticationResult.getTokenClaims();
		assertThat(tokenClaims.getClaims()).hasSize(4);
		assertThat(tokenClaims.isActive()).isTrue();
		assertThat(tokenClaims.getClientId()).isEqualTo(authorizedClient.getClientId());
		assertThat(tokenClaims.getIssuedAt()).isEqualTo(refreshToken.getIssuedAt());
		assertThat(tokenClaims.getExpiresAt()).isEqualTo(refreshToken.getExpiresAt());
	}

}
