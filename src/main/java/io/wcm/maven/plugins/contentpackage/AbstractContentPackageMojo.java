/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.wcm.maven.plugins.contentpackage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Common functionality for all mojos.
 */
abstract class AbstractContentPackageMojo extends AbstractMojo {

  /**
   * Prefix or error message from CRX HTTP interfaces when uploading a package that already exists.
   */
  protected static final String CRX_PACKAGE_EXISTS_ERROR_MESSAGE_PREFIX = "Package already exists: ";

  /**
   * The name of the content package file to install on the target system.
   * If not set, the primary artifact of the project is considered the content package to be installed.
   */
  @Parameter(property = "vault.file", defaultValue = "${project.build.directory}/${project.build.finalName}.zip")
  private File packageFile;

  /**
   * The URL of the HTTP service API of the CRX package manager.
   * See <a href=
   * "http://dev.day.com/docs/en/crx/current/how_to/package_manager.html#Managing%20Packages%20on%20the%20Command%20Line"
   * >CRX HTTP service Interface</a> for details on this interface.
   */
  @Parameter(property = "vault.serviceURL", required = true, defaultValue = "http://localhost:4502/crx/packmgr/service")
  private String serviceURL;

  /**
   * The user name to authenticate as against the remote CRX system.
   */
  @Parameter(property = "vault.userId", required = true, defaultValue = "admin")
  private String userId;

  /**
   * The password to authenticate against the remote CRX system.
   */
  @Parameter(property = "vault.password", required = true, defaultValue = "admin")
  private String password;

  /**
   * Set this to "true" to skip installing packages to CRX although configured in the POM.
   */
  @Parameter(property = "vault.skip", defaultValue = "false")
  private boolean skip;

  /**
   * Number of times to retry upload and install via CRX HTTP interface if it fails.
   */
  @Parameter(property = "vault.retryCount", defaultValue = "0")
  private int retryCount;

  /**
   * Number of seconds between retry attempts.
   */
  @Parameter(property = "vault.retryDelay", defaultValue = "0")
  private int retryDelay;

  /**
   * Bundle status JSON URL. If an URL is configured the activation status of all bundles in the system is checked
   * before it is tried to upload and install a new package and after each upload.
   * If not all packages are installed the upload is delayed up to 10 minutes, every 5 seconds the
   * activation status is checked anew.
   * Expected is an URL like: http://localhost:4502/system/console/bundles/.json
   */
  @Parameter(property = "vault.bundleStatusURL", required = false)
  private String bundleStatusURL;

  @Parameter(property = "project", required = true, readonly = true)
  private MavenProject project;

  protected final MavenProject getProject() {
    return this.project;
  }

  protected final File getPackageFile() {
    return this.packageFile;
  }

  protected final String getCrxPackageManagerUrl() {
    String serviceUrl = this.serviceURL;
    // convert "legacy interface URL" with service.jsp to new CRX interface (since CRX 2.1)
    serviceUrl = StringUtils.replace(serviceUrl, "/crx/packmgr/service.jsp", "/crx/packmgr/service");
    // remove /.json suffix if present
    serviceUrl = StringUtils.removeEnd(serviceUrl, "/.json");
    return serviceUrl;
  }

  /**
   * Set up http client with credentials
   * @return Http client
   * @throws MojoExecutionException
   */
  protected final CloseableHttpClient getHttpClient() throws MojoExecutionException {
    try {
      URI crxUri = new URI(getCrxPackageManagerUrl());

      CredentialsProvider credsProvider = new BasicCredentialsProvider();
      credsProvider.setCredentials(
          new AuthScope(crxUri.getHost(), crxUri.getPort()),
          new UsernamePasswordCredentials(this.userId, this.password));

      CloseableHttpClient httpClient = HttpClients.custom()
          .setDefaultCredentialsProvider(credsProvider)
          .build();

      return httpClient;
    }
    catch (URISyntaxException ex) {
      throw new MojoExecutionException("Invalid url: " + getCrxPackageManagerUrl(), ex);
    }
  }

