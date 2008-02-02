/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import com.tc.properties.TCProperties;

public class HealthCheckerConfigImpl implements HealthCheckerConfig {

  private final boolean    enable;
  private final boolean    doSocketConnect;
  private final String     name;
  private final int        pingIdleTime;
  private final int        pingInterval;
  private final int        pingProbes;

  // Default keepalive values in seconds
  private final static int PING_IDLETIME = 45;
  private final static int PING_INTERVAL = 15;
  private final static int PING_PROBECNT = 3;

  // for testing
  boolean                  dummy         = false;

  public HealthCheckerConfigImpl(TCProperties healthCheckerProperties, String hcName) {
    this.pingIdleTime = healthCheckerProperties.getInt("ping.idletime");
    this.pingInterval = healthCheckerProperties.getInt("ping.interval");
    this.pingProbes = healthCheckerProperties.getInt("ping.probes");
    this.name = hcName;
    this.doSocketConnect = healthCheckerProperties.getBoolean("socketConnect");
    this.enable = healthCheckerProperties.getBoolean("ping.enabled");
  }

  public HealthCheckerConfigImpl(String name) {
    this(PING_IDLETIME, PING_INTERVAL, PING_PROBECNT, name, false);
  }

  public HealthCheckerConfigImpl(String name, boolean extraCheck) {
    this(PING_IDLETIME, PING_INTERVAL, PING_PROBECNT, name, extraCheck);
  }

  public HealthCheckerConfigImpl(int idle, int interval, int probes, String name) {
    this(idle, interval, probes, name, false);
  }

  public HealthCheckerConfigImpl(int idle, int interval, int probes, String name, boolean extraCheck) {
    this.pingIdleTime = idle;
    this.pingInterval = interval;
    this.pingProbes = probes;
    this.name = name;
    this.doSocketConnect = extraCheck;
    this.enable = true;
  }

  public boolean doSocketConnect() {
    return doSocketConnect;
  }

  public boolean isHealthCheckerEnabled() {
    return enable;
  }

  public int getPingIdleTime() {
    return this.pingIdleTime;
  }

  public int getPingInterval() {
    return this.pingInterval;
  }

  public int getPingProbes() {
    return this.pingProbes;
  }

  public String getHealthCheckerName() {
    return this.name;
  }

  // for testing

  public void setDummy() {
    this.dummy = true;
  }

  public boolean isDummy() {
    return this.dummy;
  }

}
