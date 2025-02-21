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
package org.springframework.security.oauth2.server.authorization;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.jdbc.support.lob.LobCreator;
import org.springframework.jdbc.support.lob.LobHandler;
import org.springframework.lang.Nullable;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * A JDBC implementation of an {@link OAuth2AuthorizationService} that uses a
 * {@link JdbcOperations} for {@link OAuth2Authorization} persistence.
 *
 * <p>
 * <b>NOTE:</b> This {@code OAuth2AuthorizationService} depends on the table definition
 * described in
 * "classpath:org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql" and
 * therefore MUST be defined in the database schema.
 *
 * @author Ovidiu Popa
 * @since 0.1.2
 * @see OAuth2AuthorizationService
 * @see OAuth2Authorization
 * @see JdbcOperations
 * @see RowMapper
 */
public class JdbcOAuth2AuthorizationService implements OAuth2AuthorizationService {

	// @formatter:off
	private static final String COLUMN_NAMES = "id, "
			+ "registered_client_id, "
			+ "principal_name, "
			+ "authorization_grant_type, "
			+ "attributes, "
			+ "state, "
			+ "authorization_code_value, "
			+ "authorization_code_issued_at, "
			+ "authorization_code_expires_at,"
			+ "authorization_code_metadata,"
			+ "access_token_value,"
			+ "access_token_issued_at,"
			+ "access_token_expires_at,"
			+ "access_token_metadata,"
			+ "access_token_type,"
			+ "access_token_scopes,"
			+ "oidc_id_token_value,"
			+ "oidc_id_token_issued_at,"
			+ "oidc_id_token_expires_at,"
			+ "oidc_id_token_metadata,"
			+ "refresh_token_value,"
			+ "refresh_token_issued_at,"
			+ "refresh_token_expires_at,"
			+ "refresh_token_metadata";
	// @formatter:on

	private static final String TABLE_NAME = "oauth2_authorization";

	private static final String PK_FILTER = "id = ?";
	private static final String UNKNOWN_TOKEN_TYPE_FILTER = "state = ? OR authorization_code_value = ? OR " +
			"access_token_value = ? OR refresh_token_value = ?";

	private static final String STATE_FILTER = "state = ?";
	private static final String AUTHORIZATION_CODE_FILTER = "authorization_code_value = ?";
	private static final String ACCESS_TOKEN_FILTER = "access_token_value = ?";
	private static final String REFRESH_TOKEN_FILTER = "refresh_token_value = ?";

	// @formatter:off
	private static final String LOAD_AUTHORIZATION_SQL = "SELECT " + COLUMN_NAMES
			+ " FROM " + TABLE_NAME
			+ " WHERE ";
	// @formatter:on

	// @formatter:off
	private static final String SAVE_AUTHORIZATION_SQL = "INSERT INTO " + TABLE_NAME
			+ " (" + COLUMN_NAMES + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
	// @formatter:on

	// @formatter:off
	private static final String UPDATE_AUTHORIZATION_SQL = "UPDATE " + TABLE_NAME
			+ " SET registered_client_id = ?, principal_name = ?, authorization_grant_type = ?, attributes = ?, state = ?,"
			+ " authorization_code_value = ?, authorization_code_issued_at = ?, authorization_code_expires_at = ?, authorization_code_metadata = ?,"
			+ " access_token_value = ?, access_token_issued_at = ?, access_token_expires_at = ?, access_token_metadata = ?, access_token_type = ?, access_token_scopes = ?,"
			+ " oidc_id_token_value = ?, oidc_id_token_issued_at = ?, oidc_id_token_expires_at = ?, oidc_id_token_metadata = ?,"
			+ " refresh_token_value = ?, refresh_token_issued_at = ?, refresh_token_expires_at = ?, refresh_token_metadata = ?"
			+ " WHERE " + PK_FILTER;
	// @formatter:on

	private static final String REMOVE_AUTHORIZATION_SQL = "DELETE FROM " + TABLE_NAME + " WHERE " + PK_FILTER;