  /**
   * Execute CRX HTTP Package manager method and parse/output xml response.
   * @param httpClient Http client
   * @param method Get or Post method
   * @throws MojoExecutionException
   */
  protected final JSONObject executePackageManagerMethodJson(CloseableHttpClient httpClient, HttpRequestBase method,
      int runCount) throws MojoExecutionException {

    try {

      CloseableHttpResponse response = null;
      String responseString = null;
      try {
        JSONObject jsonResponse = null;

        if (getLog().isDebugEnabled()) {
          getLog().debug("Call URL: " + method.getURI());
        }

        // execute method
        response = httpClient.execute(method);
        responseString = EntityUtils.toString(response.getEntity());
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {

          // get response JSON
          if (responseString != null) {
            jsonResponse = new JSONObject(responseString);
          }
          if (jsonResponse == null) {
            jsonResponse = new JSONObject();
            jsonResponse.put("success", false);
            jsonResponse.put("msg", "Invalid response (null).");
          }

        }
        else {
          jsonResponse = new JSONObject();
          jsonResponse.put("success", false);
          jsonResponse.put("msg", responseString);
        }

        return jsonResponse;
      }
      catch (IOException ex) {
        throw new MojoExecutionException("Http method failed.", ex);
      }
      catch (JSONException ex) {
        throw new MojoExecutionException("JSON operation failed:\n" + responseString, ex);
      }
      finally {
        if (response != null) {
          EntityUtils.consumeQuietly(response.getEntity());
          try {
            response.close();
          }
          catch (IOException ex) {
            // ignore
          }
        }
      }

    }
    catch (MojoExecutionException ex) {
      // retry again if configured so...
      if (runCount < this.retryCount) {
        getLog().info("ERROR: " + ex.getMessage());
        getLog().debug("Package manager method execution failed.", ex);
        getLog().info("---------------");

        String msg = "Package manager method failed, try again (" + (runCount + 1) + "/" + this.retryCount + ")";
        if (this.retryDelay > 0) {
          msg += " after " + this.retryDelay + " second(s)";
        }
        msg += "...";
        getLog().info(msg);
        if (this.retryDelay > 0) {
          try {
            Thread.sleep(this.retryDelay * DateUtils.MILLIS_PER_SECOND);
          }
          catch (InterruptedException ex1) {
            // ignore
          }
        }
        return executePackageManagerMethodJson(httpClient, method, runCount + 1);
      }
      else {
        throw ex;
      }
    }
  }

