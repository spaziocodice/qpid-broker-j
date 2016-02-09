/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.server.security.auth.manager.oauth2.github;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.TrustStore;
import org.apache.qpid.server.plugin.PluggableService;
import org.apache.qpid.server.security.auth.UsernamePrincipal;
import org.apache.qpid.server.security.auth.manager.oauth2.IdentityResolverException;
import org.apache.qpid.server.security.auth.manager.oauth2.OAuth2AuthenticationProvider;
import org.apache.qpid.server.security.auth.manager.oauth2.OAuth2IdentityResolverService;
import org.apache.qpid.server.security.auth.manager.oauth2.OAuth2Utils;

/**
 * An identity resolver that calls GitHubs's user API https://developer.github.com/v3/users/
 *
 * It requires that the authentication request includes the scope 'user'
 *
 */
@PluggableService
public class GitHubOAuth2IdentityResolverService implements OAuth2IdentityResolverService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubOAuth2IdentityResolverService.class);
    private static final String UTF8 = StandardCharsets.UTF_8.name();

    public static final String TYPE = "GitHubUser";
    
    private final ObjectMapper _objectMapper = new ObjectMapper();

    @Override
    public String getType()
    {
        return TYPE;
    }

    @Override
    public void validate(final OAuth2AuthenticationProvider<?> authProvider) throws IllegalConfigurationException
    {
        if (!Sets.newHashSet(authProvider.getScope().split("\\s")).contains("user"))
        {
            throw new IllegalConfigurationException("This identity resolver requires that scope 'user' is included in"
                                               + " the authentication request.");
        }
    }

    @Override
    public Principal getUserPrincipal(final OAuth2AuthenticationProvider<?> authenticationProvider,
                                      String accessToken) throws IOException, IdentityResolverException
    {
        URI userInfoEndpoint = authenticationProvider.getIdentityResolverEndpointURI();
        TrustStore trustStore = authenticationProvider.getTrustStore();

        LOGGER.debug("About to call identity service '{}'", userInfoEndpoint);

        HttpsURLConnection connection = (HttpsURLConnection) userInfoEndpoint.toURL().openConnection();
        if (trustStore != null)
        {
            OAuth2Utils.setTrustedCertificates(connection, trustStore);
        }

        connection.setRequestProperty("Accept-Charset", UTF8);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + UTF8);
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("Authorization", "token " + accessToken);

        connection.connect();

        try (InputStream input = connection.getInputStream())
        {
            int responseCode = connection.getResponseCode();
            LOGGER.debug("Call to identity service '{}' complete, response code : {}",
                         userInfoEndpoint, responseCode);

            Map<String, String> responseMap;
            try
            {
                responseMap = _objectMapper.readValue(input, Map.class);
            }
            catch (JsonProcessingException e)
            {
                throw new IOException(String.format("Identity resolver '%s' did not return json",
                                                    userInfoEndpoint), e);
            }
            if (responseCode != 200)
            {
                throw new IdentityResolverException(String.format(
                        "Identity resolver '%s' failed, response code %d",
                        userInfoEndpoint, responseCode));
            }

            final String githubId = responseMap.get("login");
            if (githubId == null)
            {
                throw new IdentityResolverException(String.format(
                        "Identity resolver '%s' failed, response did not include 'login'",
                        userInfoEndpoint));
            }
            return new UsernamePrincipal(githubId);
        }
    }
}
