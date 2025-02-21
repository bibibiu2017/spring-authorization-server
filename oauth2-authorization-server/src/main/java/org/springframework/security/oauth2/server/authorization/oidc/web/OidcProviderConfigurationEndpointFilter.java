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
package org.springframework.security.oauth2.server.authorization.oidc.web;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.core.oidc.OidcProviderConfiguration;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.http.converter.OidcProviderConfigurationHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.config.ProviderSettings;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A {@code Filter} that processes OpenID Provider Configuration Requests.
 *
 * @author Daniel Garnier-Moiroux
 * @author Arthur Mita
 * @since 0.1.0
 * @see OidcProviderConfiguration
 * @see ProviderSettings
 * @see <a target="_blank" href="https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationRequest">4.1. OpenID Provider Configuration Request</a>
 */
public final class OidcProviderConfigurationEndpointFilter extends OncePerRequestFilter {
	/**
	 * The default endpoint {@code URI} for OpenID Provider Configuration requests.
	 */
	private static final String DEFAULT_OIDC_PROVIDER_CONFIGURATION_ENDPOINT_URI = "/.well-known/openid-configuration";

	private final ProviderSettings providerSettings;
	private final RequestMatcher requestMatcher;
	private final OidcProviderConfigurationHttpMessageConverter providerConfigurationHttpMessageConverter =
			new OidcProviderConfigurationHttpMessageConverter();

	public OidcProviderConfigurationEndpointFilter(ProviderSettings providerSettings) {
		Assert.notNull(providerSettings, "providerSettings cannot be null");
		this.providerSettings = providerSettings;
		this.requestMatcher = new AntPathRequestMatcher(
				DEFAULT_OIDC_PROVIDER_CONFIGURATION_ENDPOINT_URI,
				HttpMethod.GET.name()
		);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		if (!this.requestMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		OidcProviderConfiguration providerConfiguration = OidcProviderConfiguration.builder()
				.issuer(this.providerSettings.getIssuer())
				.authorizationEndpoint(asUrl(this.providerSettings.getIssuer(), this.providerSettings.getAuthorizationEndpoint()))
				.tokenEndpoint(asUrl(this.providerSettings.getIssuer(), this.providerSettings.getTokenEndpoint()))
				.tokenEndpointAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue())
				.tokenEndpointAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue())
				.jwkSetUrl(asUrl(this.providerSettings.getIssuer(), this.providerSettings.getJwkSetEndpoint()))
				.responseType(OAuth2AuthorizationResponseType.CODE.getValue())
				.grantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
				.grantType(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.grantType(AuthorizationGrantType.REFRESH_TOKEN.getValue())
				.subjectType("public")
				.idTokenSigningAlgorithm(SignatureAlgorithm.RS256.getName())
				.clientRegistrationEndpoint(asUrl(this.providerSettings.getIssuer(), this.providerSettings.getOidcClientRegistrationEndpoint()))
				.scope(OidcScopes.OPENID)
				.build();

		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		this.providerConfigurationHttpMessageConverter.write(
				providerConfiguration, MediaType.APPLICATION_JSON, httpResponse);
	}

	private static String asUrl(String issuer, String endpoint) {
		return UriComponentsBuilder.fromUriString(issuer).path(endpoint).build().toUriString();
	}
}
