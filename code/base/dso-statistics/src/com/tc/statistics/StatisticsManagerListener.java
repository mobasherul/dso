/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics;

public interface StatisticsManagerListener {

  public void allStatisticsDisabled(String sessionId);

  public void statisticEnabled(String sessionId, String statisticName);

  public void capturingStopped(String sessionId);
}
