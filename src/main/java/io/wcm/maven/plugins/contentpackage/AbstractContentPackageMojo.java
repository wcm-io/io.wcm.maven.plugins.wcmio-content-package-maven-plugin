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

import static io.wcm.tooling.commons.packmgr.install.VendorInstallerFactory.COMPOSUM_URL;
import static io.wcm.tooling.commons.packmgr.install.VendorInstallerFactory.CRX_URL;

import java.io.File;
import java.util.Arrays;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.crypto.SettingsDecrypter;

import io.wcm.tooling.commons.packmgr.PackageManagerProperties;
import io.wcm.tooling.commons.packmgr.install.VendorInstallerFactory;
import io.wcm.tooling.commons.packmgr.install.VendorInstallerFactory.Service;

/**
 * Common functionality for all mojos.
 */
abstract class AbstractContentPackageMojo extends AbstractMojo {

  /**
   * The name of the content package file to install on the target system.
   * If not set, the primary artifact of the project is considered the content package to be installed.
   */
  @Parameter(property = "vault.file", defaultValue = "${project.build.directory}/${project.build.finalName}.zip")
  private File packageFile;

  /**
   * The URL of the HTTP service API of the CRX package manager.
   *
   * <p>
   * See <a href=
   * "https://experienceleague.adobe.com/docs/experience-manager-65/administering/operations/curl.html?lang=en#package-management"
   * >Package Manager HTTP API</a> for details on this interface.
   * </p>
   */
  @Parameter(property = "vault.serviceURL", required = true, defaultValue = "http://localhost:4502/crx/packmgr/service")
  private String serviceURL;

  /**
   * The user name to authenticate as against the remote CRX system (package manager).
   */
  @Parameter(property = "vault.userId", required = true, defaultValue = "admin")
  private String userId;

  /**
   * The password to authenticate against the remote CRX system (package manager).
   */
  @Parameter(property = "vault.password", required = true, defaultValue = "admin")
  private String password;

  /**
   * OAuth 2 access token to authenticate against the remote CRX system (package manager).
   * If this is configured, username and password are ignored.
   */
  @Parameter(property = "vault.oauth2AccessToken")
  private String oauth2AccessToken;

  /**
   * The user name to authenticate as against the remote CRX system (Felix console).
   * Defaults to the value from <code>userId</code>.
   */
  @Parameter(property = "console.userId")
  private String consoleUserId;

  /**
   * The password to authenticate against the remote CRX system (Felix console).
   * Defaults to the value from <code>password</code>.
   */
  @Parameter(property = "console.password")
  private String consolePassword;

  /**
   * OAuth 2 access token to authenticate against the remote CRX system (Felix console).
   * If this is configured, username and password are ignored.
   * Defaults to value from <code>oauth2AccessToken</code>.
   */
  @Parameter(property = "console.consoleOauth2AccessToken")
  private String consoleOauth2AccessToken;

  /**
   * Set this to "true" to skip installing packages to CRX although configured in the POM.
   */
  @Parameter(property = "vault.skip", defaultValue = "false")
  private boolean skip;

  /**
   * Number of times to retry upload and install via CRX HTTP interface if it fails.
   */
  @Parameter(property = "vault.retryCount", defaultValue = "24")
  private int retryCount;

  /**
   * Number of seconds between retry attempts.
   */
  @Parameter(property = "vault.retryDelay", defaultValue = "5")
  private int retryDelay;

  /**
   * Bundle status JSON URL. If an URL is configured the activation status of all bundles in the system is checked
   * before it is tried to upload and install a new package and after each upload.
   *
   * <p>
   * If not all bundles are activated the upload is delayed up to {@link #bundleStatusWaitLimit} seconds,
   * every 5 seconds the activation status is checked anew.
   * </p>
   *
   * <p>
   * Expected is an URL like: http://localhost:4502/system/console/bundles/.json
   * </p>
   *
   * <p>
   * If the URL is not set it is derived from serviceURL. If set to "-" the status check is disabled.
   * </p>
   */
  @Parameter(property = "vault.bundleStatusURL", required = false)
  private String bundleStatusURL;

  /**
   * Number of seconds to wait as maximum for a positive bundle status check.
   * If this limit is reached and the bundle status is still not positive the install of the package proceeds anyway.
   */
  @Parameter(property = "vault.bundleStatusWaitLimit", defaultValue = "360")
  private int bundleStatusWaitLimit;

  /**
   * Package Manager install status JSON URL. If an URL is configured the installation status of packages and
   * embedded packages is checked before it is tried to upload and install a new package and after each upload.
   *
   * <p>
   * If not all packages are installed the upload is delayed up to {@link #packageManagerInstallStatusWaitLimit}
   * seconds, every 5 seconds the installation status is checked anew.
   * </p>
   *
   * <p>
   * Expected is an URL like: http://localhost:4502/crx/packmgr/installstatus.jsp
   * </p>
   *
   * <p>
   * If the URL is not set it is derived from serviceURL. If set to "-" the status check is disabled.
   * </p>
   */
  @Parameter(property = "vault.packageManagerInstallStatusURL", required = false)
  private String packageManagerInstallStatusURL;

  /**
   * Number of seconds to wait as maximum for a positive package manager install status check.
   * If this limit is reached and the package manager status is still not positive the install of the package proceeds
   * anyway.
   */
  @Parameter(property = "vault.packageManagerInstallStatusWaitLimit", defaultValue = "360")
  private int packageManagerInstallStatusWaitLimit;

