/*
 * Copyright (c) 1998-2021 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.util.net;

import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thredds.test.util.TdsUnitTestCommon;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPSession;
import ucar.unidata.util.test.TestDir;
import ucar.unidata.util.test.category.NotPullRequest;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

/**
 * This test is to check non-ssh related authorization: Basic primarily.
 * It currently cannot be run except manually under Intellij.
 * Notes:
 * 1. Thredds currently suppports two authorization mechanisms: spring
 * and container-based (i.e. tomcat server.xml) This class tests
 * pure container-based authorization
 * 2. Because this code need to depend on the tomcat server.xml being
 * properly set up, it cannot be run under Jenkins or GitHub Actions.
 * 3. This requires, for tomcat 8, that the following be in tomcat-users.xml.
 * <role rolename="containerauth"/>
 * <user username="authuser" password="authpwd" roles="containerauth"/>
 * That user may need other roles to also work with spring authorization.
 * 4. The web.xml file ./tds/src/main/webapp/WEB-INF/web.xml
 * also need to have the following:
 * <security-constraint>
 * <web-resource-collection>
 * <web-resource-name>tomcat only testing</web-resource-name>
 * <url-pattern>/containerauth/*</url-pattern>
 * </web-resource-collection>
 * <auth-constraint>
 * <role-name>containerauth</role-name>
 * </auth-constraint>
 * <!-- Do not require 'https' -->
 * <user-data-constraint>
 * <transport-guarantee>NONE</transport-guarantee>
 * </user-data-constraint>
 * </security-constraint>
 * <security-role>
 * <description>User who can access restricted datasets.</description>
 * <role-name>containerauth</role-name>
 * </security-role>
 *
 * 5. The catalog.xml file ./tds/src/test/content/thredds/catalog.xml
 * needs to have some url mappings and dataset defined.
 * <datasetRoot path="containerauth" location="content/testdata"/>
 * <dataset name="Test Container Authorization"
 * ID="testcontainerauth"
 * serviceName="all"
 * urlPath="containerauth/testData2.nc"
 * restrictAccess="containerauth"/>
 *
 */
@Category(NotPullRequest.class)
public class TestTomcatAuth extends TdsUnitTestCommon {
  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  static final String BADPASSWORD = "bad";

  static protected final String MODULE = "httpclient";

  static protected final boolean IGNORE = true;

  //////////////////////////////////////////////////
  // Provide a non-interactive TestProvider to hold
  // the user+pwd; used in several places
  static class TestProvider implements CredentialsProvider, Serializable {
    String username = null;
    String password = null;
    int counter = 0;

    public TestProvider(String username, String password) {
      setUserPwd(username, password);
    }

    public void setUserPwd(String username, String password) {
      this.username = username;
      this.password = password;
    }

    // Credentials Provider Interface
    public Credentials getCredentials(AuthScope scope) // AuthScheme authscheme, String host, int port, boolean isproxy)
    {
      UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);
      logger.info("TestCredentials.getCredentials called: creds=|{}| host={} port={}}", creds.toString(),
          scope.getHost(), scope.getPort());
      this.counter++;
      return creds;
    }

    public void setCredentials(AuthScope scope, Credentials creds) {
      throw new UnsupportedOperationException();
    }

    public void clear() {
      this.username = null;
      this.password = null;
    }

    // Serializable Interface
    private void writeObject(java.io.ObjectOutputStream oos) throws IOException {
      oos.writeObject(this.username);
      oos.writeObject(this.password);
    }

