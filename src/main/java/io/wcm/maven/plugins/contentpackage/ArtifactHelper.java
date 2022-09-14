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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

class ArtifactHelper {

  private final RepositorySystem repoSystem;
  private final RepositorySystemSession repoSession;
  private final List<RemoteRepository> repositories;

  ArtifactHelper(RepositorySystem repoSystem, RepositorySystemSession repoSession, List<RemoteRepository> repositories) {
    this.repoSystem = repoSystem;
    this.repoSession = repoSession;
    this.repositories = repositories;
  }

  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public File getArtifactFile(final String artifactId, final String groupId, final String version,
      final String packaging, final String classifier, final String artifact) throws MojoFailureException, MojoExecutionException {
    // check if artifact was specified
    if ((StringUtils.isEmpty(artifactId) || StringUtils.isEmpty(groupId) || StringUtils.isEmpty(version))
        && StringUtils.isEmpty(artifact)) {
      return null;
    }

    // split up artifact string
    Artifact artifactObject;
    if (StringUtils.isEmpty(artifactId)) {
      artifactObject = getArtifactFromMavenCoordinates(artifact);
    }
    else {
      artifactObject = createArtifact(artifactId, groupId, version, packaging, classifier);
    }

    // resolve artifact
    ArtifactRequest request = new ArtifactRequest();
    request.setArtifact(artifactObject);
    request.setRepositories(repositories);
    try {
      ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
      return result.getArtifact().getFile();
    }
    catch (ArtifactResolutionException ex) {
      throw new MojoExecutionException("Unable to download artifact: " + artifactObject.toString(), ex);
    }
  }

  /**
   * Parse coordinates following definition from https://maven.apache.org/pom.html#Maven_Coordinates
   * @param artifact Artifact coordinates
   * @return Artifact object
   * @throws MojoFailureException if coordinates are semantically invalid
   */
  private Artifact getArtifactFromMavenCoordinates(final String artifact) throws MojoFailureException {

    String[] parts = StringUtils.split(artifact, ":");

    String version;
    String packaging = null;
    String classifier = null;

    switch (parts.length) {
      case 3:
        // groupId:artifactId:version
        version = parts[2];
        break;

      case 4:
        // groupId:artifactId:packaging:version
        packaging = parts[2];
        version = parts[3];
        break;

      case 5:
        // groupId:artifactId:packaging:classifier:version
        packaging = parts[2];
        classifier = parts[3];
        version = parts[4];
        break;

      default:
        throw new MojoFailureException("Invalid artifact: " + artifact);
    }

    String groupId = parts[0];
    String artifactId = parts[1];

    return createArtifact(artifactId, groupId, version, packaging, classifier);
  }

  private Artifact createArtifact(final String artifactId, final String groupId, final String version,
      final String type, String classifier) throws MojoFailureException {
    String artifactTypeString = StringUtils.defaultString(type, "jar");
    String artifactExtension = artifactTypeString;

    ArtifactType artifactType = repoSession.getArtifactTypeRegistry().get(artifactExtension);
    if (artifactType != null) {
      artifactExtension = artifactType.getExtension();
    }

    if (StringUtils.isBlank(groupId) || StringUtils.isBlank(artifactId) || StringUtils.isBlank(version)) {
      throw new MojoFailureException("Invalid Maven artifact reference: "
          + "artifactId=" + artifactId + ", "
          + "groupId=" + groupId + ", "
          + "version=" + version + ", "
          + "extension=" + artifactExtension + ", "
          + "classifier=" + classifier + ","
          + "type=" + artifactType);
    }

    return new DefaultArtifact(groupId, artifactId, classifier, artifactExtension, version, artifactType);
  }

}
