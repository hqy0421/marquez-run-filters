/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.models;

import io.dropwizard.jersey.errors.ErrorMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import marquez.common.models.RunState;

/**
 * Optional filters for listing the runs of a job. A filter is considered "empty" when no run
 * states and no time boundaries have been provided; in that case callers are expected to fall back
 * to the unfiltered (backwards compatible) behavior.
 */
@EqualsAndHashCode
@ToString
public class RunsFilter {
  @Getter private final List<RunState> runStates;
  @Nullable private final Instant startedAfter;
  @Nullable private final Instant startedBefore;

  private RunsFilter(
      List<RunState> runStates, @Nullable Instant startedAfter, @Nullable Instant startedBefore) {
    this.runStates = runStates;
    this.startedAfter = startedAfter;
    this.startedBefore = startedBefore;
  }

  /**
   * Builds a {@link RunsFilter} from raw query parameter values.
   *
   * <p>Run states may be repeated ({@code ?runStates=FAILED&runStates=ABORTED}) or comma-separated
   * ({@code ?runStates=FAILED,ABORTED}) and are matched case-insensitively. Time boundaries must be
   * ISO-8601 timestamps with an explicit offset (e.g. {@code 2024-01-01T00:00:00Z}).
   *
   * @throws BadRequestException on unknown run states, unparseable timestamps, or a time window
   *     whose lower bound is after its upper bound
   */
  public static RunsFilter of(
      @Nullable List<String> runStates,
      @Nullable String startedAfter,
      @Nullable String startedBefore) {
    final List<RunState> states = parseRunStates(runStates);
    final Instant after = parseTimestamp(startedAfter, "startedAfter");
    final Instant before = parseTimestamp(startedBefore, "startedBefore");
    if (after != null && before != null && after.isAfter(before)) {
      throw badRequest(
          String.format(
              "Invalid time window: 'startedAfter' (%s) must not be after 'startedBefore' (%s).",
              after, before));
    }
    return new RunsFilter(states, after, before);
  }

  /** Returns true when no filter has been provided. */
  public boolean isEmpty() {
    return runStates.isEmpty() && startedAfter == null && startedBefore == null;
  }

  public Optional<Instant> getStartedAfter() {
    return Optional.ofNullable(startedAfter);
  }

  public Optional<Instant> getStartedBefore() {
    return Optional.ofNullable(startedBefore);
  }

  private static List<RunState> parseRunStates(@Nullable List<String> stateValues) {
    if (stateValues == null || stateValues.isEmpty()) {
      return List.of();
    }
    final List<RunState> states = new ArrayList<>();
    final List<String> invalid = new ArrayList<>();
    for (String stateValue : stateValues) {
      if (stateValue == null) {
        continue;
      }
      for (String value : stateValue.split(",")) {
        final String state = value.trim();
        if (state.isEmpty()) {
          continue;
        }
        try {
          states.add(RunState.valueOf(state.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
          invalid.add(state);
        }
      }
    }
    if (!invalid.isEmpty()) {
      throw badRequest(
          String.format(
              "Invalid run state(s): %s. Valid run states: %s.",
              invalid.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")),
              Arrays.stream(RunState.values())
                  .map(RunState::name)
                  .collect(Collectors.joining(", "))));
    }
    return states;
  }

  @Nullable
  private static Instant parseTimestamp(@Nullable String value, String paramName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      // Also accept ISO-8601 offsets other than 'Z' (e.g. +08:00).
      try {
        return OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException e2) {
        throw badRequest(
            String.format(
                "Invalid '%s' value: '%s'. Expected an ISO-8601 timestamp"
                    + " (e.g. 2024-01-01T00:00:00Z).",
                paramName, value));
      }
    }
  }

  /**
   * Builds a {@link BadRequestException} whose response deterministically carries a JSON error
   * message, so clients always receive a meaningful 4xx body.
   */
  private static BadRequestException badRequest(String message) {
    final Response response =
        Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message))
            .build();
    return new BadRequestException(message, response);
  }
}