    private void readObject(java.io.ObjectInputStream ois) throws IOException, ClassNotFoundException {
      this.username = (String) ois.readObject();
      this.password = (String) ois.readObject();
    }
  }

  //////////////////////////////////////////////////
  // Add a temporary control for remote versus localhost

  protected String server = null;

  // Define the test sets

  protected String datadir = null;
  protected String threddsroot = null;

  //////////////////////////////////////////////////
  // Constructor(s)

  public TestTomcatAuth() {
    super("TestTomcatAuth");
    setTitle("Authorization tests");
    // HTTPSession.debugHeaders(true);
    HTTPSession.TESTING = true;
    this.server = TestDir.remoteTestServer;
    defineTestCases();
  }

  //////////////////////////////////////////////////

  static public class Result {
    public int status = 0;
    public byte[] contents = null;

    public String toString() {
      return String.format("{status=%d |contents|=%d}", status, (contents == null ? 0 : contents.length));
    }
  }

  static public void report(Result result) {
    report(result, null);
  }

  static public void report(Result result, Integer counter) {
    logger.info("Result: code={} content?={} provider-calls={}", result.status, result.contents.length, counter);
  }

  static class AuthDataBasic {
    String url;
    String user = null;
    String password = null;

    public AuthDataBasic(String url, String usr, String pwd) {
      this.url = url;
      this.user = usr;
      this.password = pwd;
    }

    public String toString() {
      StringBuilder b = new StringBuilder();
      b.append("{");
      b.append("url=|");
      b.append(url);
      b.append("|");
      b.append(", userpwd=");
      b.append(user);
      b.append(":");
      b.append(password);
      b.append("}");
      return b.toString();
    }

    /**
     * Return url with embedded user+pwd
     */
    public String inline() throws Exception {
      StringBuilder buf = new StringBuilder(this.url);
      int pos = buf.indexOf("://");
      pos += 3;
      buf.insert(pos, "@");
      buf.insert(pos, this.password);
      buf.insert(pos, ":");
      buf.insert(pos, this.user);
      return buf.toString();
    }
  }

  protected List<AuthDataBasic> basictests = new ArrayList<>();

  protected void defineTestCases() {
    basictests.add(new AuthDataBasic("http://" + this.server + "/thredds/dodsC/containerauth/testData2.nc.dds", "tds",
        "secret666"));
  }

  @Test
  public void testBasic() throws Exception {
    Result result = null;
    TestProvider provider = null;

    logger.info("*** Testing: Http Basic Password Authorization");
    for (AuthDataBasic data : basictests) {
      logger.info("Test global credentials provider");
      logger.info("*** URL: " + data.url);

      provider = new TestProvider(data.user, data.password);

      // Test global credentials provider
      HTTPSession.setGlobalCredentialsProvider(provider);

      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        result = invoke(session, data.url);
        report(result, provider.counter);
      }

      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status));
      Assert.assertTrue("no content", result.contents.length > 0);
      Assert.assertTrue("Cre  dentials provider called: " + provider.counter, provider.counter == 1);
    }

    for (AuthDataBasic data : basictests) {
      logger.info("Test local credentials provider");
      logger.info("*** URL: " + data.url);

      provider = new TestProvider(data.user, data.password);

      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        session.setCredentialsProvider(provider);
        result = invoke(session, data.url);
        report(result, provider.counter);
      }
      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status)); // non-existence is ok
      Assert.assertTrue("no content", result.contents.length > 0);
      Assert.assertTrue("Credentials provider called: " + provider.counter, provider.counter == 1);
    }
  }

  @Test
  public void testInline() throws Exception {
    logger.info("*** Testing: Http Basic Password Authorization inline in URL");
    for (AuthDataBasic data : basictests) {
      Result result = null;
      logger.info("*** URL: " + data.inline());
      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        result = invoke(session, data.inline());
        report(result);
      }
      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status)); // non-existence is ok
      Assert.assertTrue("no content", result.contents.length > 0);
    }
  }

  @Test
  public void testBasicDirect() throws Exception {
    logger.info("*** Testing: Http Basic Password Authorization Using Constant Credentials");
    for (AuthDataBasic data : basictests) {
      Result result = null;
      Credentials creds = new UsernamePasswordCredentials(data.user, data.password);
      BasicCredentialsProvider bcp = new BasicCredentialsProvider();
      bcp.setCredentials(AuthScope.ANY, creds);
      logger.info("Test global credentials");
      logger.info("*** URL: " + data.url);


      // Test global credentials provider
      HTTPSession.setGlobalCredentialsProvider(bcp);
      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        result = invoke(session, data.url);
        report(result);
      }

      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status)); // non-existence is ok
      Assert.assertTrue("no content", result.contents.length > 0);
    }

    for (AuthDataBasic data : basictests) {
      Result result = null;
      Credentials creds = new UsernamePasswordCredentials(data.user, data.password);
      logger.info("Test local credentials");
      logger.info("*** URL: " + data.url);

      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        BasicCredentialsProvider bcp = new BasicCredentialsProvider();
        bcp.setCredentials(AuthScope.ANY, creds);
        session.setCredentialsProvider(bcp);
        result = invoke(session, data.url);
        report(result);
      }
      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status)); // non-existence is ok
      Assert.assertTrue("no content", result.contents.length > 0);
    }
  }

  @Test
  public void testCache() throws Exception {
    logger.info("*** Testing: Cache Invalidation");
    for (AuthDataBasic data : basictests) {
      Result result = null;
      TestProvider provider = null;
      logger.info("*** URL: " + data.url);

      // Do each test with a bad password to cause cache invalidation
      provider = new TestProvider(data.user, BADPASSWORD);

      HTTPSession session = HTTPFactory.newSession(data.url);
      session.setCredentialsProvider(provider);
      result = invoke(session, data.url);
      report(result, provider.counter);
      Assert.assertTrue("Incorrect return code: " + result.status,
          result.status == HttpStatus.SC_UNAUTHORIZED || result.status == HttpStatus.SC_FORBIDDEN);
      Assert.assertTrue("Credentials provider called: " + provider.counter, provider.counter == 1);

      // retry with correct password;
      // AuthCache should automatically clear bad one from cache.
      provider.setUserPwd(data.user, data.password);
      session.setCredentialsProvider(provider);
      result = invoke(session, data.url);
      report(result, provider.counter);
      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status));
      session.close();
    }
  }

  @Test
  public void testCache2() throws Exception {
    logger.info("*** Testing: Cache Invalidation visually");
    if (!this.prop_display) {
      logger.error("Test aborted: requires display");
      return;
    }
    for (AuthDataBasic data : basictests) {
      Result result = null;
      logger.info("*** URL: " + data.url);
      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        Login cp = new Login(data.user, BADPASSWORD);
        session.setCredentialsProvider(cp);
        result = invoke(session, data.url);
        report(result);
      }
      Assert.assertTrue("Incorrect return code: " + result.status,
          result.status == HttpStatus.SC_UNAUTHORIZED || result.status == HttpStatus.SC_FORBIDDEN);
      // retry with correct password;
      // AuthCache should automatically clear bad one from cache.
      try (HTTPSession session = HTTPFactory.newSession(data.url)) {
        Login cp = new Login(data.user, data.password);
        session.setCredentialsProvider(cp);
        result = invoke(session, data.url);
        report(result);
      }
      Assert.assertTrue("Incorrect return code: " + result.status, check(result.status));
    }
  }


  /*
   * // This test actually is does nothing because I have no way to test it
   * // since it requires a firewall proxy that requires username+pwd
   * 
   * @Test
   * public void
   * testFirewall() throws Exception
   * {
   * if(IGNORE) return; //ignore
   * String user = null;
   * String pwd = null;
   * String host = null;
   * int port = -1;
   * String url = null;
   * 
   * Counter counter = new Counter();
   * logger.info("*** Testing: Http Firewall Proxy (with authentication)");
   * provider.setPWD(user, pwd);
   * logger.info("*** URL: " + url);
   * // Test local credentials provider
   * try (HTTPSession session = HTTPFactory.newSession(url);
   * HTTPMethod method = HTTPFactory.Get(session, url)) {
   * session.setProxy(host, port);
   * session.setCredentialsProvider(url, provider);
   * int status = method.execute();
   * logger.info("local provider: status code = {}}", status);
   * Assert.assertTrue("Incorrect return code: " + result.status, check(result.status));
   * // Test global credentials provider
   * HTTPSession.setGlobalProxy(host, port);
   * HTTPSession.setGlobalTestProvider(url, provider);
   * try (HTTPSession session = HTTPFactory.newSession(url);
   * HTTPMethod method = HTTPFactory.Get(session, url)) {
   * int status = method.execute();
   * Assert.assertTrue("Incorrect return code: " + result.status, check(result.status));
   * }
   * }
   * }
   */

  //////////////////////////////////////////////////

  protected Result invoke(HTTPSession session, String url) throws IOException {
    Result result = new Result();
    try {
      try (HTTPMethod method = HTTPFactory.Get(session, url)) {
        result.status = method.execute();
        logger.info("global provider: status code = {}", result.status);
        // logger.info("|cache| = {}", HTTPCachingProvider.getCache().size());
        // try to read in the content
        result.contents = readbinaryfile(method.getResponseAsStream());
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new IOException(t);
    }
    return result;
  }

}

/*
 * javax.net.debug=cmd
 * 
 * where cmd can be:
 * 
 * all turn on all debugging
 * ssl turn on ssl debugging
 * 
 * or
 * 
 * javax.net.debug=ssl:<option>:<option>...
 * 
 * where <option> is taken from the following:
 * 
 * The following can be used with ssl:
 * record enable per-record tracing
 * handshake print each handshake message
 * keygen print key generation data
 * session print session activity
 * defaultctx print default SSL initialization
 * sslctx print SSLContext tracing
 * sessioncache print session cache tracing
 * keymanager print key manager tracing
 * trustmanager print trust manager tracing
 * pluggability print pluggability tracing
 * 
 * handshake debugging can be widened with:
 * data hex dump of each handshake message
 * verbose verbose handshake message printing
 * 
 * record debugging can be widened with:
 * plaintext hex dump of record plaintext
 * packet print raw SSL/TLS packets
 * 
 * 
 * Process finished with exit code 0
 * Empty test suite.
 */
