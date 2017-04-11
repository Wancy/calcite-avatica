/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.avatica.server;

import org.apache.calcite.avatica.ConnectionSpec;
import org.apache.calcite.avatica.jdbc.JdbcMeta;
import org.apache.calcite.avatica.remote.AuthenticationType;
import org.apache.calcite.avatica.remote.Driver;
import org.apache.calcite.avatica.remote.LocalService;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Callable;

import javax.servlet.http.HttpServletRequest;

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Test class for HTTP Basic authentication.
 */
public class RemoteUserExtractHttpServerTest extends HttpAuthBase {
  private static final Logger LOG = LoggerFactory.getLogger(RemoteUserExtractHttpServerTest.class);

  private static final ConnectionSpec CONNECTION_SPEC = ConnectionSpec.HSQLDB;
  private static HttpServer server;
  private static String url1;
  private static String url2;

  @BeforeClass public static void startServer() throws Exception {

    final String userPropertiesFile = BasicAuthHttpServerTest.class
            .getResource("/auth-users.properties").getFile();
    assertNotNull("Could not find properties file for basic auth users", userPropertiesFile);

    // Create a LocalService around HSQLDB
    final JdbcMeta jdbcMeta = new JdbcMeta(CONNECTION_SPEC.url,
            CONNECTION_SPEC.username, CONNECTION_SPEC.password);
    LocalService service = new LocalService(jdbcMeta);

    HandlerFactory factory = new HandlerFactory();
    AvaticaHandler avaticaHandler = factory.getHandler(service, Driver.Serialization.PROTOBUF, null,
        new AvaticaServerConfiguration() {
          @Override public AuthenticationType getAuthenticationType() {
            return AuthenticationType.BASIC;
          }

          @Override public String getKerberosRealm() {
            return null;
          }

          @Override public String getKerberosPrincipal() {
            return null;
          }

          @Override public boolean supportsImpersonation() {
            return true;
          }

          @Override public <T> T doAsRemoteUser(String remoteUserName, String remoteAddress,
                                                Callable<T> action) throws Exception {

            if (remoteUserName.equals("USER4")) {
              throw new RuntimeException("USER4 is a disallowed user");
            } else if (remoteUserName.equals("USER2")) {
              return action.call();
            } else {
              throw new RuntimeException("Unknown user.");
            }
          }

          @Override public RemoteUserExtractor getRemoteUserExtractor() {
            return new RemoteUserExtractor() {
              HttpQueryStringParameterRemoteUserExtractor paramRemoteUserExtractor =
                      new HttpQueryStringParameterRemoteUserExtractor();
              HttpRequestRemoteUserExtractor requestRemoteUserExtractor =
                      new HttpRequestRemoteUserExtractor();

              @Override public String extract(HttpServletRequest request)
                  throws RemoteUserExtractionException {
                if (request.getParameter("doAs") != null) {
                  String doAsUser = request.getParameter("doAs");
                  LOG.info("doAsUser is " + doAsUser);
                  return paramRemoteUserExtractor.extract(request);
                } else {
                  return "USER2";
                }

              }
            };
          }

          @Override public String[] getAllowedRoles() {
            return new String[] { "users" };
          }

          @Override public String getHashLoginServiceRealm() {
            return "Avatica";
          }

          @Override public String getHashLoginServiceProperties() {
            return userPropertiesFile;
          }
        });

    server = new HttpServer.Builder()
            .withHandler(avaticaHandler)
            .withPort(0)
            .build();
    server.start();

    url1 = "jdbc:avatica:remote:url=http://localhost:" + server.getPort()
            + ";authentication=BASIC;serialization=PROTOBUF";

    url2 = "jdbc:avatica:remote:url=http://localhost:" + server.getPort()
            + "?doAs=USER4" + ";authentication=BASIC;serialization=PROTOBUF";

    // Create and grant permissions to our users
    createHsqldbUsers();
  }

  @AfterClass public static void stopServer() throws Exception {
    if (null != server) {
      server.stop();
    }
  }

  @Test public void testUserWithAllowedDoAsRole() throws Exception {
    // Disallowed by avatica
    final Properties props = new Properties();
    props.put("avatica_user", "USER2");
    props.put("avatica_password", "password2");

    readWriteData(url1, "ALLOWED_doAs_AVATICA_USER", props);
  }

  @Test public void testUserWithDisallowedDoAsRole() throws Exception {
    // Disallowed by avatica
    final Properties props = new Properties();
    props.put("avatica_user", "USER2");
    props.put("avatica_password", "password2");

    try {
      readWriteData(url2, "DISALLOWED_doAs_AVATICA_USER", props);
      fail("Expected an exception");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), containsString("USER4 is a disallowed user"));
    }
  }

}

// End RemoteUserExtractHttpServerTest.java