	private final JdbcOperations jdbcOperations;
	private final LobHandler lobHandler;
	private RowMapper<OAuth2Authorization> authorizationRowMapper;
	private Function<OAuth2Authorization, List<SqlParameterValue>> authorizationParametersMapper;

	/**
	 * Constructs a {@code JdbcOAuth2AuthorizationService} using the provided parameters.
	 *
	 * @param jdbcOperations             the JDBC operations
	 * @param registeredClientRepository the registered client repository
	 */
	public JdbcOAuth2AuthorizationService(JdbcOperations jdbcOperations,
			RegisteredClientRepository registeredClientRepository) {
		this(jdbcOperations, registeredClientRepository, new DefaultLobHandler());
	}

	/**
	 * Constructs a {@code JdbcOAuth2AuthorizationService} using the provided parameters.
	 *
	 * @param jdbcOperations             the JDBC operations
	 * @param registeredClientRepository the registered client repository
	 * @param lobHandler                 the handler for large binary fields and large text fields
	 */
	public JdbcOAuth2AuthorizationService(JdbcOperations jdbcOperations,
			RegisteredClientRepository registeredClientRepository, LobHandler lobHandler) {
		Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
		Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
		Assert.notNull(lobHandler, "lobHandler cannot be null");
		this.jdbcOperations = jdbcOperations;
		this.lobHandler = lobHandler;
		OAuth2AuthorizationRowMapper authorizationRowMapper = new OAuth2AuthorizationRowMapper(registeredClientRepository);
		authorizationRowMapper.setLobHandler(lobHandler);
		this.authorizationRowMapper = authorizationRowMapper;
		this.authorizationParametersMapper = new OAuth2AuthorizationParametersMapper();
	}

	@Override
	public void save(OAuth2Authorization authorization) {
		Assert.notNull(authorization, "authorization cannot be null");
		OAuth2Authorization existingAuthorization = findById(authorization.getId());
		if (existingAuthorization == null) {
			insertAuthorization(authorization);
		} else {
			updateAuthorization(authorization);
		}
	}

	private void updateAuthorization(OAuth2Authorization authorization) {
		List<SqlParameterValue> parameters = this.authorizationParametersMapper.apply(authorization);
		SqlParameterValue id = parameters.remove(0);
		parameters.add(id);
		try (LobCreator lobCreator = this.lobHandler.getLobCreator()) {
			PreparedStatementSetter pss = new LobCreatorArgumentPreparedStatementSetter(lobCreator,
					parameters.toArray());
			this.jdbcOperations.update(UPDATE_AUTHORIZATION_SQL, pss);
		}
	}

	private void insertAuthorization(OAuth2Authorization authorization) {
		List<SqlParameterValue> parameters = this.authorizationParametersMapper.apply(authorization);
		try (LobCreator lobCreator = this.lobHandler.getLobCreator()) {
			PreparedStatementSetter pss = new LobCreatorArgumentPreparedStatementSetter(lobCreator,
					parameters.toArray());
			this.jdbcOperations.update(SAVE_AUTHORIZATION_SQL, pss);
		}
	}

	@Override
	public void remove(OAuth2Authorization authorization) {
		Assert.notNull(authorization, "authorization cannot be null");
		SqlParameterValue[] parameters = new SqlParameterValue[] {
				new SqlParameterValue(Types.VARCHAR, authorization.getId())
		};
		PreparedStatementSetter pss = new ArgumentPreparedStatementSetter(parameters);
		this.jdbcOperations.update(REMOVE_AUTHORIZATION_SQL, pss);
	}

	@Nullable
	@Override
	public OAuth2Authorization findById(String id) {
		Assert.hasText(id, "id cannot be empty");
		List<SqlParameterValue> parameters = new ArrayList<>();
		parameters.add(new SqlParameterValue(Types.VARCHAR, id));
		return findBy(PK_FILTER, parameters);
	}

