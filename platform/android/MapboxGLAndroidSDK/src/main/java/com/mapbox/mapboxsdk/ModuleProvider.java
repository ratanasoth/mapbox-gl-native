package com.mapbox.mapboxsdk;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.mapbox.mapboxsdk.http.HttpRequest;
import com.mapbox.mapboxsdk.maps.TelemetryDefinition;

public interface ModuleProvider {

  @NonNull
  HttpRequest createHttpRequest();

  @Nullable
  TelemetryDefinition obtianTelemetry();

}
