/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.ws.rs.core.Response;
import marquez.common.Utils;
import marquez.common.models.RunState;
import marquez.db.JobFacetsDao;
import marquez.db.JobVersionDao;
import marquez.db.RunFacetsDao;
import marquez.service.JobService;
import marquez.service.NamespaceService;
import marquez.service.RunService;
import marquez.service.ServiceFactory;
import marquez.service.models.JobFacets;
import marquez.service.models.RunFacets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

@ExtendWith(DropwizardExtensionsSupport.class)
class JobResourceTest {
  private static ResourceExtension UNDER_TEST;
  private static JobFacets JOB_FACETS;
  private static RunFacets RUN_FACETS;
  private static UUID RUN_UUID = UUID.fromString("a32f2800-7782-3ce3-b77e-eeeeaded3cf3");

  private static final String NAMESPACE = "test-namespace";
  private static final String JOB = "test-job";
  private static final String RUNS_PATH =
      "/api/v1/namespaces/" + NAMESPACE + "/jobs/" + JOB + "/runs";

  private static RunService runService;
  private static JobService jobService;
  private static NamespaceService namespaceService;

  static {
    runService = mock(RunService.class);
    jobService = mock(JobService.class);
    namespaceService = mock(NamespaceService.class);
    JobVersionDao jobVersionDao = mock(JobVersionDao.class);
    JobFacetsDao jobFacetsDao = mock(JobFacetsDao.class);
    RunFacetsDao runFacetsDao = mock(RunFacetsDao.class);

    ImmutableMap<String, Object> jobFacets =
        Utils.fromJson(
            JobResourceTest.class.getResourceAsStream("/facets/job_facets.json"),
            new TypeReference<>() {});
    ImmutableMap<String, Object> runFacets =
        Utils.fromJson(
            JobResourceTest.class.getResourceAsStream("/facets/run_facets.json"),
            new TypeReference<>() {});
    JOB_FACETS = new JobFacets(RUN_UUID, jobFacets);
    RUN_FACETS = new RunFacets(RUN_UUID, runFacets);
    when(runService.exists(RUN_UUID)).thenReturn(true);
    when(jobFacetsDao.findJobFacetsByRunUuid(any(UUID.class))).thenReturn(JOB_FACETS);
    when(runFacetsDao.findRunFacetsByRunUuid(any(UUID.class))).thenReturn(RUN_FACETS);

    when(namespaceService.exists(anyString())).thenReturn(true);
    when(jobService.exists(anyString(), anyString())).thenReturn(true);
    // default to empty run lists so unstubbed calls do not return null
    when(runService.findAll(anyString(), anyString(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(runService.findAll(
            anyString(), anyString(), anyList(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());

    ServiceFactory serviceFactory =
        ApiTestUtils.mockServiceFactory(
            Map.of(
                RunService.class, runService,
                JobService.class, jobService,
                NamespaceService.class, namespaceService));

    UNDER_TEST =
        ResourceExtension.builder()
            .addResource(new JobResource(serviceFactory, jobVersionDao, jobFacetsDao, runFacetsDao))
            .build();
  }

  @BeforeEach
  public void resetInvocations() {
    Mockito.clearInvocations(runService, jobService);
  }

  @Test
  public void testGetJobFacetsByRunUUid() {
    assertThat(
            UNDER_TEST
                .target("/api/v1/jobs/321/runs")
                .queryParam("type", "job")
                .request()
                .get()
                .getStatus())
        .isEqualTo(404);
    final JobFacets testJobFacets =
        UNDER_TEST
            .target("/api/v1/jobs/runs/a32f2800-7782-3ce3-b77e-eeeeaded3cf3/facets")
            .queryParam("type", "job")
            .request()
            .get()
            .readEntity(JobFacets.class);

    assertEquals(testJobFacets, JOB_FACETS);
  }

  @Test
  public void testGetRunFacetsByRunUUid() {
    assertThat(
            UNDER_TEST
                .target("/api/v1/jobs/321/runs")
                .queryParam("type", "run")
                .request()
                .get()
                .getStatus())
        .isEqualTo(404);
    final RunFacets testRunFacets =
        UNDER_TEST
            .target("/api/v1/jobs/runs/a32f2800-7782-3ce3-b77e-eeeeaded3cf3/facets")
            .queryParam("type", "run")
            .request()
            .get()
            .readEntity(RunFacets.class);

    assertEquals(testRunFacets, RUN_FACETS);
  }

  @Test
  public void testListRuns_withoutFilters_keepsUnfilteredBehavior() {
    when(jobService.countJobRuns(NAMESPACE, JOB)).thenReturn(7);

    final Map<String, Object> response = UNDER_TEST.target(RUNS_PATH).request().get(Map.class);

    assertThat(response.get("totalCount")).isEqualTo(7);
    assertThat(response.get("runs")).isEqualTo(List.of());
    verify(runService).findAll(NAMESPACE, JOB, 100, 0);
    verify(jobService).countJobRuns(NAMESPACE, JOB);
    // the filtered queries must not be used when no filter is provided
    verify(runService, never())
        .findAll(anyString(), anyString(), anyList(), any(), any(), anyInt(), anyInt());
    verify(runService, never()).countRuns(anyString(), anyString(), anyList(), any(), any());
  }

  @Test
  public void testListRuns_withRunStatesFilter() {
    when(runService.countRuns(
            NAMESPACE, JOB, List.of(RunState.FAILED, RunState.ABORTED), null, null))
        .thenReturn(2);

    final Map<String, Object> response =
        UNDER_TEST
            .target(RUNS_PATH)
            .queryParam("runStates", "FAILED", "aborted")
            .request()
            .get(Map.class);

    assertThat(response.get("totalCount")).isEqualTo(2);
    verify(runService)
        .findAll(NAMESPACE, JOB, List.of(RunState.FAILED, RunState.ABORTED), null, null, 100, 0);
    verify(runService)
        .countRuns(NAMESPACE, JOB, List.of(RunState.FAILED, RunState.ABORTED), null, null);
    verify(jobService, never()).countJobRuns(anyString(), anyString());
  }

  @Test
  public void testListRuns_withCommaSeparatedRunStates() {
    UNDER_TEST.target(RUNS_PATH).queryParam("runStates", "FAILED,COMPLETED").request().get();

    verify(runService)
        .findAll(NAMESPACE, JOB, List.of(RunState.FAILED, RunState.COMPLETED), null, null, 100, 0);
  }

  @Test
  public void testListRuns_withTimeWindowFilter() {
    final Instant after = Instant.parse("2024-01-01T00:00:00Z");
    final Instant before = Instant.parse("2024-02-01T00:00:00Z");

    UNDER_TEST
        .target(RUNS_PATH)
        .queryParam("startedAfter", "2024-01-01T00:00:00Z")
        .queryParam("startedBefore", "2024-02-01T00:00:00Z")
        .request()
        .get();

    verify(runService).findAll(NAMESPACE, JOB, List.of(), after, before, 100, 0);
    verify(runService).countRuns(NAMESPACE, JOB, List.of(), after, before);
  }

  @Test
  public void testListRuns_withInvalidRunState_returns400() {
    final Response response =
        UNDER_TEST.target(RUNS_PATH).queryParam("runStates", "NOT_A_STATE").request().get();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.readEntity(String.class)).contains("NOT_A_STATE");
    verify(runService, never())
        .findAll(anyString(), anyString(), anyList(), any(), any(), anyInt(), anyInt());
  }

  @Test
  public void testListRuns_withInvalidStartedAfter_returns400() {
    final Response response =
        UNDER_TEST.target(RUNS_PATH).queryParam("startedAfter", "not-a-timestamp").request().get();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.readEntity(String.class)).contains("startedAfter");
  }

  @Test
  public void testListRuns_withInvalidStartedBefore_returns400() {
    final Response response =
        UNDER_TEST.target(RUNS_PATH).queryParam("startedBefore", "2024-13-01").request().get();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.readEntity(String.class)).contains("startedBefore");
  }

  @Test
  public void testListRuns_withInvertedTimeWindow_returns400() {
    final Response response =
        UNDER_TEST
            .target(RUNS_PATH)
            .queryParam("startedAfter", "2024-02-01T00:00:00Z")
            .queryParam("startedBefore", "2024-01-01T00:00:00Z")
            .request()
            .get();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.readEntity(String.class)).contains("startedAfter");
  }
}