	@Nullable
	@Override
	public OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
		Assert.hasText(token, "token cannot be empty");
		List<SqlParameterValue> parameters = new ArrayList<>();
		if (tokenType == null) {
			parameters.add(new SqlParameterValue(Types.VARCHAR, token));
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			return findBy(UNKNOWN_TOKEN_TYPE_FILTER, parameters);
		} else if (OAuth2ParameterNames.STATE.equals(tokenType.getValue())) {
			parameters.add(new SqlParameterValue(Types.VARCHAR, token));
			return findBy(STATE_FILTER, parameters);
		} else if (OAuth2ParameterNames.CODE.equals(tokenType.getValue())) {
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			return findBy(AUTHORIZATION_CODE_FILTER, parameters);
		} else if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			return findBy(ACCESS_TOKEN_FILTER, parameters);
		} else if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
			parameters.add(new SqlParameterValue(Types.BLOB, token.getBytes(StandardCharsets.UTF_8)));
			return findBy(REFRESH_TOKEN_FILTER, parameters);
		}
		return null;
	}

	private OAuth2Authorization findBy(String filter, List<SqlParameterValue> parameters) {
		PreparedStatementSetter pss = new ArgumentPreparedStatementSetter(parameters.toArray());
		List<OAuth2Authorization> result = this.jdbcOperations.query(LOAD_AUTHORIZATION_SQL + filter, pss, this.authorizationRowMapper);
		return !result.isEmpty() ? result.get(0) : null;
	}

	/**
	 * Sets the {@link RowMapper} used for mapping the current row in
	 * {@code java.sql.ResultSet} to {@link OAuth2Authorization}. The default is
	 * {@link OAuth2AuthorizationRowMapper}.
	 *
	 * @param authorizationRowMapper the {@link RowMapper} used for mapping the current
	 *                               row in {@code ResultSet} to {@link OAuth2Authorization}
	 */
	public final void setAuthorizationRowMapper(RowMapper<OAuth2Authorization> authorizationRowMapper) {
		Assert.notNull(authorizationRowMapper, "authorizationRowMapper cannot be null");
		this.authorizationRowMapper = authorizationRowMapper;
	}

	/**
	 * Sets the {@code Function} used for mapping {@link OAuth2Authorization} to
	 * a {@code List} of {@link SqlParameterValue}. The default is
	 * {@link OAuth2AuthorizationParametersMapper}.
	 *
	 * @param authorizationParametersMapper the {@code Function} used for mapping
	 *                                      {@link OAuth2Authorization} to a {@code List} of {@link SqlParameterValue}
	 */
	public final void setAuthorizationParametersMapper(
			Function<OAuth2Authorization, List<SqlParameterValue>> authorizationParametersMapper) {
		Assert.notNull(authorizationParametersMapper, "authorizationParametersMapper cannot be null");
		this.authorizationParametersMapper = authorizationParametersMapper;
	}

	protected final JdbcOperations getJdbcOperations() {
		return this.jdbcOperations;
	}

	protected final LobHandler getLobHandler() {
		return this.lobHandler;
	}

	protected final RowMapper<OAuth2Authorization> getAuthorizationRowMapper() {
		return this.authorizationRowMapper;
	}

	protected final Function<OAuth2Authorization, List<SqlParameterValue>> getAuthorizationParametersMapper() {
		return this.authorizationParametersMapper;
	}

	/**
	 * The default {@link RowMapper} that maps the current row in
	 * {@code java.sql.ResultSet} to {@link OAuth2Authorization}.
	 */
	public static class OAuth2AuthorizationRowMapper implements RowMapper<OAuth2Authorization> {
		private final RegisteredClientRepository registeredClientRepository;
		private LobHandler lobHandler = new DefaultLobHandler();
		private ObjectMapper objectMapper = new ObjectMapper();

		public OAuth2AuthorizationRowMapper(RegisteredClientRepository registeredClientRepository) {
			Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
			this.registeredClientRepository = registeredClientRepository;

			ClassLoader classLoader = JdbcOAuth2AuthorizationService.class.getClassLoader();
			List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
			this.objectMapper.registerModules(securityModules);
			this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
		}

		@Override
		@SuppressWarnings("unchecked")
		public OAuth2Authorization mapRow(ResultSet rs, int rowNum) throws SQLException {
			String registeredClientId = rs.getString("registered_client_id");
			RegisteredClient registeredClient = this.registeredClientRepository.findById(registeredClientId);
			if (registeredClient == null) {
				throw new DataRetrievalFailureException(
						"The RegisteredClient with id '" + registeredClientId + "' was not found in the RegisteredClientRepository.");
			}

			OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient);
			String id = rs.getString("id");
			String principalName = rs.getString("principal_name");
			String authorizationGrantType = rs.getString("authorization_grant_type");
			Map<String, Object> attributes = parseMap(rs.getString("attributes"));

			builder.id(id)
					.principalName(principalName)
					.authorizationGrantType(new AuthorizationGrantType(authorizationGrantType))
					.attributes((attrs) -> attrs.putAll(attributes));

			String state = rs.getString("state");
			if (StringUtils.hasText(state)) {
				builder.attribute(OAuth2ParameterNames.STATE, state);
			}

			String tokenValue;
			Instant tokenIssuedAt;
			Instant tokenExpiresAt;
			byte[] authorizationCodeValue = this.lobHandler.getBlobAsBytes(rs, "authorization_code_value");

			if (authorizationCodeValue != null) {
				tokenValue = new String(authorizationCodeValue, StandardCharsets.UTF_8);
				tokenIssuedAt = rs.getTimestamp("authorization_code_issued_at").toInstant();
				tokenExpiresAt = rs.getTimestamp("authorization_code_expires_at").toInstant();
				Map<String, Object> authorizationCodeMetadata = parseMap(rs.getString("authorization_code_metadata"));

				OAuth2AuthorizationCode authorizationCode = new OAuth2AuthorizationCode(
						tokenValue, tokenIssuedAt, tokenExpiresAt);
				builder.token(authorizationCode, (metadata) -> metadata.putAll(authorizationCodeMetadata));
			}

			byte[] accessTokenValue = this.lobHandler.getBlobAsBytes(rs, "access_token_value");
			if (accessTokenValue != null) {
				tokenValue = new String(accessTokenValue, StandardCharsets.UTF_8);
				tokenIssuedAt = rs.getTimestamp("access_token_issued_at").toInstant();
				tokenExpiresAt = rs.getTimestamp("access_token_expires_at").toInstant();
				Map<String, Object> accessTokenMetadata = parseMap(rs.getString("access_token_metadata"));
				OAuth2AccessToken.TokenType tokenType = null;
				if (OAuth2AccessToken.TokenType.BEARER.getValue().equalsIgnoreCase(rs.getString("access_token_type"))) {
					tokenType = OAuth2AccessToken.TokenType.BEARER;
				}

				Set<String> scopes = Collections.emptySet();
				String accessTokenScopes = rs.getString("access_token_scopes");
				if (accessTokenScopes != null) {
					scopes = StringUtils.commaDelimitedListToSet(accessTokenScopes);
				}
				OAuth2AccessToken accessToken = new OAuth2AccessToken(tokenType, tokenValue, tokenIssuedAt, tokenExpiresAt, scopes);
				builder.token(accessToken, (metadata) -> metadata.putAll(accessTokenMetadata));
			}

			byte[] oidcIdTokenValue = this.lobHandler.getBlobAsBytes(rs, "oidc_id_token_value");
			if (oidcIdTokenValue != null) {
				tokenValue = new String(oidcIdTokenValue, StandardCharsets.UTF_8);
				tokenIssuedAt = rs.getTimestamp("oidc_id_token_issued_at").toInstant();
				tokenExpiresAt = rs.getTimestamp("oidc_id_token_expires_at").toInstant();
				Map<String, Object> oidcTokenMetadata = parseMap(rs.getString("oidc_id_token_metadata"));

				OidcIdToken oidcToken = new OidcIdToken(
						tokenValue, tokenIssuedAt, tokenExpiresAt, (Map<String, Object>) oidcTokenMetadata.get(OAuth2Authorization.Token.CLAIMS_METADATA_NAME));
				builder.token(oidcToken, (metadata) -> metadata.putAll(oidcTokenMetadata));
			}

			byte[] refreshTokenValue = this.lobHandler.getBlobAsBytes(rs, "refresh_token_value");
			if (refreshTokenValue != null) {
				tokenValue = new String(refreshTokenValue, StandardCharsets.UTF_8);
				tokenIssuedAt = rs.getTimestamp("refresh_token_issued_at").toInstant();
				tokenExpiresAt = null;
				Timestamp refreshTokenExpiresAt = rs.getTimestamp("refresh_token_expires_at");
				if (refreshTokenExpiresAt != null) {
					tokenExpiresAt = refreshTokenExpiresAt.toInstant();
				}
				Map<String, Object> refreshTokenMetadata = parseMap(rs.getString("refresh_token_metadata"));

				OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
						tokenValue, tokenIssuedAt, tokenExpiresAt);
				builder.token(refreshToken, (metadata) -> metadata.putAll(refreshTokenMetadata));
			}
			return builder.build();
		}

		public final void setLobHandler(LobHandler lobHandler) {
			Assert.notNull(lobHandler, "lobHandler cannot be null");
			this.lobHandler = lobHandler;
		}

		public final void setObjectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper cannot be null");
			this.objectMapper = objectMapper;
		}

		protected final RegisteredClientRepository getRegisteredClientRepository() {
			return this.registeredClientRepository;
		}

		protected final LobHandler getLobHandler() {
			return this.lobHandler;
		}

		protected final ObjectMapper getObjectMapper() {
			return this.objectMapper;
		}

		private Map<String, Object> parseMap(String data) {
			try {
				return this.objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
			} catch (Exception ex) {
				throw new IllegalArgumentException(ex.getMessage(), ex);
			}
		}

	}

	/**
	 * The default {@code Function} that maps {@link OAuth2Authorization} to a
	 * {@code List} of {@link SqlParameterValue}.
	 */
	public static class OAuth2AuthorizationParametersMapper implements Function<OAuth2Authorization, List<SqlParameterValue>> {
		private ObjectMapper objectMapper = new ObjectMapper();

		public OAuth2AuthorizationParametersMapper() {
			ClassLoader classLoader = JdbcOAuth2AuthorizationService.class.getClassLoader();
			List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
			this.objectMapper.registerModules(securityModules);
			this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
		}

		@Override
		public List<SqlParameterValue> apply(OAuth2Authorization authorization) {
			List<SqlParameterValue> parameters = new ArrayList<>();
			parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getId()));
			parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getRegisteredClientId()));
			parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getPrincipalName()));
			parameters.add(new SqlParameterValue(Types.VARCHAR, authorization.getAuthorizationGrantType().getValue()));

			String attributes = writeMap(authorization.getAttributes());
			parameters.add(new SqlParameterValue(Types.VARCHAR, attributes));

			String state = null;
			String authorizationState = authorization.getAttribute(OAuth2ParameterNames.STATE);
			if (StringUtils.hasText(authorizationState)) {
				state = authorizationState;
			}
			parameters.add(new SqlParameterValue(Types.VARCHAR, state));

			OAuth2Authorization.Token<OAuth2AuthorizationCode> authorizationCode =
					authorization.getToken(OAuth2AuthorizationCode.class);
			List<SqlParameterValue> authorizationCodeSqlParameters = toSqlParameterList(authorizationCode);
			parameters.addAll(authorizationCodeSqlParameters);

			OAuth2Authorization.Token<OAuth2AccessToken> accessToken =
					authorization.getToken(OAuth2AccessToken.class);
			List<SqlParameterValue> accessTokenSqlParameters = toSqlParameterList(accessToken);
			parameters.addAll(accessTokenSqlParameters);
			String accessTokenType = null;
			String accessTokenScopes = null;
			if (accessToken != null) {
				accessTokenType = accessToken.getToken().getTokenType().getValue();
				if (!CollectionUtils.isEmpty(accessToken.getToken().getScopes())) {
					accessTokenScopes = StringUtils.collectionToDelimitedString(accessToken.getToken().getScopes(), ",");
				}
			}
			parameters.add(new SqlParameterValue(Types.VARCHAR, accessTokenType));
			parameters.add(new SqlParameterValue(Types.VARCHAR, accessTokenScopes));

			OAuth2Authorization.Token<OidcIdToken> oidcIdToken = authorization.getToken(OidcIdToken.class);
			List<SqlParameterValue> oidcIdTokenSqlParameters = toSqlParameterList(oidcIdToken);
			parameters.addAll(oidcIdTokenSqlParameters);

			OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
			List<SqlParameterValue> refreshTokenSqlParameters = toSqlParameterList(refreshToken);
			parameters.addAll(refreshTokenSqlParameters);
			return parameters;
		}

		public final void setObjectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper cannot be null");
			this.objectMapper = objectMapper;
		}

		protected final ObjectMapper getObjectMapper() {
			return this.objectMapper;
		}

		private <T extends AbstractOAuth2Token> List<SqlParameterValue> toSqlParameterList(OAuth2Authorization.Token<T> token) {
			List<SqlParameterValue> parameters = new ArrayList<>();
			byte[] tokenValue = null;
			Timestamp tokenIssuedAt = null;
			Timestamp tokenExpiresAt = null;
			String metadata = null;
			if (token != null) {
				tokenValue = token.getToken().getTokenValue().getBytes(StandardCharsets.UTF_8);
				if (token.getToken().getIssuedAt() != null) {
					tokenIssuedAt = Timestamp.from(token.getToken().getIssuedAt());
				}
				if (token.getToken().getExpiresAt() != null) {
					tokenExpiresAt = Timestamp.from(token.getToken().getExpiresAt());
				}
				metadata = writeMap(token.getMetadata());
			}
			parameters.add(new SqlParameterValue(Types.BLOB, tokenValue));
			parameters.add(new SqlParameterValue(Types.TIMESTAMP, tokenIssuedAt));
			parameters.add(new SqlParameterValue(Types.TIMESTAMP, tokenExpiresAt));
			parameters.add(new SqlParameterValue(Types.VARCHAR, metadata));
			return parameters;
		}

		private String writeMap(Map<String, Object> data) {
			try {
				return this.objectMapper.writeValueAsString(data);
			} catch (Exception ex) {
				throw new IllegalArgumentException(ex.getMessage(), ex);
			}
		}

	}

	private static final class LobCreatorArgumentPreparedStatementSetter extends ArgumentPreparedStatementSetter {
		private final LobCreator lobCreator;

		private LobCreatorArgumentPreparedStatementSetter(LobCreator lobCreator, Object[] args) {
			super(args);
			this.lobCreator = lobCreator;
		}

		@Override
		protected void doSetValue(PreparedStatement ps, int parameterPosition, Object argValue) throws SQLException {
			if (argValue instanceof SqlParameterValue) {
				SqlParameterValue paramValue = (SqlParameterValue) argValue;
				if (paramValue.getSqlType() == Types.BLOB) {
					if (paramValue.getValue() != null) {
						Assert.isInstanceOf(byte[].class, paramValue.getValue(),
								"Value of blob parameter must be byte[]");
					}
					byte[] valueBytes = (byte[]) paramValue.getValue();
					this.lobCreator.setBlobAsBytes(ps, parameterPosition, valueBytes);
					return;
				}
			}
			super.doSetValue(ps, parameterPosition, argValue);
		}

	}

}
