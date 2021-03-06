/*
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package hudson.plugins.sonar;

import hudson.Functions;
import hudson.maven.MavenModuleSet;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.plugins.sonar.model.TriggersConfig;
import hudson.scm.NullSCM;
import hudson.tasks.Maven;
import hudson.util.jna.GNUCLibrary;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;

import java.io.File;
import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;

/**
 * @author Evgeny Mandrikov
 */
public abstract class SonarTestCase {

  @Rule
  public JenkinsRule j = new JenkinsRule();

  /**
   * Setting this to non-existent host, allows us to avoid intersection with exist Sonar.
   */
  public static final String SONAR_HOST = "http://example.org:9999/sonar";
  public static final String DATABASE_PASSWORD = "password";

  public static final String ROOT_POM = "sonar-pom.xml";
  public static final String SONAR_INSTALLATION_NAME = "default";

  /**
   * Returns Fake Maven Installation.
   *
   * @return Fake Maven Installation
   * @throws Exception if something is wrong
   */
  protected Maven.MavenInstallation configureDefaultMaven() throws Exception {
    File mvn = new File(getClass().getResource("SonarTestCase/maven/bin/mvn").toURI().getPath());
    if (!Functions.isWindows()) {
      // noinspection OctalInteger
      GNUCLibrary.LIBC.chmod(mvn.getPath(), 0755);
    }
    String home = mvn.getParentFile().getParentFile().getAbsolutePath();
    Maven.MavenInstallation mavenInstallation = new Maven.MavenInstallation("default", home, JenkinsRule.NO_PROPERTIES);
    j.jenkins.getDescriptorByType(Maven.DescriptorImpl.class).setInstallations(mavenInstallation);
    return mavenInstallation;
  }

  protected SonarInstallation configureDefaultSonar() {
    return configureSonar(new SonarInstallation(SONAR_INSTALLATION_NAME));
  }

  protected SonarInstallation configureSonar(SonarInstallation sonarInstallation) {
    j.jenkins.getDescriptorByType(SonarPublisher.DescriptorImpl.class).setInstallations(sonarInstallation);
    return sonarInstallation;
  }

  protected MavenModuleSet setupMavenProject() throws Exception {
    return setupMavenProject("pom.xml");
  }

  protected String getPom(AbstractBuild<?, ?> build, String pomName) {
    return build.getWorkspace().child(pomName).getRemote();
  }

  protected MavenModuleSet setupMavenProject(String pomName) throws Exception {
    MavenModuleSet project = j.createMavenProject("MavenProject");
    // Setup SCM
    project.setScm(new SingleFileSCM(pomName, getClass().getResource("/hudson/plugins/sonar/SonarTestCase/pom.xml")));
    // Setup Maven
    project.setRootPOM(pomName);
    project.setGoals("clean install");
    project.setIsArchivingDisabled(true);
    // Setup Sonar
    project.getPublishersList().add(newSonarPublisherForMavenProject());
    return project;
  }

  protected FreeStyleProject setupFreeStyleProject() throws Exception {
    return setupFreeStyleProject(ROOT_POM);
  }

  protected FreeStyleProject setupFreeStyleProject(String pomName) throws Exception {
    FreeStyleProject project = j.createFreeStyleProject("FreeStyleProject");
    // Setup SCM
    project.setScm(new NullSCM());
    // Setup Sonar
    project.getPublishersList().add(newSonarPublisherForFreeStyleProject(pomName));
    return project;
  }

  protected AbstractBuild<?, ?> build(AbstractProject<?, ?> project) throws Exception {
    return build(project, null);
  }

  protected AbstractBuild<?, ?> build(AbstractProject<?, ?> project, Result expectedStatus) throws Exception {
    return build(project, new TriggersConfig.SonarCause(), expectedStatus);
  }

  protected AbstractBuild<?, ?> build(AbstractProject<?, ?> project, Cause cause, Result expectedStatus) throws Exception {
    AbstractBuild<?, ?> build = project.scheduleBuild2(0, cause).get();
    if (expectedStatus != null) {
      j.assertBuildStatus(expectedStatus, build);
    }
    return build;
  }

  protected static SonarPublisher newSonarPublisherForMavenProject() {
    return new SonarPublisher(SONAR_INSTALLATION_NAME, null, null);
  }

  protected static SonarPublisher newSonarPublisherForFreeStyleProject(String pomName) {
    return new SonarPublisher(
      SONAR_INSTALLATION_NAME,
      new TriggersConfig(),
      null,
      null,
      "default", // Maven Installation Name
      pomName // Root POM
    );
  }

  /**
   * Asserts that Sonar executed with given arguments.
   *
   * @param build build
   * @param args command line arguments
   * @throws Exception if something is wrong
   */
  protected void assertSonarExecution(AbstractBuild<?, ?> build, String args) throws Exception {
    // Check command line arguments
    assertLogContains(args + " -e -B", build);
    // Check that plugin was invoked
    assertLogContains("sonar:sonar", build);

    // Check that Sonar Plugin started
    // assertLogContains("[INFO] Sonar host: " + SONAR_HOST, build);

    // SONARPLUGINS-320: Check that small badge was added to build history
    assertThat(build.getAction(BuildSonarAction.class)).as(BuildSonarAction.class.getSimpleName() + " not found").isNotNull();

    // SONARPLUGINS-165: Check that link added to project
    // FIXME Godin: I don't know why, but this don't work for FreeStyleProject
    // AbstractProject project = build.getProject();
    // assertNotNull(project.getAction(ProjectSonarAction.class));
  }

  protected void assertSonarExecution(AbstractBuild<?, ?> build) throws Exception {
    assertSonarExecution(build, "");
  }

  protected void assertNoSonarExecution(AbstractBuild<?, ?> build, String cause) throws Exception {
    assertLogContains(cause, build);
    // SONARPLUGINS-320: Check that small badge was not added to build history
    assertThat(build.getAction(BuildSonarAction.class)).as(BuildSonarAction.class.getSimpleName() + " found").isNull();
  }

  protected void assertLogContains(String substring, Run<?, ?> run) throws IOException {
    String log = JenkinsRule.getLog(run);
    assertThat(log).contains(substring);
  }

  /**
   * Asserts that the console output of the build doesn't contains the given substring.
   *
   * @param substring substring to check
   * @param run run to check
   * @throws Exception if something wrong
   */
  protected void assertLogDoesntContains(String substring, Run<?, ?> run) throws Exception {
    String log = JenkinsRule.getLog(run);
    assertThat(log).doesNotContain(substring);
  }
}