  /**
   * Execute CRX HTTP Package manager method and parse/output xml response.
   * @param httpClient Http client
   * @param method Get or Post method
   * @throws MojoExecutionException
   */
  protected final void executePackageManagerMethodHtml(CloseableHttpClient httpClient, HttpRequestBase method,
      int runCount) throws MojoExecutionException {

    try {

      CloseableHttpResponse response = null;
      String responseString = null;
      try {

        if (getLog().isDebugEnabled()) {
          getLog().debug("Call URL: " + method.getURI());
        }

        // execute method
        response = httpClient.execute(method);
        responseString = EntityUtils.toString(response.getEntity());
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {

          // debug output whole xml
          if (getLog().isDebugEnabled()) {
            getLog().debug("CRX Package Manager Response:\n" + responseString);
          }

          // remove all HTML tags and special conctent
          final Pattern HTML_STYLE = Pattern.compile("<style[^<>]*>[^<>]*</style>", Pattern.MULTILINE | Pattern.DOTALL);
          final Pattern HTML_JAVASCRIPT = Pattern.compile("<script[^<>]*>[^<>]*</script>", Pattern.MULTILINE | Pattern.DOTALL);
          final Pattern HTML_ANYTAG = Pattern.compile("<[^<>]*>");

          responseString = HTML_STYLE.matcher(responseString).replaceAll("");
          responseString = HTML_JAVASCRIPT.matcher(responseString).replaceAll("");
          responseString = HTML_ANYTAG.matcher(responseString).replaceAll("");
          responseString = StringUtils.replace(responseString, "&nbsp;", " ");

          getLog().info(responseString);
        }
        else {
          throw new MojoExecutionException("Failure:\n" + responseString);
        }

      }
      catch (IOException ex) {
        throw new MojoExecutionException("Http method failed.", ex);
      }
      finally {
        if (response != null) {
          EntityUtils.consumeQuietly(response.getEntity());
          try {
            response.close();
          }
          catch (IOException ex) {
            // ignore
          }
        }
      }

    }
    catch (MojoExecutionException ex) {
      // retry again if configured so...
      if (runCount < this.retryCount) {
        getLog().info("ERROR: " + ex.getMessage());
        getLog().debug("Package manager method execution failed.", ex);
        getLog().info("---------------");

        String msg = "Package manager method failed, try again (" + (runCount + 1) + "/" + this.retryCount + ")";
        if (this.retryDelay > 0) {
          msg += " after " + this.retryDelay + " second(s)";
        }
        msg += "...";
        getLog().info(msg);
        if (this.retryDelay > 0) {
          try {
            Thread.sleep(this.retryDelay * DateUtils.MILLIS_PER_SECOND);
          }
          catch (InterruptedException ex1) {
            // ignore
          }
        }
        executePackageManagerMethodHtml(httpClient, method, runCount + 1);
      }
      else {
        throw ex;
      }
    }
  }

  protected final boolean isSkip() {
    return this.skip;
  }

  /**
   * Wait up to 10 min for bundles to become active.
   * @throws MojoExecutionException
   */
  protected void waitForBundlesActivation() throws MojoExecutionException {
    if (StringUtils.isBlank(bundleStatusURL)) {
      getLog().debug("Skipping check for bundle activation state because no bundleStatusURL is defined.");
      return;
    }

    final int WAIT_MAX_SEC = 10 * 60;
    final int WAIT_INTERVAL_SEC = 2;
    final long CHECK_RETRY_COUNT = WAIT_MAX_SEC / WAIT_INTERVAL_SEC;

    getLog().info("Check bundle activation states...");
    for (int i = 1; i <= CHECK_RETRY_COUNT; i++) {
      if (isBundlesActive()) {
        return;
      }
      getLog().info("Bundles are currently starting/stopping - wait " + WAIT_INTERVAL_SEC + " seconds...");
      try {
        Thread.sleep(WAIT_INTERVAL_SEC * DateUtils.MILLIS_PER_SECOND);
      }
      catch (InterruptedException e) {
        // ignore
      }
    }
  }

  private boolean isBundlesActive() throws MojoExecutionException {
    CloseableHttpClient httpClient = null;
    CloseableHttpResponse response = null;
    try {
      httpClient = getHttpClient();
      HttpGet method = new HttpGet(bundleStatusURL);

      response = httpClient.execute(method);
      String responseString = EntityUtils.toString(response.getEntity());
      if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK || StringUtils.isEmpty(responseString)) {
        return false;
      }

      JSONObject jsonResponse = new JSONObject(responseString);
      BundleStatus status = BundleStatus.fromStatusResponse(jsonResponse);

      getLog().info(status.getStatusLine());

      return status.getInstalled() + status.getResolved() == 0;
    }
    catch (Throwable ex) {
      throw new MojoExecutionException("Can't determine bundles state via URL: " + bundleStatusURL, ex);
    }
    finally {
      if (response != null) {
        EntityUtils.consumeQuietly(response.getEntity());
        try {
          response.close();
        }
        catch (IOException ex) {
          // ignore
        }
      }
      if (httpClient != null) {
        try {
          httpClient.close();
        }
        catch (IOException ex) {
          // ignore
        }
      }
    }
  }

}
