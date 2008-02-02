/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

public class DisabledHealthCheckerConfigImpl implements HealthCheckerConfig {

  public boolean isHealthCheckerEnabled() {
    return false;
  }
  
  public int getPingIdleTime() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public int getPingInterval() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public int getPingProbes() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public String getHealthCheckerName() {
    throw new AssertionError("Disabled HealthChecker");
  }

  public boolean doSocketConnect() {
    throw new AssertionError("Disabled HealthChecker");
  }


}
