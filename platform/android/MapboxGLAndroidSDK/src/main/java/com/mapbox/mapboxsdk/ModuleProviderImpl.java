package com.mapbox.mapboxsdk;

import com.mapbox.mapboxsdk.http.HttpRequest;
import com.mapbox.mapboxsdk.maps.TelemetryDefinition;
import com.mapbox.mapboxsdk.module.http.HttpRequestImpl;
import com.mapbox.mapboxsdk.module.telemetry.TelemetryImpl;

/**
 * Injects concrete instances of configurable abstractions
 */
public class ModuleProviderImpl implements ModuleProvider {

  /**
   * Create a new concrete implementation of HttpRequest.
   *
   * @return a new instance of an HttpRequest
   */
  public HttpRequest createHttpRequest() {
    return new HttpRequestImpl();
  }

  /**
   * Get the concrete implementation of TelemetryDefinition
   *
   * @return a single instance of TelemetryImpl
   */
  public TelemetryDefinition obtianTelemetry() {
    // TODO remove singleton with next major release,
    // this is needed to make static methods on TelemetryImpl
    // backwards compatible without breaking semver
    return TelemetryImpl.getInstance();
  }
}