  /**
   * Patterns for symbolic names of bundles that are expected to be not present in bundle list.
   * If any of these bundles are found in the bundle list, this system is assumed as not ready for installing further
   * packages because a previous installation (e.g. of AEM service pack) is still in progress.
   */
  @Parameter(property = "vault.bundleStatusBlacklistBundleNames", defaultValue = "^updater\\.aem.*$")
  private String[] bundleStatusBlacklistBundleNames;

  /**
   * Patterns for symbolic names of bundles that are ignored in bundle list.
   * The state of these bundles has no effect on the bundle status check.
   */
  @Parameter(property = "vault.bundleStatusWhitelistBundleNames",
      defaultValue = "^com\\.day\\.crx\\.crxde-support$,"
          + "^com\\.adobe\\.granite\\.crx-explorer$,"
          + "^com\\.adobe\\.granite\\.crxde-lite$,"
          + "^org\\.apache\\.sling\\.jcr\\.webdav$,"
          + "^org\\.apache\\.sling\\.jcr\\.davex$")
  private String[] bundleStatusWhitelistBundleNames;

  /**
   * If set to true also self-signed certificates are accepted.
   */
  @Parameter(property = "vault.relaxedSSLCheck", defaultValue = "false")
  private boolean relaxedSSLCheck;

  /**
   * HTTP connection timeout (in seconds).
   */
  @Parameter(property = "vault.httpConnectTimeoutSec", defaultValue = "10")
  private int httpConnectTimeoutSec;

  /**
   * HTTP socket timeout (in seconds).
   */
  @Parameter(property = "vault.httpSocketTimeoutSec", defaultValue = "60")
  private int httpSocketTimeout;

  /**
   * Log level to be used to log responses from package manager (which may get huge for large packages).
   * Possible values are INFO (default) or DEBUG.
   */
  @Parameter(property = "vault.packageManagerOutputLogLevel", defaultValue = "INFO")
  private String packageManagerOutputLogLevel;

  @Parameter(property = "session", defaultValue = "${session}", readonly = true)
  private MavenSession session;

  @Inject
  private SettingsDecrypter decrypter;

  /**
   * @return Package file
   */
  protected final File getPackageFile() {
    return this.packageFile;
  }

  /**
   * @return Skip
   */
  protected final boolean isSkip() {
    return this.skip;
  }

  /**
   * @return Package manager properties
   * @throws MojoExecutionException If configuration is invalid
   */
  protected PackageManagerProperties getPackageManagerProperties() throws MojoExecutionException {
    PackageManagerProperties props = new PackageManagerProperties();

    props.setPackageManagerUrl(buildPackageManagerUrl());
    props.setUserId(this.userId);
    props.setPassword(this.password);
    props.setOAuth2AccessToken(this.oauth2AccessToken);
    props.setConsoleUserId(this.consoleUserId);
    props.setConsolePassword(this.consolePassword);
    props.setConsoleOAuth2AccessToken(this.consoleOauth2AccessToken);
    props.setRetryCount(this.retryCount);
    props.setRetryDelaySec(this.retryDelay);
    props.setBundleStatusUrl(buildBundleStatusUrl());
    props.setBundleStatusWaitLimitSec(this.bundleStatusWaitLimit);
    props.setBundleStatusBlacklistBundleNames(Arrays.asList(this.bundleStatusBlacklistBundleNames));
    props.setBundleStatusWhitelistBundleNames(Arrays.asList(this.bundleStatusWhitelistBundleNames));
    props.setPackageManagerInstallStatusURL(buildPackageManagerInstallStatusUrl());
    props.setPackageManagerInstallStatusWaitLimitSec(this.packageManagerInstallStatusWaitLimit);
    props.setRelaxedSSLCheck(this.relaxedSSLCheck);
    props.setHttpConnectTimeoutSec(this.httpConnectTimeoutSec);
    props.setHttpSocketTimeoutSec(this.httpSocketTimeout);
    props.setProxies(ProxySupport.getMavenProxies(session, decrypter));
    props.setPackageManagerOutputLogLevel(this.packageManagerOutputLogLevel);

    return props;
  }

  private String buildPackageManagerUrl() throws MojoExecutionException {
    String serviceUrl = this.serviceURL;
    switch (VendorInstallerFactory.identify(serviceUrl)) {
      case CRX:
        serviceUrl = VendorInstallerFactory.getBaseUrl(serviceUrl) + CRX_URL;
        break;
      case COMPOSUM:
        serviceUrl = VendorInstallerFactory.getBaseUrl(serviceUrl) + COMPOSUM_URL;
        break;
      default:
        throw new MojoExecutionException("Unsupported service URL: " + serviceUrl);
    }
    return serviceUrl;
  }

  private String buildBundleStatusUrl() throws MojoExecutionException {
    if (StringUtils.equals(this.bundleStatusURL, "-")) {
      return null;
    }
    if (this.bundleStatusURL != null) {
      return this.bundleStatusURL;
    }
    // if not set use hostname from serviceURL and add default path to bundle status
    String baseUrl = VendorInstallerFactory.getBaseUrl(buildPackageManagerUrl());
    return baseUrl + "/system/console/bundles/.json";
  }

  private String buildPackageManagerInstallStatusUrl() throws MojoExecutionException {
    if (StringUtils.equals(this.packageManagerInstallStatusURL, "-")
        || VendorInstallerFactory.identify(this.serviceURL) != Service.CRX) {
      return null;
    }
    if (this.packageManagerInstallStatusURL != null) {
      return this.packageManagerInstallStatusURL;
    }
    // if not set use hostname from serviceURL and add default path to bundle status
    String baseUrl = VendorInstallerFactory.getBaseUrl(buildPackageManagerUrl());
    return baseUrl + "/crx/packmgr/installstatus.jsp";
  }

}
