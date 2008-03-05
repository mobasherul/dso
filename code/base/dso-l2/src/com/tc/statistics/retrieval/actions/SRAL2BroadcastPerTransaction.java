/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.retrieval.actions;

import com.tc.statistics.StatisticRetrievalAction;
import com.tc.statistics.StatisticType;
import com.tc.statistics.StatisticData;
import com.tc.stats.counter.sampled.SampledCounter;
import com.tc.stats.counter.sampled.TimeStampedCounterValue;
import com.tc.objectserver.core.api.DSOGlobalServerStats;
import com.tc.util.Assert;

import java.util.Date;
import java.math.BigDecimal;

public class SRAL2BroadcastPerTransaction implements StatisticRetrievalAction {

  public static final String ACTION_NAME = "l2 broadcast per transaction";

  private final SampledCounter txnCounter;
  private final SampledCounter broadcastCounter;

  public SRAL2BroadcastPerTransaction(final DSOGlobalServerStats serverStats) {
    Assert.assertNotNull("serverStats", serverStats);
    this.txnCounter = serverStats.getTransactionCounter();
    this.broadcastCounter = serverStats.getBroadcastCounter();
  }

  public String getName() {
    return ACTION_NAME;
  }

  public StatisticType getType() {
    return StatisticType.SNAPSHOT;
  }

  public StatisticData[] retrieveStatisticData() {
    TimeStampedCounterValue numTxn = txnCounter.getMostRecentSample();
    TimeStampedCounterValue numBroadcasts = broadcastCounter.getMostRecentSample();
    // todo: this might have to be changed into new Date(value.getTimestamp()),
    // which is when the actual sampling occurred, we use the 'now' timestamp at
    // the moment to make sure that the statistic data retrieval arrives in order.
    // Otherwise, this data entry could be timed before the startup data event of
    // the capture session.
    Date moment = new Date();
    BigDecimal value = numTxn.getCounterValue() != 0 ? new BigDecimal((double)numBroadcasts.getCounterValue() /
                                                                      numTxn.getCounterValue()) : new BigDecimal(0);
    return new StatisticData[] { new StatisticData(ACTION_NAME, moment, value) };
  }
}
