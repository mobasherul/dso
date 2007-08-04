/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.runner;

import com.tc.net.proxy.TCPProxy;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.objectserver.control.ServerControl;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.app.ApplicationConfigBuilder;
import com.tc.simulator.app.GlobalIdGenerator;

import java.util.HashMap;
import java.util.Map;

public class TransparentAppConfig implements ApplicationConfig, ApplicationConfigBuilder {
  public static final String      adapterMapKey                     = "adapterMap";

  private final String            applicationClassname;
  private final GlobalIdGenerator idGenerator;
  private final Map               extraConfigAttributes             = new HashMap();
  private final ServerControl     serverControl;
  private ServerControl[]         serverControls;
  private TCPProxy[]              proxies;
  private int                     intensity;
  private int                     mutatorCount;
  private int                     applicationInstancePerClientCount = 1;
  private int                     validatorCount;
  private int                     adaptedMutatorCount;
  private int                     adaptedValidatorCount;
  private boolean                 isMutateValidateTest              = false;
  private boolean                 killMutators                      = false;

  public TransparentAppConfig(String applicationClassname, GlobalIdGenerator idGenerator, int mutatorCount,
                              int intensity, ServerControl[] serverControls, TCPProxy[] proxies) {
    this(applicationClassname, idGenerator, mutatorCount, intensity, null, 0, 0, 0);
    this.serverControls = serverControls;
    this.proxies = proxies;
  }

  public TransparentAppConfig(String applicationClassname, GlobalIdGenerator idGenerator, int mutatorCount,
                              int intensity, ServerControl serverControl) {
    this(applicationClassname, idGenerator, mutatorCount, intensity, serverControl, 0, 0, 0);
  }

  public TransparentAppConfig(String applicationClassname, GlobalIdGenerator idGenerator, int mutatorCount,
                              int intensity, ServerControl serverControl, int validatorCount, int adaptedMutatorCount,
                              int adaptedValidatorCount) {
    this.applicationClassname = applicationClassname;
    this.idGenerator = idGenerator;
    if (mutatorCount < 1) throw new AssertionError("Client count must be greater than 0");
    this.mutatorCount = mutatorCount;
    this.intensity = intensity;
    this.serverControl = serverControl;
    this.validatorCount = validatorCount;
    this.adaptedMutatorCount = adaptedMutatorCount;
    isLessThanOrEqualTo(adaptedMutatorCount, mutatorCount, "adaptedMutatorCount");
    this.adaptedValidatorCount = adaptedValidatorCount;
    isLessThanOrEqualTo(adaptedValidatorCount, validatorCount, "adaptedValidatorCount");
  }

  public int getAdaptedMutatorCount() {
    return adaptedMutatorCount;
  }

  public int getAdaptedValidatorCount() {
    return adaptedValidatorCount;
  }

  public void setAttribute(String key, String value) {
    extraConfigAttributes.put(key, value);
  }

  public void setAttribute(String key, Object value) {
    extraConfigAttributes.put(key, value);
  }

  public String getAttribute(String key) {
    return (String) extraConfigAttributes.get(key);
  }

  public Object getAttributeObject(String key) {
    return extraConfigAttributes.get(key);
  }

  public int nextGlobalId() {
    return (int) idGenerator.nextId();
  }

  public int getGlobalMutatorCount() {
    return this.mutatorCount * this.applicationInstancePerClientCount;
  }

  public TransparentAppConfig setApplicationInstancePerClientCount(int applicationInstanceCount) {
    this.applicationInstancePerClientCount = applicationInstanceCount;
    return this;
  }

  public int getApplicationInstancePerClientCount() {
    return this.applicationInstancePerClientCount;
  }

  public int getMutatorCount() {
    return this.mutatorCount;
  }

  public TransparentAppConfig setMutatorCount(int i) {
    this.mutatorCount = i;
    return this;
  }

  public int getIntensity() {
    return this.intensity;
  }

  public TransparentAppConfig setIntensity(int i) {
    this.intensity = i;
    return this;
  }

  public String getApplicationClassname() {
    return this.applicationClassname;
  }

  public int getGlobalValidatorCount() {
    return validatorCount * applicationInstancePerClientCount;
  }

  public int getValidatorCount() {
    return this.validatorCount;
  }

  public TransparentAppConfig setValidatorCount(int count) {
    validatorCount = count;
    return this;
  }

  public void setIsMutateValidateTest(boolean val) {
    isMutateValidateTest = val;
  }

  public boolean isMutateValidateTest() {
    return isMutateValidateTest;
  }

  public void setKillMutators(boolean val) {
    killMutators = val;
  }

  public boolean killMutators() {
    return killMutators;
  }

  public TransparentAppConfig setAdaptedMutatorCount(int count) {
    adaptedMutatorCount = count;
    isLessThanOrEqualTo(adaptedMutatorCount, mutatorCount, "adaptedMutatorCount");
    return this;
  }

  public TransparentAppConfig setAdaptedValidatorCount(int count) {
    adaptedValidatorCount = count;
    isLessThanOrEqualTo(adaptedValidatorCount, validatorCount, "adaptedValidatorCount");
    return this;
  }

  private void isLessThanOrEqualTo(int a, int b, String description) {
    if (a > b) { throw new AssertionError(description + " is too big!"); }
  }

  // ApplicationConfigBuilder interface...

  public void visitClassLoaderConfig(DSOClientConfigHelper config) {
    return;
  }

  public ApplicationConfig newApplicationConfig() {
    return this;
  }

  public ApplicationConfig copy() {
    throw new AssertionError();
  }

  public ServerControl getServerControl() {
    return serverControl;
  }

  public ServerControl[] getServerControls() {
    return serverControls;
  }

  public TCPProxy[] getProxies() {
    return proxies;
  }
}
