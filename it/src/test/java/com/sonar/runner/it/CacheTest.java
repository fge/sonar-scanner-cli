/*
 * SonarSource :: IT :: SonarQube Scanner
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.sonar.runner.it;

import com.sonar.orchestrator.build.BuildFailureException;
import com.sonar.orchestrator.build.BuildResult;
import com.sonar.orchestrator.build.SonarRunner;
import com.sonar.orchestrator.locator.ResourceLocation;
import java.io.File;
import java.io.IOException;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheTest extends ScannerTestCase {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private File currentTemp = null;
  private static boolean serverRunning = false;

  @BeforeClass
  public static void setUpClass() {
    orchestrator.resetData();
    orchestrator.getServer().restoreProfile(ResourceLocation.create("/sonar-way-profile.xml"));
    orchestrator.getServer().provisionProject("java:sample", "Java Sample, with comma");
    orchestrator.getServer().associateProjectToQualityProfile("java:sample", "java", "sonar-way");
    serverRunning = true;
  }

  @AfterClass
  public static void restartForOtherTests() {
    ensureStarted();
  }

  private static void ensureStarted() {
    if (!serverRunning) {
      orchestrator.start();
      serverRunning = true;
    }
  }

  private static void ensureStopped() {
    if (serverRunning) {
      orchestrator.stop();
      serverRunning = false;
    }
  }

  @Test
  public void testIssuesMode() throws IOException {
    Assume.assumeTrue(orchestrator.getServer().version().isGreaterThanOrEquals("5.3"));

    // online, cache empty -> should sync
    ensureStarted();
    SonarRunner build = createRunner("issues", true, "java-sample");
    BuildResult result = orchestrator.executeBuild(build, false);
    assertThat(result.isSuccess()).isTrue();

    // offline, don't use cache by default -> should fail
    ensureStopped();
    build = createRunner("issues", false, "java-sample");
    result = orchestrator.executeBuildQuietly(build, false);
    assertThat(result.isSuccess()).isFalse();

    // offline, don't use cache -> should run from cache
    build = createRunner("issues", false, "java-sample");
    build.setProperty("sonar.useWsCache", "true");
    result = orchestrator.executeBuild(build, false);
    assertThat(result.isSuccess()).isTrue();

    // offline, cache empty -> should fail
    build = createRunner("issues", true, "java-sample");
    build.setProperty("sonar.useWsCache", "true");
    result = orchestrator.executeBuildQuietly(build, false);
    assertThat(result.isSuccess()).isFalse();
    // this message is specific to the server_first cache strategy
    assertThat(result.getLogs()).contains("can not be reached, trying cache");
    assertThat(result.getLogs()).contains("can not be reached and data is not cached");
  }

  @Test
  public void testNonAssociatedMode() throws IOException {
    Assume.assumeTrue(orchestrator.getServer().version().isGreaterThanOrEquals("5.2"));

    // online, without cache -> should sync
    ensureStarted();
    SonarRunner build = createRunner("issues", true, "java-sample-non-associated");
    BuildResult result = orchestrator.executeBuild(build, false);
    assertThat(result.isSuccess()).isTrue();

    // offline, with cache -> should run from cache
    ensureStopped();
    build = createRunner("issues", false, "java-sample-non-associated");
    build.setProperty("sonar.useWsCache", "true");
    result = orchestrator.executeBuild(build, false);
    assertThat(result.isSuccess()).isTrue();
  }

  @Test
  public void testPublishModeOffline() throws IOException {
    Assume.assumeTrue(orchestrator.getServer().version().isGreaterThanOrEquals("5.2"));

    // online (cache not used)
    ensureStarted();
    SonarRunner build = createRunner("publish", "java-sample");
    BuildResult result = orchestrator.executeBuild(build, false);
    assertThat(result.isSuccess()).isTrue();

    // offline (cache not used) -> should fail
    ensureStopped();
    build = createRunner("publish", false, "java-sample");
    try {
      result = orchestrator.executeBuild(build);
    } catch (BuildFailureException e) {
      assertThat(e.getResult().getLogs()).contains("Fail to download libraries from server");
    }

  }

  private SonarRunner createRunner(String mode, String project) throws IOException {
    return createRunner(mode, false, project);
  }

  private SonarRunner createRunner(String mode, boolean refreshCache, String project) throws IOException {
    if (refreshCache || currentTemp == null) {
      currentTemp = temp.newFolder();
    }

    SonarRunner runner = newScanner(new File("projects/" + project))
      .setProperty("sonar.analysis.mode", mode)
      .setProperty("sonar.userHome", currentTemp.getAbsolutePath());

    return runner;
  }

}
